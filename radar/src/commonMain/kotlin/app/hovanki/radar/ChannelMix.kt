package app.hovanki.radar

/**
 * The channels a radio runs besides the game's own choice (docs/adr/0018-field-test-build.md §4 B, the field build's
 * journal): [game] are read into the game's sightings, as [RadarCatalog.game] is; [shadow] are advertised and heard
 * too, but what they read goes only to the [RadarTrace] (the frames the host hands it), never to the game. A channel
 * that should be heard but not sent is wrapped in [ListenOnly]. Built by [RadarCatalog.field].
 */
data class ChannelMix(val game: List<RadarChannel>, val shadow: List<RadarChannel> = emptyList()) {
    /** Every channel the host runs, the game's first: an advertisement's parts are kept in this order ([AdPlan]). */
    val all: List<RadarChannel> get() = game + shadow
}

/** [channel], heard and decoded as ever, but with nothing of it on this phone's air. */
class ListenOnly(private val channel: RadarChannel) : RadarChannel by channel {
    override fun advertise(token: String, role: RadarRole): List<AdPart> = emptyList()

    override fun toString(): String = "ListenOnly(${channel.id})"

    override fun equals(other: Any?): Boolean = other is ListenOnly && other.channel == channel

    override fun hashCode(): Int = channel.hashCode() * 31 + 1
}
