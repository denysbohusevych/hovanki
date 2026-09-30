package app.hovanki.radar.channel.overflow

import app.hovanki.radar.AdPart
import app.hovanki.radar.AirFrame
import app.hovanki.radar.Availability
import app.hovanki.radar.Decoded
import app.hovanki.radar.RadarCaps
import app.hovanki.radar.RadarChannel
import app.hovanki.radar.RadarRole
import app.hovanki.radar.RadarService
import app.hovanki.radar.ScanInterest
import app.hovanki.radar.SightingVia
import app.hovanki.radar.TechniqueStatus
import app.hovanki.shared.rules.AppleData
import app.hovanki.shared.rules.OverflowArea
import app.hovanki.shared.rules.OverflowCode
import app.hovanki.shared.rules.RadarToken

/**
 * `ble.overflow` (docs/adr/0016-iphone-overflow-radar.md, ADR 0017 section 2.3): a locked iPhone hider, the token
 * Manchester-coded in the overflow area's mask ([OverflowCode]). The hider advertises the game's service and the
 * overflow table's UUIDs of the token's bits; in the background iOS turns them into the mask. Android and a Mac read
 * the mask raw in Apple's manufacturer data ([SightingVia.OVERFLOW_RAW]); an iPhone on screen gets the table's UUIDs
 * listed by CoreBluetooth ([SightingVia.OVERFLOW_UUIDS]). The radio lab's only (behind `OVERFLOW_RADAR` in the plan).
 */
data object OverflowChannel : RadarChannel {
    override val id: String = "ble.overflow"
    override val status: TechniqueStatus = TechniqueStatus.LAB

    override fun available(caps: RadarCaps): Availability = caps.noBluetoothLe
        ?: if (caps.canReadOverflow || caps.canAdvertiseOverflow) {
            Availability.Available
        } else {
            Availability.Unavailable("neither reads nor advertises an overflow area")
        }

    override fun advertise(token: String, role: RadarRole): List<AdPart> = if (role == RadarRole.HIDER &&
        RadarToken.isWellFormed(token)
    ) {
        OverflowParts(OverflowCode.encode(token))
    } else {
        emptyList()
    }

    override fun interests(): List<ScanInterest> = listOf(
        ScanInterest.Manufacturer(RadarService.APPLE_COMPANY_ID, byteArrayOf(AppleData.OVERFLOW.toByte())),
        ScanInterest.OverflowUuids(OverflowArea.UUIDS),
    )

    /** The mask's bits, raw or listed, decoded; a damaged mask gives its first candidate and all of them. */
    override fun decode(frame: AirFrame): List<Decoded> {
        val raw = frame.manufacturerData[RadarService.APPLE_COMPANY_ID]?.let(AppleData::overflowMask)
        val (bits, via) = when {
            raw != null -> OverflowArea.bitsOf(raw) to SightingVia.OVERFLOW_RAW

            frame.overflowUuids.isNotEmpty() ->
                frame.overflowUuids.mapNotNull(OverflowArea::bitOf).toSet() to SightingVia.OVERFLOW_UUIDS

            else -> return emptyList()
        }
        val candidates = OverflowCode.decode(bits)
        return if (candidates.isEmpty()) emptyList() else listOf(Decoded(candidates.first(), via, candidates))
    }
}

/** What an iPhone advertises for overflow [bits]: the game's service and the table's UUID of every bit. */
object OverflowParts {
    operator fun invoke(bits: Set<Int>): List<AdPart> =
        listOf(AdPart.ServiceUuid(RadarService.UUID)) + bits.sorted().map { AdPart.ServiceUuid(OverflowArea.uuid(it)) }
}
