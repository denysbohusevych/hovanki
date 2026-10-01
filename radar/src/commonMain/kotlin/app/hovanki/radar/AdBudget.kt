package app.hovanki.radar

import app.hovanki.shared.protocol.Platform

/**
 * The byte budget of a legacy advertisement on Android (docs/radar-run.md §3), counted the way
 * `BluetoothLeAdvertiser.totalBytes` does before `startAdvertising` refuses a packet with
 * `ADVERTISE_FAILED_DATA_TOO_LARGE`: 2 bytes of header per field; the service UUIDs of one size share one field
 * (16 bytes per 128-bit UUID, 4 per 32-bit, 2 per 16-bit); service data is its UUID and the data; manufacturer data 2
 * bytes of company id and the data; the flags (3 bytes) only when the advertisement is connectable. The advertisement
 * and the scan response each have [LEGACY_MAX] bytes; only [AdPart.ServiceData] with `inScanResponse` goes into the
 * latter.
 */
object AdBudget {
    const val LEGACY_MAX = 31
    private const val FIELD_HEADER = 2
    private const val FLAGS = 3
    private const val COMPANY_ID = 2

    /** The bytes of [parts] in one packet: the scan response when [scanResponse], else the advertisement. */
    fun bytes(parts: List<AdPart>, scanResponse: Boolean, connectable: Boolean = false): Int =
        fields(parts, scanResponse, connectable).sumOf { it.second }

    /** Both packets within [LEGACY_MAX]. */
    fun fits(parts: List<AdPart>, connectable: Boolean = false): Boolean =
        bytes(parts, scanResponse = false, connectable) <= LEGACY_MAX &&
            bytes(parts, scanResponse = true, connectable) <= LEGACY_MAX

    /**
     * The layout in words for the log's `adv` event: `adv 18 (uuid128 18) + rsp 22 (svcdata 22)`; the scan response
     * only when something is in it.
     */
    fun layout(parts: List<AdPart>, connectable: Boolean = false): String {
        fun packet(label: String, scanResponse: Boolean): String {
            val fields = fields(parts, scanResponse, connectable)
            val total = fields.sumOf { it.second }
            return if (fields.isEmpty()) {
                "$label $total"
            } else {
                "$label $total (${fields.joinToString(", ") { "${it.first} ${it.second}" }})"
            }
        }
        val adv = packet("adv", scanResponse = false)
        return if (parts.any { it.inScanResponse }) "$adv + ${packet("rsp", scanResponse = true)}" else adv
    }

    /** The fields of one packet: a label and its bytes, in the order `totalBytes` counts them. */
    private fun fields(parts: List<AdPart>, scanResponse: Boolean, connectable: Boolean): List<Pair<String, Int>> {
        val here = parts.filter { it.inScanResponse == scanResponse }
        return buildList {
            if (connectable && !scanResponse) add("flags" to FLAGS)
            val uuids = here.filterIsInstance<AdPart.ServiceUuid>().map { BleUuid.normalize(it.uuid) }.distinct()
            for ((size, label) in listOf(2 to "uuid16", 4 to "uuid32", 16 to "uuid128")) {
                val count = uuids.count { BleUuid.size(it) == size }
                if (count > 0) add(label to FIELD_HEADER + count * size)
            }
            for (part in here) {
                when (part) {
                    is AdPart.ServiceData -> add("svcdata" to FIELD_HEADER + BleUuid.size(part.uuid) + part.data.size)
                    is AdPart.ManufacturerData -> add("mfr" to FIELD_HEADER + COMPANY_ID + part.data.size)
                    is AdPart.IBeacon -> add("ibeacon" to FIELD_HEADER + COMPANY_ID + IBeaconBytes.LENGTH)
                    is AdPart.LocalName -> add("name" to FIELD_HEADER + part.name.encodeToByteArray().size)
                    is AdPart.ServiceUuid -> Unit
                }
            }
        }
    }

    private val AdPart.inScanResponse: Boolean get() = this is AdPart.ServiceData && inScanResponse
}

/** A part of the advertisement with the channel ([tech]) it came from. */
data class TechPart(val tech: String, val part: AdPart)

/** A part the host left out, and [why], for the log's `adv` event. */
data class Dropped(val tech: String, val part: AdPart, val why: String)

/**
 * The advertisement a host asks its OS for (docs/adr/0017-radar-techniques-and-big-run.md, section 2.2): assembled
 * from every channel's parts, in the channels' order, a service UUID two channels want once; minus what the platform
 * can't send ([dropped]). The hosts of Android and iOS and the simulator's share it.
 */
data class AdPlan(val parts: List<TechPart>, val dropped: List<Dropped> = emptyList()) {
    val adParts: List<AdPart> get() = parts.map { it.part }

    /** The channels with something on the air, in order. */
    val techs: List<String> get() = parts.map { it.tech }.distinct()

    val isEmpty: Boolean get() = parts.isEmpty()

    fun layout(connectable: Boolean = false): String = AdBudget.layout(adParts, connectable)

    /**
     * Tells [trace] about [action] (`start`, `stop`, `failed` with [error], `skipped_background`) once per channel on
     * the air; with `start` and `failed`, also every part left out ([traceDropped]).
     */
    fun trace(trace: RadarTrace, action: String, token: String?, error: String? = null, connectable: Boolean = false) {
        val layout = layout(connectable)
        for (tech in techs) trace.advertise(action, tech, token, layout, error)
        if (action == "start" || action == "failed") traceDropped(trace, token, connectable)
    }

    /** Every part left out: `dropped`, the reason as the error. */
    fun traceDropped(trace: RadarTrace, token: String?, connectable: Boolean = false) {
        val layout = layout(connectable)
        for (drop in dropped) trace.advertise("dropped", drop.tech, token, layout, drop.why)
    }

    companion object {
        /** Every channel's parts for [token] in [role], a service UUID once. */
        fun assemble(channels: List<RadarChannel>, token: String, role: RadarRole): List<TechPart> {
            val seen = mutableSetOf<String>()
            return channels.flatMap { channel -> channel.advertise(token, role).map { TechPart(channel.id, it) } }
                .filter { (_, part) -> part !is AdPart.ServiceUuid || seen.add(BleUuid.normalize(part.uuid)) }
        }

        /**
         * The plan for [platform]. Android: no local name (it can't name one advertisement without renaming the
         * phone), then parts from the end until both packets fit ([AdBudget]). iOS and a Mac (CoreBluetooth): no
         * service data or manufacturer data (an app can't advertise them); an iBeacon advertises alone.
         */
        fun of(
            channels: List<RadarChannel>,
            token: String,
            role: RadarRole,
            platform: Platform,
            connectable: Boolean = false,
        ): AdPlan = forPlatform(assemble(channels, token, role), platform, connectable)

        fun forPlatform(parts: List<TechPart>, platform: Platform, connectable: Boolean = false): AdPlan {
            val kept = mutableListOf<TechPart>()
            val dropped = mutableListOf<Dropped>()
            when (platform) {
                Platform.ANDROID -> {
                    for (part in parts) {
                        if (part.part is AdPart.LocalName) {
                            dropped += Dropped(part.tech, part.part, "no local name on android")
                        } else {
                            kept += part
                        }
                    }
                    while (kept.isNotEmpty() && !AdBudget.fits(kept.map { it.part }, connectable)) {
                        val over = AdBudget.layout(kept.map { it.part }, connectable)
                        val last = kept.removeAt(kept.lastIndex)
                        dropped += Dropped(last.tech, last.part, "over ${AdBudget.LEGACY_MAX} bytes: $over")
                    }
                }

                Platform.IOS, Platform.OTHER -> {
                    val beacon = parts.firstOrNull { it.part is AdPart.IBeacon }
                    for (part in parts) {
                        val why = when {
                            part.part is AdPart.ServiceData -> "no service data on ios"
                            part.part is AdPart.ManufacturerData -> "no manufacturer data on ios"
                            beacon != null && part !== beacon -> "an ibeacon advertises alone on ios"
                            else -> null
                        }
                        if (why == null) kept += part else dropped += Dropped(part.tech, part.part, why)
                    }
                }
            }
            return AdPlan(kept, dropped)
        }
    }
}
