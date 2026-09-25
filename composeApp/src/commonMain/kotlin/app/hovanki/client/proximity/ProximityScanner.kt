package app.hovanki.client.proximity

import app.hovanki.shared.protocol.PlayerId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * BLE proximity of other players (Kable, after the MVP): lets a catch be confirmed automatically
 * when the seeker's phone sees the hider's signal strongly for a few seconds.
 */
interface ProximityScanner {
    fun readings(): Flow<ProximityReading>
}

/** [playerId] is null when the advertisement could not be matched to a player of this game. */
data class ProximityReading(val playerId: PlayerId?, val rssi: Int, val atMillis: Long)

/**
 * TODO(BLE, after MVP): real scanner, see docs/adr/0001-stack.md. A backgrounded iPhone advertises in a reduced
 * form that Android barely sees, so "iPhone hides, Android seeks" needs the GPS-distance fallback.
 */
class NoopProximityScanner : ProximityScanner {
    override fun readings(): Flow<ProximityReading> = emptyFlow()
}
