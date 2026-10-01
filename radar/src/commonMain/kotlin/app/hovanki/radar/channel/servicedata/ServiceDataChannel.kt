package app.hovanki.radar.channel.servicedata

import app.hovanki.radar.AdData
import app.hovanki.radar.AdPart
import app.hovanki.radar.AirPlatform
import app.hovanki.radar.AirRole
import app.hovanki.radar.BleUuid
import app.hovanki.radar.ChannelReading
import app.hovanki.radar.ChannelUse
import app.hovanki.radar.GameAir
import app.hovanki.radar.HeardFrame
import app.hovanki.radar.RadarChannel
import app.hovanki.radar.ScanInterest
import app.hovanki.radar.SightingVia
import app.hovanki.shared.rules.RadarToken

/**
 * How an Android hider puts its token on the air (`ble.service_data`, docs/adr/0017-radar-techniques-and-big-run.md,
 * section 2.3). The first apps put the game's service UUID and the token as its data in one packet: 18 + 22 = 40
 * bytes, and a legacy advertisement holds 31, so Android refused it and nothing went out. Three layouts fit, and
 * compete until the field test says which one every phone hears:
 *
 * - [SCAN_RESPONSE]: the UUID in the packet (18 bytes: an iPhone's scan for the service lets it through), the token
 *   as its service data in the scan response (22 bytes): only an active scan gets it.
 * - [BARE]: only the service data (22 bytes), no list of UUIDs: one packet, a passive scan reads it; iOS can't scan
 *   for service data, so an iPhone probably never hears it.
 * - [MFR]: the game's UUID and the token in manufacturer data under the test company id 0xFFFF (26 bytes).
 */
enum class AdLayout(val key: String) {
    SCAN_RESPONSE("scan_response"),
    BARE("bare"),
    MFR("mfr"),
}

/**
 * The service data channel in one [layout]: [adPart] for an Android hider; [decode] reads its layout only, so every
 * frame is read by exactly one of the three, and a listener that has all three hears every Android hider.
 */
class ServiceDataChannel(val layout: AdLayout) : RadarChannel {
    override val id: String = "$TECH.${layout.key}"
    override val use: ChannelUse = ChannelUse.GAME

    override fun adPart(token: String, role: AirRole, platform: AirPlatform): AdPart? {
        if (role != AirRole.HIDER || platform != AirPlatform.ANDROID || !RadarToken.isWellFormed(token)) return null
        val data = AdData(serviceData = mapOf(GameAir.SERVICE_UUID to token))
        return when (layout) {
            AdLayout.SCAN_RESPONSE ->
                AdPart(
                    id,
                    main = AdData(serviceUuids = listOf(GameAir.SERVICE_UUID)),
                    scanResponse = data,
                    layout = layout.key,
                )

            AdLayout.BARE -> AdPart(id, main = data, layout = layout.key)

            AdLayout.MFR -> AdPart(
                id,
                main = AdData(manufacturerData = mapOf(GameAir.TEST_COMPANY_ID to MFR_PREFIX + token)),
                layout = layout.key,
            )
        }
    }

    override fun interests(platform: AirPlatform): List<ScanInterest> = when (layout) {
        // Every platform can scan for a listed UUID; the token comes in the scan response.
        AdLayout.SCAN_RESPONSE -> listOf(ScanInterest.ServiceUuid(GameAir.SERVICE_UUID))

        // Only Android filters by service data or manufacturer data; iOS hears these only when its scan for the
        // service lets them through, which the field test tells.
        AdLayout.BARE -> listOf(ScanInterest.ServiceData(GameAir.SERVICE_UUID))

        AdLayout.MFR -> listOf(ScanInterest.Manufacturer(GameAir.TEST_COMPANY_ID, MFR_PREFIX))
    }

    override fun decode(frame: HeardFrame): ChannelReading? {
        val token = when (layout) {
            AdLayout.SCAN_RESPONSE, AdLayout.BARE -> {
                val listed = frame.serviceUuids.any { BleUuid.canonical(it) == GameAir.SERVICE_UUID }
                if (listed != (layout == AdLayout.SCAN_RESPONSE)) return null
                frame.serviceData.entries.firstOrNull { BleUuid.canonical(it.key) == GameAir.SERVICE_UUID }?.value
            }

            AdLayout.MFR -> frame.manufacturerData[GameAir.TEST_COMPANY_ID]
                ?.lowercase()
                ?.takeIf { it.startsWith(MFR_PREFIX) }
                ?.removePrefix(MFR_PREFIX)
        }?.lowercase() ?: return null
        if (!RadarToken.isWellFormed(token)) return null
        val via = if (layout == AdLayout.MFR) SightingVia.MANUFACTURER_DATA else SightingVia.SERVICE_DATA
        return ChannelReading(id, listOf(token), via, use)
    }

    companion object {
        const val TECH = "ble.service_data"

        /**
         * The `.mfr` frame after the company id: `48 01` («H», version 1), the game's UUID, then the token: 2 + 16 + 4
         * bytes, 26 with the field's length, type and company id.
         */
        val MFR_PREFIX: String = "4801" + BleUuid.canonical(GameAir.SERVICE_UUID).replace("-", "").lowercase()
    }
}
