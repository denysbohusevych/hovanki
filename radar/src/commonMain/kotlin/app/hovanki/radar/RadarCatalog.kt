package app.hovanki.radar

import app.hovanki.radar.channel.ibeacon.IBeaconChannel
import app.hovanki.radar.channel.ibeacon.IBeaconRegionChannel
import app.hovanki.radar.channel.name.NameChannel
import app.hovanki.radar.channel.overflow.OverflowChannel
import app.hovanki.radar.channel.servicedata.ServiceDataChannel

/**
 * The radar's channels in this build (docs/adr/0017-radar-techniques-and-big-run.md, section 2.1), by id: the game
 * and the radio lab pick from here, nobody else knows a channel by name. A new channel is added here with the status
 * `LAB` (docs/radar-run.md, «Как добавить технику»).
 */
object RadarCatalog {
    val channels: List<RadarChannel> = listOf(
        ServiceDataChannel.ScanResponse,
        ServiceDataChannel.Bare,
        ServiceDataChannel.Mfr,
        NameChannel,
        IBeaconChannel,
        IBeaconRegionChannel,
        OverflowChannel,
    )

    /**
     * The game's (status `GAME`), the same on every platform: each phone advertises what its platform can send of them
     * (an Android hider the service data, an iPhone hider the name, a seeker the iBeacon) and hears them all; an
     * iPhone also monitors the seekers' region, as before this catalog.
     */
    val game: List<RadarChannel> = channels.filter { it.status == TechniqueStatus.GAME }

    fun byId(id: String): RadarChannel? = channels.firstOrNull { it.id == id }
}
