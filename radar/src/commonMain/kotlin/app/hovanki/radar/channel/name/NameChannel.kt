package app.hovanki.radar.channel.name

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
 * An iPhone hider on the screen (`ble.name`, docs/adr/0017-radar-techniques-and-big-run.md, section 2.3): iOS lets an
 * app advertise only a name and service UUIDs, so the token is the name, next to the game's service (iOS keeps 8
 * characters of the name beside a 128-bit UUID: exactly the token). In the background iOS drops the name: a locked
 * iPhone is not heard this way. The Mac advertises the same in either role. Read only next to the game's service, so
 * nobody else's name passes for a token.
 */
class NameChannel : RadarChannel {
    override val id: String = TECH
    override val use: ChannelUse = ChannelUse.GAME

    override fun adPart(token: String, role: AirRole, platform: AirPlatform): AdPart? {
        val advertises = platform == AirPlatform.MAC || (platform == AirPlatform.IOS && role == AirRole.HIDER)
        if (!advertises || !RadarToken.isWellFormed(token)) return null
        return AdPart(id, main = AdData(serviceUuids = listOf(GameAir.SERVICE_UUID), localName = token))
    }

    override fun interests(platform: AirPlatform): List<ScanInterest> =
        listOf(ScanInterest.ServiceUuid(GameAir.SERVICE_UUID))

    override fun decode(frame: HeardFrame): ChannelReading? {
        val name = frame.localName ?: return null
        if (frame.serviceUuids.none { BleUuid.canonical(it) == GameAir.SERVICE_UUID }) return null
        val token = name.removePrefix(GameAir.NAME_PREFIX)
        if (!RadarToken.isWellFormed(token)) return null
        return ChannelReading(id, listOf(token), SightingVia.NAME, use)
    }

    companion object {
        const val TECH = "ble.name"
    }
}
