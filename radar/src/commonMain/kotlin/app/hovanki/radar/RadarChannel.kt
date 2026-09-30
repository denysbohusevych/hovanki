package app.hovanki.radar

/**
 * A channel (docs/adr/0017-radar-techniques-and-big-run.md, section 2.2): one format of the token on the air. What to
 * put into the advertisement, what to listen for, how to read a frame back into a token. Pure Kotlin: a channel
 * knows no platform; the host ([AirHost]) sends its parts and hands it every frame it hears. One package per channel
 * (`app.hovanki.radar.channel.<x>`), which imports nothing of this module but the root package.
 */
interface RadarChannel : Technique {
    override val kind: TechniqueKind get() = TechniqueKind.CHANNEL

    /** What to advertise for [token] in [role]; empty: this channel sends nothing in that role. */
    fun advertise(token: String, role: RadarRole): List<AdPart>

    /** What this channel wants heard, in either role. */
    fun interests(): List<ScanInterest>

    /** The tokens [frame] carries by this channel, with how ([SightingVia]). Empty: not this channel's frame. */
    fun decode(frame: AirFrame): List<Decoded>
}

enum class RadarRole { HIDER, SEEKER }

/**
 * A token read from a frame: [token] the channel's best reading, [candidates] every token the frame may carry (a
 * damaged overflow mask reads as 2 or 4), [token] first.
 */
data class Decoded(val token: String, val via: SightingVia, val candidates: List<String> = listOf(token))

/** [frame] decoded by every channel: pairs of the channel's id and what it read. */
fun List<RadarChannel>.decode(frame: AirFrame): List<Pair<String, Decoded>> =
    flatMap { channel -> channel.decode(frame).map { channel.id to it } }
