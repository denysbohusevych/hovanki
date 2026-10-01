package app.hovanki.shared.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The game's live channel (docs/adr/0015-websockets.md): the `sync` of [ApiRoutes.SYNC] over one WebSocket
// ([ApiRoutes.SOCKET]), plus a poke from the server when something happened. Text frames of [protocolJson], told
// apart by `type`; a side skips a type it doesn't know, so new frames can be added. The commands stay HTTP.

/** A frame the app sends. */
@Serializable
sealed interface ClientFrame {
    /** The same [SyncRequest] as `POST /sync`; the answer is a [ServerFrame.Snapshot] or [ServerFrame.Error] with [seq]. */
    @Serializable
    @SerialName("sync")
    data class Sync(val seq: Long, val request: SyncRequest) : ClientFrame
}

/** A frame the server sends. */
@Serializable
sealed interface ServerFrame {
    /** The answer to the [ClientFrame.Sync] with [seq]: the game as the player sees it, as `POST /sync` returns it. */
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val seq: Long, val snapshot: GameSnapshot) : ServerFrame

    /** Something happened that the player should see: sync now rather than after the usual pause. */
    @Serializable
    @SerialName("poke")
    data object Poke : ServerFrame

    /**
     * The [ClientFrame.Sync] with [seq] was refused, as `POST /sync` would refuse it with [error]; the socket stays open.
     * [retryAfterSeconds]: for [ErrorReason.TOO_MANY_REQUESTS].
     */
    @Serializable
    @SerialName("error")
    data class Error(val seq: Long, val error: ApiError, val retryAfterSeconds: Long? = null) : ServerFrame
}

/** Why the server closes a game's socket: the close codes of the application's range (4000–4999). */
object SocketClose {
    /** The token is not (or no longer) a player's of this game: the session is over, as a 401. */
    const val SESSION_REJECTED = 4401

    /** The game is over and gone, or never existed: as a 404. */
    const val GAME_NOT_FOUND = 4404

    /** Syncs too often, or too many sockets of one player: come back after a pause. */
    const val TOO_MANY = 4429

    /** The server has the live channel off ([ServerFeature.LIVE_SOCKET]): poll `POST /sync` instead. */
    const val POLL = 4503
}

/** The live channel's limits, the same on both sides. */
object SocketLimits {
    /** A frame the server takes, at most: a sync of 100 samples and 200 sightings is far below. */
    const val MAX_FRAME_BYTES = 256 * 1024

    /** A socket that is not open within this long (the upgrade hangs, e.g. in a server's shutdown) is given up on. */
    const val OPEN_TIMEOUT_MILLIS = 10_000L

    /** An answer that takes longer means a dead connection: the app opens a new one. */
    const val REPLY_TIMEOUT_MILLIS = 10_000L

    /** The server closes a socket that sent nothing for this long; the app syncs far more often. */
    const val IDLE_TIMEOUT_MILLIS = 60_000L

    /** Syncs within [SYNC_WINDOW_MILLIS] one socket may send; more and it is closed with [SocketClose.TOO_MANY]. */
    const val MAX_SYNCS_PER_WINDOW = 12
    const val SYNC_WINDOW_MILLIS = 5_000L

    /** Open sockets of one player; one more closes the oldest. */
    const val MAX_SOCKETS_PER_PLAYER = 3

    /** One poke per socket within this, at most: the pokes of a burst are sent as one. */
    const val POKE_GAP_MILLIS = 500L

    /** The app answers a poke with a sync no sooner than this after its previous one. */
    const val SYNC_AFTER_POKE_GAP_MILLIS = 300L
}
