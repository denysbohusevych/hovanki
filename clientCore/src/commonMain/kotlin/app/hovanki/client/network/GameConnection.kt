package app.hovanki.client.network

import app.hovanki.shared.protocol.DeviceReport
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.NearbySighting
import app.hovanki.shared.protocol.PlayerSession
import kotlinx.coroutines.flow.Flow

/**
 * Real-time channel to the game server: uploads our location samples and delivers fresh snapshots.
 *
 * The transport is hidden on purpose: polling over HTTP ([PollingGameConnection]), which survives flaky mobile networks
 * and iOS background limits, or the live channel over a WebSocket ([WebSocketGameConnection],
 * docs/adr/0015-websockets.md), whose pokes bring what happens at once. The app uses both ([AdaptiveGameConnection]):
 * the socket while the server has it on and it works, polling otherwise. Chat comes with the syncs either way.
 */
interface GameConnection {
    /**
     * Cold flow: collecting it opens the channel, cancelling the collection closes it.
     * Pending samples are taken from [outbox]; the flow retries on its own and completes only after [ConnectionEvent.Ended].
     * [chatAfter] is asked before every request for the chat cursor (`SyncRequest.chatAfter`: the newest seq the
     * client has, 0 for none): the snapshots bring the newer messages. Null: no chat. [extras] is asked before every
     * request too: whom the phone heard over Bluetooth since the last one and what it says about itself.
     * [intervalMillis] is asked after every snapshot for the pause before the next request: the game's
     * `syncIntervalSeconds`, or less while the radar says somebody is near.
     */
    fun connect(
        session: PlayerSession,
        outbox: LocationOutbox,
        chatAfter: () -> Long? = { null },
        extras: () -> SyncExtras = { SyncExtras() },
        intervalMillis: (GameSnapshot) -> Long = ::defaultSyncIntervalMillis,
    ): Flow<ConnectionEvent>
}

/** The game's own pace: [app.hovanki.shared.protocol.GameRules.syncIntervalSeconds], a second at least. */
fun defaultSyncIntervalMillis(snapshot: GameSnapshot): Long =
    snapshot.settings.rules.syncIntervalSeconds.coerceAtLeast(1) * 1000L

/** What goes with a sync besides the samples (docs/adr/0012-nearby-radar.md): the radar's sightings, the phone. */
data class SyncExtras(val nearby: List<NearbySighting> = emptyList(), val device: DeviceReport? = null)

sealed interface ConnectionEvent {
    /**
     * A fresh snapshot, and by which [transport] it came; [bytes]: the answer's size on the wire as the connection
     * read it (null where it did not measure). The phone's own, for the field log: not in the protocol.
     */
    data class Snapshot(
        val snapshot: GameSnapshot,
        val transport: Transport = Transport.POLLING,
        val bytes: Int? = null,
    ) : ConnectionEvent

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

/** How the syncs go: `POST /sync` every few seconds, or frames over the live channel's socket. */
enum class Transport { POLLING, SOCKET }
