package app.hovanki.radar

import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.rules.RadarToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.transform

/**
 * The game's [ProximityRadio] on a platform's [AirHost] (docs/adr/0017-radar-techniques-and-big-run.md, section 2.2):
 * the host runs the game's channels ([channels] of the phone's platform, [RadarCatalog.game] by default) as a hider or
 * a seeker, and every frame one of them decodes into a well-formed token is a [RadioSighting] with the channel's id.
 * An overflow mask that reads as several tokens gives its first one (the game doesn't run that channel anyway).
 */
class HostProximityRadio(
    private val host: AirHost,
    private val channels: (Platform) -> List<RadarChannel> = { RadarCatalog.game },
    private val trace: RadarTrace = RadarTrace.None,
) : ProximityRadio {
    override val state: StateFlow<BluetoothState> = host.caps.mapState { it.bluetooth }

    override fun refresh() = host.refresh()

    override fun run(tokens: StateFlow<String?>, asSeeker: Boolean): Flow<RadioSighting> =
        runChannels(tokens, asSeeker, channels(host.caps.value.platform))

    /** The catalog's channels of [techniques] ([RadarCatalog.byId]); unknown ids skipped, none left: the game's. */
    override fun run(tokens: StateFlow<String?>, asSeeker: Boolean, techniques: Set<String>): Flow<RadioSighting> {
        val chosen = techniques.mapNotNull(RadarCatalog::byId)
        return if (chosen.isEmpty()) run(tokens, asSeeker) else runChannels(tokens, asSeeker, chosen)
    }

    /** [mix]'s channels on the host; sightings from its game's only, the shadow's read into the trace alone. */
    override fun run(tokens: StateFlow<String?>, asSeeker: Boolean, mix: ChannelMix): Flow<RadioSighting> =
        runChannels(tokens, asSeeker, mix.game, mix.all)

    private fun runChannels(
        tokens: StateFlow<String?>,
        asSeeker: Boolean,
        chosen: List<RadarChannel>,
        running: List<RadarChannel> = chosen,
    ): Flow<RadioSighting> {
        val role = if (asSeeker) RadarRole.SEEKER else RadarRole.HIDER
        return host.run(running, tokens, role, trace).transform { frame ->
            // One sighting a token: two of the lab's channels may read the same bytes (the service data's layouts).
            for ((tech, decoded) in chosen.decode(frame).distinctBy { it.second.token to it.second.via }) {
                if (!RadarToken.isWellFormed(decoded.token)) continue
                emit(RadioSighting(decoded.token, frame.rssi, frame.atMillis, frame.api, decoded.via, frame.peer, tech))
            }
        }
    }
}
