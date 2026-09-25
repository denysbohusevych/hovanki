package app.hovanki.client.network

import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerSession
import kotlinx.coroutines.flow.Flow

/**
 * Real-time channel to the game server: uploads our location samples and delivers fresh snapshots.
 *
 * The transport is hidden on purpose. The MVP polls over HTTP ([PollingGameConnection]): positions go out every few
 * seconds anyway, and polling survives flaky mobile networks and iOS background limits better than a socket.
 * When instant features appear (chat, live tracking) this can be swapped for WebSocket or SSE without
 * touching the rest of the app.
 */
interface GameConnection {
    /**
     * Cold flow: collecting it opens the channel, cancelling the collection closes it.
     * Pending samples are taken from [outbox]; the flow retries on its own and completes only after [ConnectionEvent.Ended].
     */
    fun connect(session: PlayerSession, outbox: LocationOutbox): Flow<ConnectionEvent>
}

sealed interface ConnectionEvent {
    data class Snapshot(val snapshot: GameSnapshot) : ConnectionEvent

    /** A transient failure; the connection retries by itself after [retryInMillis]. */
    data class Problem(val error: Throwable, val retryInMillis: Long) : ConnectionEvent

    /** The server no longer knows this game or session (expired, server restarted). Nothing more will come. */
    data class Ended(val reason: EndReason) : ConnectionEvent
}

enum class EndReason {
    /** 401: the session token is not valid anymore. */
    SESSION_REJECTED,

    /** 404: the game is over and was cleaned up, or never existed on this server. */
    GAME_NOT_FOUND,
}
