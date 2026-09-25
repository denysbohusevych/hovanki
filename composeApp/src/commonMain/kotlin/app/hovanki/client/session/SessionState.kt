package app.hovanki.client.session

import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerSession

/** Everything the UI needs to know about the current game; the screen shown is derived from it. */
data class SessionState(
    /** Null: not in a game (start screen). */
    val session: PlayerSession? = null,
    /** Latest authoritative state; null only for a moment while a session starts. */
    val snapshot: GameSnapshot? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.ONLINE,
    /** Own location updates are running; false when the permission is missing or location failed. */
    val isSharingLocation: Boolean = false,
    /** Result of the last failed command or of a lost session, until the next command succeeds or it is dismissed. */
    val lastError: SessionError? = null,
)

enum class ConnectionStatus { ONLINE, RECONNECTING }

sealed interface SessionError {
    /** The server refused the command; [message] is the server's explanation. */
    data class Rejected(val code: ErrorCode?, val message: String) : SessionError

    /** The server could not be reached or answered with something unreadable. */
    data class Network(val details: String?) : SessionError

    /** The server no longer knows this game (expired or server restarted); the session was closed. */
    data object SessionLost : SessionError
}
