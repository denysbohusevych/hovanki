package app.hovanki.radar

import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.UwbPeer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The precision radar by UWB (docs/adr/0012-nearby-radar.md, section 3): metres and a direction to another phone of
 * the same kind, while both players look at their phones. The server pairs the phones and passes their discovery
 * tokens ([token], `MyState.uwbPeers`); the phones range on their own. iOS: Nearby Interaction, so far only in the
 * radio lab (`uwb.ni`, [UwbTechnique]: `app.hovanki.radar.uwb.IosPrecisionRadio`, the tokens swapped through the lab's
 * run on the server); Android: `androidx.core.uwb`, not yet. The game keeps [NoopPrecisionRadio]: the server then
 * pairs nobody.
 */
interface PrecisionRadio {
    val isSupported: Boolean

    /**
     * This phone's discovery token while the radio is ready ([prepare]); null when it is not. It changes when the
     * radio has to start over (iOS: a new session after the old one was invalidated): whoever passes it on to the
     * peers collects it and passes on every new one.
     */
    val token: StateFlow<String?>

    /** Makes the radio ready, so that [token] appears (iOS: the session exists); [range] does it too. */
    fun prepare() = Unit

    /**
     * Ranges with [peers] while collected, one collector at a time; a reading per peer every few hundred
     * milliseconds.
     */
    fun range(peers: StateFlow<List<UwbPeer>>): Flow<PeerRange>
}

/** How far and in which direction (degrees clockwise from where the phone points; null: unknown) a peer is. */
data class PeerRange(
    val playerId: PlayerId,
    val distanceMeters: Double,
    val directionDegrees: Double? = null,
    val atMillis: Long,
)

/**
 * Every step of a ranging session, for the debug build's radio lab (docs/radio-lab.md §4.1, the `range` kind):
 * [None] unless the lab listens; it never changes what the radio does. [peer]: whom the step concerns (the run's label
 * in the lab), null for the radio as a whole.
 *
 * [action]: `session_start` (a session for [peer]), `config` (its configuration run; [error]: the peer's token did not
 * unarchive), `running` (the first reading of [peer]), `suspended` (iOS paused the session: the app left the screen,
 * another app took the chip), `suspension_ended`, `removed` ([error]: the reason, `timeout`, `peer_ended` or
 * `unknown`), `invalidated` ([error]: the OS's words), `rerun` (the session again after an invalidation it may recover
 * from), `stop`.
 */
interface RangeTrace {
    fun range(action: String, peer: String? = null, error: String? = null) = Unit

    companion object {
        val None: RangeTrace = object : RangeTrace {}
    }
}

/**
 * `uwb.ni` (ADR 0017 §2.3): Nearby Interaction between two iPhones with the U1/U2 chip (iPhone 11 and later, not the
 * SE), metres and a direction while both run a session with the other's discovery token; only in the lab for now (the
 * game keeps [NoopPrecisionRadio]). Available on every iPhone as far as the caps can tell: [RadarCaps] knows no chip,
 * so whether this one has it is the radio's [PrecisionRadio.isSupported].
 */
object UwbTechnique : Technique {
    override val id: String = "uwb.ni"
    override val kind: TechniqueKind = TechniqueKind.RANGING
    override val status: TechniqueStatus = TechniqueStatus.LAB

    override fun available(caps: RadarCaps): Availability = if (caps.platform == Platform.IOS) {
        Availability.Available
    } else {
        Availability.Unavailable("Nearby Interaction is iOS only (Android: uwb.jetpack)")
    }
}

class NoopPrecisionRadio : PrecisionRadio {
    override val isSupported: Boolean = false
    override val token: StateFlow<String?> = MutableStateFlow(null)

    override fun range(peers: StateFlow<List<UwbPeer>>): Flow<PeerRange> = emptyFlow()
}
