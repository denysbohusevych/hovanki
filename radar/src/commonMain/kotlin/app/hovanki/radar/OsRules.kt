package app.hovanki.radar

import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.rules.AppleData
import app.hovanki.shared.rules.IBeaconFrame
import app.hovanki.shared.rules.OverflowArea

/** Whether the app is on the screen or in the background (an iPhone in a pocket is the latter). */
enum class AppState { ON_SCREEN, BACKGROUND }

/**
 * What one phone has on the air: the advertisement and its scan response as listeners merge them (Android and
 * CoreBluetooth hand them over as one record). An overflow area and an iBeacon frame are Apple's manufacturer data.
 */
class Broadcast(
    val name: String? = null,
    val serviceUuids: List<String> = emptyList(),
    val serviceData: Map<String, ByteArray> = emptyMap(),
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    val connectable: Boolean = false,
) {
    val apple: ByteArray? get() = manufacturerData[RadarService.APPLE_COMPANY_ID]

    /** The overflow area's bits, if this is one. */
    val maskBits: Set<Int> get() = apple?.let(AppleData::overflowMask)?.let(OverflowArea::bitsOf).orEmpty()

    val iBeacon: IBeaconFrame? get() = apple?.let(AppleData::iBeacon)

    override fun toString(): String = "Broadcast(name=$name, uuids=$serviceUuids, " +
        "svcdata=${serviceData.mapValues { it.value.toHex() }}, mfr=${manufacturerData.mapValues { it.value.toHex() }})"
}

/** What the OS put on the air for an advertisement: [broadcast] (null: nothing), or [error] as the OS says it. */
data class Sent(val broadcast: Broadcast?, val error: String? = null)

/**
 * The operating systems' rules for the radar's advertisements and scans, as pure functions: the simulator of the bots
 * (`host.JvmAirHost`) puts the air together with them, so a channel behaves in e2e the way it does on the phones
 * (docs/adr/0017-radar-techniques-and-big-run.md, section 2.2). Sending ([send]):
 *
 * - **Android**: no local name; both packets within [AdBudget.LEGACY_MAX], else `startAdvertising` fails with
 *   `ADVERTISE_FAILED_DATA_TOO_LARGE` (code 1) and nothing goes out; an [AdPart.IBeacon] is Apple's manufacturer data.
 * - **iOS on screen** (and a Mac): no service data or manufacturer data ever; an iBeacon advertises alone; one 128-bit
 *   service UUID fits the packet, the others go to the overflow area; the name is cut to the room left (8 characters
 *   next to a 128-bit UUID).
 * - **iOS in the background**: no name, no service data or manufacturer data, no iBeacon; every service UUID becomes
 *   its bit in the overflow mask (Apple's manufacturer data `01` + 16 bytes; [overflowBit]).
 *
 * Hearing ([hear]):
 *
 * - **Android and a Mac** hear every frame a filter matches, raw: Apple's manufacturer data (masks, iBeacons) too.
 * - **iOS** (CoreBluetooth) hears only frames whose service UUIDs match a filter, and, on screen, an overflow area
 *   whose bits match a UUID it scans for, as listed UUIDs ([AirFrame.overflowUuids]); never Apple's manufacturer data.
 *   A seeker's iBeacon only through CoreLocation ranging ([ScanInterest.BeaconRanging]), on screen or in the
 *   background; region monitoring is stateful and the simulator's ([regions]).
 *
 * Not modelled: iOS coalescing the duplicates of a background scan, scan rates, the Android scanner's throttling.
 */
object OsRules {
    private const val FLAGS = 3
    private const val FIELD_HEADER = 2

    fun send(parts: List<AdPart>, platform: Platform, app: AppState): Sent = when (platform) {
        Platform.ANDROID -> android(parts)
        Platform.IOS -> ios(parts, app)
        Platform.OTHER -> ios(parts, AppState.ON_SCREEN)
    }

    /**
     * The overflow bit of [uuid] as far as it is known: the table's ([OverflowArea]) and the game's service
     * ([OverflowArea.OUR_SERVICE_BIT]); null: Apple's hash of it is unknown, and the simulator loses it.
     */
    fun overflowBit(uuid: String): Int? {
        val normalized = BleUuid.normalize(uuid)
        if (normalized == RadarService.UUID) return OverflowArea.OUR_SERVICE_BIT
        return if (normalized.length == 36) OverflowArea.bitOf(normalized) else null
    }

    /**
     * What a [listener] (its [app] state, its scan's [interests]) hears of [broadcast]: nothing, a CoreBluetooth or
     * Android frame, and on an iPhone a ranged beacon too. [peer] is the sender as the OS names it.
     */
    fun hear(
        broadcast: Broadcast,
        listener: Platform,
        app: AppState,
        interests: List<ScanInterest>,
        rssi: Int,
        atMillis: Long,
        peer: String?,
    ): List<AirFrame> = when (listener) {
        Platform.ANDROID, Platform.OTHER -> {
            if (interests.any { matchesRaw(broadcast, it) }) {
                val api = if (listener == Platform.ANDROID) RadioApi.ANDROID_LE else RadioApi.MAC_COREBLUETOOTH
                listOf(
                    AirFrame(
                        atMillis = atMillis,
                        rssi = rssi,
                        api = api,
                        peer = peer,
                        name = broadcast.name,
                        serviceUuids = broadcast.serviceUuids,
                        serviceData = broadcast.serviceData,
                        manufacturerData = broadcast.manufacturerData,
                        connectable = broadcast.connectable,
                        raw = record(broadcast).takeIf { listener == Platform.ANDROID },
                    ),
                )
            } else {
                emptyList()
            }
        }

        Platform.IOS -> buildList {
            coreBluetooth(broadcast, app, interests, rssi, atMillis, peer)?.let(::add)
            val beacon = broadcast.iBeacon
            if (beacon != null && interests.any { it is ScanInterest.BeaconRanging && beacon.isOf(it.uuid) }) {
                add(AirFrame(atMillis, rssi, RadioApi.CORELOCATION_RANGING, peer = null, iBeacon = beacon))
            }
        }
    }

    /** The UUIDs of the beacon regions [interests] monitor that [broadcast] is a beacon of (iOS only). */
    fun regions(broadcast: Broadcast, listener: Platform, interests: List<ScanInterest>): Set<String> {
        if (listener != Platform.IOS) return emptySet()
        val beacon = broadcast.iBeacon ?: return emptySet()
        return interests.filterIsInstance<ScanInterest.BeaconRegion>()
            .filter { beacon.isOf(it.uuid) }
            .mapTo(mutableSetOf()) { BleUuid.normalize(it.uuid) }
    }

    private fun android(parts: List<AdPart>): Sent {
        val sent = parts.filter { it !is AdPart.LocalName }
        if (sent.isEmpty()) return Sent(null)
        if (!AdBudget.fits(sent)) return Sent(null, "code 1")
        val manufacturer = linkedMapOf<Int, ByteArray>()
        for (part in sent) {
            when (part) {
                is AdPart.ManufacturerData -> manufacturer.getOrPut(part.companyId) { part.data }
                is AdPart.IBeacon -> manufacturer.getOrPut(RadarService.APPLE_COMPANY_ID) { IBeaconBytes.of(part) }
                else -> Unit
            }
        }
        return Sent(
            Broadcast(
                serviceUuids = sent.filterIsInstance<AdPart.ServiceUuid>().map {
                    BleUuid.normalize(it.uuid)
                }.distinct(),
                serviceData = sent.filterIsInstance<AdPart.ServiceData>()
                    .associate { BleUuid.normalize(it.uuid) to it.data },
                manufacturerData = manufacturer,
            ),
        )
    }

    private fun ios(parts: List<AdPart>, app: AppState): Sent {
        val beacon = parts.firstNotNullOfOrNull { it as? AdPart.IBeacon }
        if (beacon != null) {
            if (app == AppState.BACKGROUND) return Sent(null)
            return Sent(Broadcast(manufacturerData = mapOf(RadarService.APPLE_COMPANY_ID to IBeaconBytes.of(beacon))))
        }
        val uuids = parts.filterIsInstance<AdPart.ServiceUuid>().map { BleUuid.normalize(it.uuid) }.distinct()
        val name = parts.firstNotNullOfOrNull { (it as? AdPart.LocalName)?.name }
        if (app == AppState.BACKGROUND) {
            val bits = uuids.mapNotNull(::overflowBit)
            return Sent(if (bits.isEmpty()) null else Broadcast(manufacturerData = mask(bits)))
        }
        // On screen: one 128-bit UUID in the packet (with the flags, 3 + 18 of 31 bytes), the rest overflows.
        val (long, short) = uuids.partition { BleUuid.size(it) == 16 }
        val listed = short + long.take(1)
        val overflow = long.drop(1).mapNotNull(::overflowBit)
        val room = AdBudget.LEGACY_MAX - FLAGS - AdBudget.bytes(listed.map { AdPart.ServiceUuid(it) }, false) -
            FIELD_HEADER
        val cut = name?.take(room.coerceAtLeast(0))?.takeIf { it.isNotEmpty() }
        if (listed.isEmpty() && overflow.isEmpty() && cut == null) return Sent(null)
        return Sent(
            Broadcast(
                name = cut,
                serviceUuids = listed,
                manufacturerData = if (overflow.isEmpty()) emptyMap() else mask(overflow),
                connectable = true,
            ),
        )
    }

    private fun mask(bits: List<Int>): Map<Int, ByteArray> =
        mapOf(RadarService.APPLE_COMPANY_ID to byteArrayOf(AppleData.OVERFLOW.toByte()) + OverflowArea.maskOf(bits))

    private fun matchesRaw(broadcast: Broadcast, interest: ScanInterest): Boolean = when (interest) {
        is ScanInterest.Service -> {
            val uuid = BleUuid.normalize(interest.uuid)
            uuid in broadcast.serviceUuids || broadcast.serviceData.keys.any { BleUuid.normalize(it) == uuid }
        }

        is ScanInterest.Manufacturer -> broadcast.manufacturerData[interest.companyId]?.let { data ->
            data.size >= interest.prefix.size && data.copyOf(interest.prefix.size).contentEquals(interest.prefix)
        } == true

        is ScanInterest.BeaconRanging, is ScanInterest.BeaconRegion, is ScanInterest.OverflowUuids -> false
    }

    private fun coreBluetooth(
        broadcast: Broadcast,
        app: AppState,
        interests: List<ScanInterest>,
        rssi: Int,
        atMillis: Long,
        peer: String?,
    ): AirFrame? {
        val scanned = interests.flatMap {
            when (it) {
                is ScanInterest.Service -> listOf(it.uuid)
                is ScanInterest.OverflowUuids -> it.uuids
                else -> emptyList()
            }
        }.map(BleUuid::normalize).distinct()
        val listed = scanned.any { it in broadcast.serviceUuids }
        val bits = broadcast.maskBits
        val overflow = if (app == AppState.ON_SCREEN && bits.isNotEmpty()) {
            scanned.filter { overflowBit(it) in bits }
        } else {
            emptyList()
        }
        if (!listed && overflow.isEmpty()) return null
        return AirFrame(
            atMillis = atMillis,
            rssi = rssi,
            api = RadioApi.COREBLUETOOTH,
            peer = peer,
            name = broadcast.name,
            serviceUuids = broadcast.serviceUuids,
            overflowUuids = overflow,
            serviceData = broadcast.serviceData,
            manufacturerData = broadcast.manufacturerData - RadarService.APPLE_COMPANY_ID,
            connectable = broadcast.connectable,
        )
    }

    private fun IBeaconFrame.isOf(uuid: String): Boolean = uuidHex == BleUuid.hex(uuid)

    /** The advertising record as Android's `ScanRecord.getBytes` gives it: AD structures, UUIDs little-endian. */
    internal fun record(broadcast: Broadcast): ByteArray {
        val out = mutableListOf<Byte>()
        fun field(type: Int, body: ByteArray) {
            out += (body.size + 1).toByte()
            out += type.toByte()
            out += body.toList()
        }
        if (broadcast.connectable) field(0x01, byteArrayOf(0x06))
        for ((size, type) in listOf(2 to 0x03, 4 to 0x05, 16 to 0x07)) {
            val uuids = broadcast.serviceUuids.filter { BleUuid.size(it) == size }
            if (uuids.isNotEmpty()) {
                field(
                    type,
                    uuids.fold(ByteArray(0)) { all, it ->
                        all +
                            BleUuid.bytes(it).reversedArray()
                    },
                )
            }
        }
        for ((uuid, data) in broadcast.serviceData) {
            val type = when (BleUuid.size(uuid)) {
                2 -> 0x16
                4 -> 0x20
                else -> 0x21
            }
            field(type, BleUuid.bytes(uuid).reversedArray() + data)
        }
        for ((company, data) in broadcast.manufacturerData) {
            field(0xFF, byteArrayOf(company.toByte(), (company shr 8).toByte()) + data)
        }
        broadcast.name?.let { field(0x09, it.encodeToByteArray()) }
        return out.toByteArray()
    }
}
