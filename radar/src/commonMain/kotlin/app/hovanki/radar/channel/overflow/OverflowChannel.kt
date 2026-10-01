package app.hovanki.radar.channel.overflow

import app.hovanki.radar.AdPart
import app.hovanki.radar.AirHex
import app.hovanki.radar.AirPlatform
import app.hovanki.radar.AirRole
import app.hovanki.radar.ChannelReading
import app.hovanki.radar.ChannelUse
import app.hovanki.radar.GameAir
import app.hovanki.radar.HeardFrame
import app.hovanki.radar.RadarChannel
import app.hovanki.radar.ScanInterest
import app.hovanki.radar.SightingVia
import app.hovanki.shared.rules.AppleData
import app.hovanki.shared.rules.OverflowArea
import app.hovanki.shared.rules.OverflowCode
import app.hovanki.shared.rules.RadarToken

/**
 * A locked iPhone's token in its overflow area (`ble.overflow`, docs/adr/0016-iphone-overflow-radar.md and ADR 0017
 * §2.3), «in the shadow» (ADR 0018 §4 B): what it reads goes into the journal only, never to the game.
 *
 * - Advertising (iOS only, either role): the UUIDs of the table whose bits Manchester-code the token
 *   ([OverflowCode.encode], the lab's layout), after the game's service, as the advertisement a locked iPhone keeps on
 *   the air ([AdPart.backgroundUuids]): iOS can't start or change one in the background, so the host puts it on while
 *   the app is still active, as it resigns, and the token in it stays the one of that moment («замёрзший жетон»).
 * - Listening: Android reads the mask's raw 16 bytes in Apple's manufacturer data (`4C 00 01 …`); an iPhone on the
 *   screen asks CoreBluetooth for the table's UUIDs and gets the ones whose bits stand.
 */
class OverflowChannel : RadarChannel {
    override val id: String = TECH
    override val use: ChannelUse = ChannelUse.SHADOW

    override fun adPart(token: String, role: AirRole, platform: AirPlatform): AdPart? {
        if (platform != AirPlatform.IOS || !RadarToken.isWellFormed(token)) return null
        val uuids = OverflowCode.encode(token).sorted().map(OverflowArea::uuid)
        return AdPart(id, backgroundUuids = listOf(GameAir.SERVICE_UUID) + uuids)
    }

    override fun interests(platform: AirPlatform): List<ScanInterest> = when (platform) {
        AirPlatform.IOS -> listOf(ScanInterest.OverflowUuids(OverflowArea.UUIDS))
        else -> listOf(ScanInterest.Manufacturer(GameAir.APPLE_COMPANY_ID, OVERFLOW_PREFIX))
    }

    override fun decode(frame: HeardFrame): ChannelReading? {
        val (bits, via) = bitsOf(frame) ?: return null
        val tokens = OverflowCode.decode(bits)
        if (tokens.isEmpty()) return null
        return ChannelReading(id, tokens, via, use)
    }

    companion object {
        const val TECH = "ble.overflow"

        /** Apple's frame type of the overflow area, right after the company id (no length byte follows). */
        val OVERFLOW_PREFIX: String = AppleData.OVERFLOW.toString(16).padStart(2, '0')

        /** The mask's bits in [frame], and how they came; null: no mask in it. */
        fun bitsOf(frame: HeardFrame): Pair<Set<Int>, SightingVia>? {
            if (frame.overflowUuids.isNotEmpty()) {
                val bits = frame.overflowUuids.mapNotNull(OverflowArea::bitOf).toSet()
                return if (bits.isEmpty()) null else bits to SightingVia.OVERFLOW_UUIDS
            }
            val apple = frame.manufacturerData[GameAir.APPLE_COMPANY_ID] ?: return null
            val mask = AppleData.overflowMask(AirHex.bytes(apple)) ?: return null
            return OverflowArea.bitsOf(mask) to SightingVia.OVERFLOW_RAW
        }
    }
}
