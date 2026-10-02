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

    /**
     * The service data's three layouts in the order an Android hider gets them by its player's number in a field game
     * ([field]), the game's first.
     */
    val hiderLayouts: List<RadarChannel> =
        listOf(ServiceDataChannel.ScanResponse, ServiceDataChannel.Bare, ServiceDataChannel.Mfr)

    /** What a field game's journal puts in the shadow ([field]): a locked iPhone's overflow mask. */
    val fieldShadow: List<RadarChannel> = listOf(OverflowChannel)

    /**
     * A game's channels while the field build's journal is written (docs/adr/0018-field-test-build.md §4 B): an
     * Android hider sends the service data's layout of its player's number round [hiderLayouts] (the report shows
     * which one every model hears), and every phone hears all three as the game's, so a hider on `.bare` or `.mfr`
     * stays on the radar of whoever can hear it; the overflow mask ([fieldShadow]) goes on the air and is read into
     * the journal only. The other game channels as [game]. Without a journal the game runs [game].
     */
    fun field(playerNumber: Int): ChannelMix {
        val sent = hiderLayouts[playerNumber.mod(hiderLayouts.size)]
        val layouts = listOf(sent) + hiderLayouts.filter { it != sent }.map(::ListenOnly)
        return ChannelMix(game = layouts + game.filter { it !in hiderLayouts }, shadow = fieldShadow)
    }
}
