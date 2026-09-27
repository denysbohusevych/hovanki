package app.hovanki.client.session

import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.TracksResponse
import app.hovanki.shared.rules.ChatRules

/** Everything the UI needs to know about the current game; the screen shown is derived from it. */
data class SessionState(
    /** Null: not in a game (start screen). */
    val session: PlayerSession? = null,
    /** Latest authoritative state; null only while a session starts or [isResuming]. */
    val snapshot: GameSnapshot? = null,
    /** A session saved by an earlier run of the app is being checked with the server; see `resumeSavedGame`. */
    val isResuming: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.ONLINE,
    /** Own location updates are running; false when the permission is missing or location failed. */
    val isSharingLocation: Boolean = false,
    /** Result of the last failed command or of a lost session, until the next command succeeds or it is dismissed. */
    val lastError: SessionError? = null,
    /** The buildings where hiding is not allowed, once loaded; the map draws exactly these. */
    val buildings: BuildingsResponse? = null,
    /**
     * This game's chat as far as the player may see it, oldest first: merged by seq from every snapshot (polls and
     * command responses), at most [ChatRules.HISTORY_SIZE]. Show it with `chatLines`; [snapshot]'s own `chat` is only
     * the part that came with it.
     */
    val chat: List<ChatMessage> = emptyList(),
    /** Every player's track of the round, loaded once the game is over: the replay on the results screen. */
    val tracks: TracksResponse? = null,
    /** The newest seq the player has read (`GameSessionManager.markChatRead`); 0: none. See `unreadChatCount`. */
    val chatReadSeq: Long = 0,
)

enum class ConnectionStatus { ONLINE, RECONNECTING }

sealed interface SessionError {
    /**
     * The server refused the command; [message] is the server's explanation, [reason] the exact cause when it sent
     * one, [retryAfterSeconds] how long to wait after a rate limit ([ErrorReason.TOO_MANY_REQUESTS]).
     */
    data class Rejected(
        val code: ErrorCode?,
        val message: String,
        val reason: ErrorReason? = null,
        val retryAfterSeconds: Long? = null,
    ) : SessionError

    /** The server could not be reached or answered with something unreadable. */
    data class Network(val details: String?) : SessionError

    /** The server no longer knows this game (expired or server restarted); the session was closed. */
    data object SessionLost : SessionError

    /** The game saved by an earlier run of the app has ended meanwhile; nothing to return to. */
    data object SavedGameFinished : SessionError

    /** The game saved by an earlier run of the app is gone (deleted on the server, token refused). */
    data object SavedGameGone : SessionError
}
