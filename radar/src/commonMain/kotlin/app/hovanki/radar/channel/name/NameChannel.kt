package app.hovanki.radar.channel.name

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
import app.hovanki.shared.rules.RadarToken

/**
 * `ble.name` (docs/adr/0017-radar-techniques-and-big-run.md, section 2.3): an iPhone hider on the screen, the game's
 * service UUID and the token as the local name (iOS lets an app advertise nothing else, and keeps 8 characters of
 * the name next to a 128-bit UUID: exactly the token). In the background iOS sends no name: nobody reads the token.
 * Android can't name one advertisement: its host drops the name and says so. Everybody hears it by the service.
 */
data object NameChannel : RadarChannel {
    override val id: String = "ble.name"
    override val status: TechniqueStatus = TechniqueStatus.GAME

    override fun available(caps: RadarCaps): Availability = caps.noBluetoothLe ?: Availability.Available

    override fun advertise(token: String, role: RadarRole): List<AdPart> =
        if (role == RadarRole.HIDER && RadarToken.isWellFormed(token)) {
            listOf(AdPart.ServiceUuid(RadarService.UUID), AdPart.LocalName(token))
        } else {
            emptyList()
        }

    override fun interests(): List<ScanInterest> = listOf(ScanInterest.Service(RadarService.UUID))

    /** The name, bare or after the first apps' prefix, when it is a token and the frame is the game's service's. */
    override fun decode(frame: AirFrame): List<Decoded> {
        val name = frame.name ?: return emptyList()
        if (!frame.lists(RadarService.UUID)) return emptyList()
        val token = name.removePrefix(RadarService.NAME_PREFIX)
        return if (RadarToken.isWellFormed(token)) listOf(Decoded(token, SightingVia.NAME)) else emptyList()
    }
}
