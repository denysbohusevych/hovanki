package app.hovanki.server.live.v2

import app.hovanki.server.game.PlayerRef
import app.hovanki.shared.live.LiveJson
import app.hovanki.shared.live.LiveLimits
import app.hovanki.shared.live.LiveServerStates
import app.hovanki.shared.live.v2.CloseReason
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.DeviceReport
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

/**
 * Where a live session's [app.hovanki.shared.live.v2.Down]s go now (docs/live-v2/spec.md §5–6): its WebSocket v2 or
 * the phone's UDP address. Thread safe; a send never waits long for a slow phone.
 */
interface LiveLink {
    /** [LiveMetrics.WS2] or [LiveMetrics.UDP]: the meters' tag, and the sync's transport. */
    val transport: String

    /**
     * Not null: the link is a UDP address the phone has not confirmed yet (spec §5.6). The answer to an Up says only
     * this challenge and the acknowledgement, no state, until the phone echoes it.
     */
    val pathChallenge: ByteArray? get() = null

    /** Sends one encoded [Down]; false: it could not (the link is closing, or the Down may not go there). */
    fun send(down: ByteArray): Boolean

    /**
     * Closes the link. [reason]: the session is over, the phone acts on it (spec §13); null: only this link goes, the
     * phone keeps its session ([goingAway]: the server is stopping).
     */
    fun close(reason: CloseReason?, goingAway: Boolean = false)
}

/**
 * One live session of a player (docs/live-v2/spec.md §4, server.md §3.4): the keys, the states sent, the last Up and
 * the device report, the link. Its own [locked] guards the exchange: an Up's Down and a push are built and sent one at
 * a time, so the phone gets its states in order. The lock order is the session's, then the game's: a push and an Up
 * both take them so, and nothing takes a session's lock under a game's. Nothing of it is ever logged.
 */
class LiveSession(
    /** 8 random bytes: the [app.hovanki.shared.protocol.LiveOpenResponse.sessionId] and every datagram's header. */
    val id: Long,
    /** The order of the opens, for the oldest of a player's sessions. */
    val serial: Long,
    val ref: PlayerRef,
    /** The game token it was opened with: checked again on every Up. */
    val token: String,
    upKey: ByteArray,
    downKey: ByteArray,
    val openedAtMillis: Long,
    val expiresAtMillis: Long,
    /** The JSON of the sections: its game's kinds shared with the game's other sessions ([LiveSessions.open]). */
    val json: LiveJson = LiveJson(),
) {
    /** The AES-128-GCM keys of the datagrams (spec §5.2); handed over once, at the open. */
    val upKey = SecretKeySpec(upKey, "AES")
    val downKey = SecretKeySpec(downKey, "AES")

    private val lock = Any()

    // Guarded by the lock.
    val states = LiveServerStates()

    /** The highest Up.seq processed: the next Down's `ack_up`. */
    var lastSeq = 0

    /** The last report the phone sent (spec §7.1): applied with every Up, as v1 applies `SyncRequest.device`. */
    var device: DeviceReport? = null
    var closed = false
    private val upTimes = LongArray(LiveLimits.MAX_UPS_PER_WINDOW)
    private var upNext = 0

    /** Where pushes go; null between links (a WebSocket dropped, the phone not back yet). */
    @Volatile
    var link: LiveLink? = null

    /** The transport of the last link, for the meter of the sessions that move between UDP and the WebSocket. */
    var lastTransport: String? = null

    /** The session's UDP, once a datagram of it came ([LiveUdpServer]); created under [locked]. */
    @Volatile
    var udp: LiveUdpServer.LiveUdpPath? = null

    /** The server time of the last Up: a session silent for [LiveLimits.SESSION_IDLE_MILLIS] is forgotten. */
    @Volatile
    var lastUpAtMillis: Long = openedAtMillis

    inline fun <T> locked(block: () -> T): T = synchronized(lockObject(), block)

    @PublishedApi
    internal fun lockObject(): Any = lock

    /**
     * One more Up now ([nowNanos]): 0 when it is within [LiveLimits.MAX_UPS_PER_WINDOW] a window, else the
     * milliseconds until one is. Under [locked].
     */
    fun countUp(nowNanos: Long): Long {
        val oldest = upTimes[upNext]
        val since = nowNanos - oldest
        if (oldest != 0L && since < UP_WINDOW_NANOS) return TimeUnit.NANOSECONDS.toMillis(UP_WINDOW_NANOS - since) + 1
        upTimes[upNext] = nowNanos.takeIf { it != 0L } ?: 1L
        upNext = (upNext + 1) % upTimes.size
        return 0
    }

    // The pushes waiting for their gap (LivePushes), under their own small lock: taken by whoever pokes, never with
    // the session's or a game's lock held inside it.
    private val pushLock = Any()
    private var pushScheduled = false
    private var lastPushNanos = 0L
    private var statePending = false
    private var chatPending: ArrayList<ChatMessage>? = null
    private var chatOverflow = false

    /** What a push is to bring: the state, the chat ([more]: some of it was dropped, the phone asks itself). */
    class Pending(val state: Boolean, val chat: List<ChatMessage>, val more: Boolean)

    /**
     * Adds to the next push; the nanoseconds until it may go, or null when it is already scheduled. At most
     * [MAX_PENDING_CHAT] messages wait: the rest the phone takes by its cursor with its next Up.
     */
    fun addPush(state: Boolean, chat: List<ChatMessage>, nowNanos: Long): Long? = synchronized(pushLock) {
        if (state) statePending = true
        if (chat.isNotEmpty()) {
            val pending = chatPending ?: ArrayList<ChatMessage>().also { chatPending = it }
            pending += chat
            if (pending.size > MAX_PENDING_CHAT) {
                pending.subList(0, pending.size - MAX_PENDING_CHAT).clear()
                chatOverflow = true
            }
        }
        if (pushScheduled) return null
        pushScheduled = true
        if (lastPushNanos == 0L) 0L else lastPushNanos + PUSH_GAP_NANOS - nowNanos
    }

    /** What waited, taken for the push that goes now. */
    fun takePush(nowNanos: Long): Pending = synchronized(pushLock) {
        val pending = Pending(statePending, chatPending?.sortedBy { it.seq }.orEmpty(), chatOverflow)
        pushScheduled = false
        lastPushNanos = nowNanos.takeIf { it != 0L } ?: 1L
        statePending = false
        chatPending = null
        chatOverflow = false
        pending
    }

    companion object {
        const val MAX_PENDING_CHAT = 100
        private val UP_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(LiveLimits.UP_WINDOW_MILLIS)
        private val PUSH_GAP_NANOS = TimeUnit.MILLISECONDS.toNanos(LiveLimits.PUSH_GAP_MILLIS)
    }
}
