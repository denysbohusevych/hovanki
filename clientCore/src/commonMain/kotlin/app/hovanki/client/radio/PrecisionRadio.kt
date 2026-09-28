package app.hovanki.client.radio

import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.UwbPeer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The precision radar by UWB (docs/adr/0010-nearby-radar.md, section 3): metres and a direction to another phone of
 * the same kind, while both players look at their phones. The server pairs the phones and passes their discovery
 * tokens ([token], `MyState.uwbPeers`); the phones range on their own. iOS: Nearby Interaction; Android:
 * `androidx.core.uwb`. Neither is implemented yet ([NoopPrecisionRadio]): the server then pairs nobody.
 */
interface PrecisionRadio {
    val isSupported: Boolean

    /** This phone's discovery token while the radio is ready; null when it is not. */
    val token: StateFlow<String?>

    /** Ranges with [peers] while collected; a reading per peer every few hundred milliseconds. */
    fun range(peers: StateFlow<List<UwbPeer>>): Flow<PeerRange>
}

/** How far and in which direction (degrees clockwise from where the phone points; null: unknown) a peer is. */
data class PeerRange(
    val playerId: PlayerId,
    val distanceMeters: Double,
    val directionDegrees: Double? = null,
    val atMillis: Long,
)

class NoopPrecisionRadio : PrecisionRadio {
    override val isSupported: Boolean = false
    override val token: StateFlow<String?> = MutableStateFlow(null)

    override fun range(peers: StateFlow<List<UwbPeer>>): Flow<PeerRange> = emptyFlow()
}
