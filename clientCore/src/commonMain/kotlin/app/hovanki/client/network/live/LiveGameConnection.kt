package app.hovanki.client.network.live

import app.hovanki.client.network.ApiException
import app.hovanki.client.network.ConnectionEvent
import app.hovanki.client.network.EndReason
import app.hovanki.client.network.GameConnection
import app.hovanki.client.network.LocationOutbox
import app.hovanki.client.network.PollingGameConnection
import app.hovanki.client.network.SyncExtras
import app.hovanki.client.network.Transport
import app.hovanki.shared.live.LiveClientStates
import app.hovanki.shared.live.LiveDownlink
import app.hovanki.shared.live.LiveLimits
import app.hovanki.shared.live.LiveSnapshots
import app.hovanki.shared.live.LiveUplink
import app.hovanki.shared.live.v2.CloseReason
import app.hovanki.shared.live.v2.Down
import app.hovanki.shared.live.v2.Up
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.DeviceReport
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.LiveOpenRequest
import app.hovanki.shared.protocol.LiveOpenResponse
import app.hovanki.shared.protocol.LiveTransports
import app.hovanki.shared.protocol.LiveWs
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.SocketClose
import app.hovanki.shared.protocol.SocketLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The live channel v2 over its WebSocket (docs/live-v2/spec.md, client.md §3): a live session opened over HTTPS
 * ([LiveApi]), then one `Up` at the game's pace answered with a `Down` that carries only what changed since the state
 * this phone acknowledged, and the server's pushes in between. The phone puts the same [GameSnapshot] v1 gives together
 * from the states ([LiveClientStates], [LiveSnapshots]), so the screens see no difference.
 *
 * - **Fixes** stay this connection's until a Down acknowledges the Up that carried them (`ack_up`), and go in every Up
 *   until then, the newest [LiveLimits.MAX_FIXES_PER_UP]; when the connection ends they go back to the [LocationOutbox].
 * - **The phone's report** goes when it differs from the last one the server acknowledged; radar sightings once.
 * - **A broken WebSocket** is opened again on the same session (its states kept) with polling's backoff; the server's
 *   close codes end the game's session (4401, 4404), ask for a new live session (4410, 4429) or for v1 (4503). The
 *   session is renewed [LiveLimits.REOPEN_BEFORE_MILLIS] before it expires, by the phone's monotonic clock.
 *
 * Every [ConnectionEvent.Problem] of the channel itself carries a [LiveChannelException], which
 * [LiveFirstGameConnection] reads to fall back to v1. [serverTime] is `ServerClock.now()`: the Up's `time_ms`.
 */
class LiveGameConnection(
    private val api: LiveApi,
    private val opener: LiveSocketOpener,
    private val serverTime: () -> Long,
    private val appVersion: String = "",
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : GameConnection {
    override fun connect(
        session: PlayerSession,
        outbox: LocationOutbox,
        chatAfter: () -> Long?,
        extras: () -> SyncExtras,
        intervalMillis: (GameSnapshot) -> Long,
    ): Flow<ConnectionEvent> = flow {
        val conn = Connection(session, outbox, chatAfter, extras, intervalMillis)
        try {
            while (true) {
                val live = when (val opened = open(conn)) {
                    is Opened.Session -> opened.live

                    is Opened.Over -> {
                        emit(ConnectionEvent.Ended(opened.reason))
                        return@flow
                    }

                    Opened.Fallback -> {
                        fallBack()
                        continue
                    }

                    is Opened.Failed -> {
                        val wait = opened.retryInMillis ?: conn.nextBackoff()
                        emit(ConnectionEvent.Problem(opened.error, wait))
                        delay(wait)
                        continue
                    }
                }
                val run = Run(live, timeSource.markNow())
                conn.fixes.newSession()
                links@ while (true) {
                    when (val outcome = runLink(conn, run)) {
                        is Outcome.Ended -> {
                            emit(ConnectionEvent.Ended(outcome.reason))
                            return@flow
                        }

                        Outcome.Reopen -> break@links

                        Outcome.Fallback -> {
                            fallBack()
                            break@links
                        }

                        is Outcome.Broken -> {
                            val wait = conn.nextBackoff()
                            emit(ConnectionEvent.Problem(outcome.error, wait))
                            delay(wait)
                            if (outcome.reopen) break@links
                        }
                    }
                }
            }
        } finally {
            // Whatever the server has not acknowledged goes with the next connection, v1's or this one's.
            outbox.requeue(conn.fixes.samples)
            conn.fixes.clear()
        }
    }

    /** The server has v2 off: said once, and the next try after [LiveLimits.V1_AFTER_DISABLED_MILLIS]. */
    private suspend fun FlowCollector<ConnectionEvent>.fallBack() {
        val error = LiveChannelException("The server has the live channel v2 off", fallbackToV1 = true)
        emit(ConnectionEvent.Problem(error, LiveLimits.V1_AFTER_DISABLED_MILLIS))
        delay(LiveLimits.V1_AFTER_DISABLED_MILLIS)
    }

    private suspend fun open(conn: Connection): Opened = try {
        Opened.Session(api.open(conn.session, LiveOpenRequest(appVersion, listOf(LiveTransports.WS))))
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        when {
            e.status == HTTP_UNAUTHORIZED -> Opened.Over(EndReason.SESSION_REJECTED)

            e.status == HTTP_NOT_FOUND && e.reason == ErrorReason.FEATURE_DISABLED -> Opened.Fallback

            e.status == HTTP_NOT_FOUND -> Opened.Over(EndReason.GAME_NOT_FOUND)

            e.status == HTTP_TOO_MANY -> Opened.Failed(e, e.retryAfterSeconds?.times(MILLIS_PER_SECOND))

            else -> Opened.Failed(
                LiveChannelException(
                    "The live session did not open: HTTP ${e.status}",
                    answered = conn.answered,
                    cause = e,
                ),
                retryInMillis = null,
            )
        }
    } catch (e: Exception) {
        Opened.Failed(
            LiveChannelException("The live session did not open: ${e.message}", answered = conn.answered, cause = e),
            retryInMillis = null,
        )
    }

    /** One WebSocket of [run]'s session, from opening to its end. */
    private suspend fun FlowCollector<ConnectionEvent>.runLink(conn: Connection, run: Run): Outcome {
        // Ktor's HttpTimeout does not cover the upgrade, so a hanging one is cut here.
        val socket = try {
            withTimeoutOrNull(SocketLimits.OPEN_TIMEOUT_MILLIS) { opener.open(conn.session, run.live) }
                ?: return Outcome.Broken(
                    LiveChannelException(
                        "The WebSocket v2 did not open within ${SocketLimits.OPEN_TIMEOUT_MILLIS} ms",
                        answered = conn.answered,
                    ),
                )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Broken(
                LiveChannelException(
                    "The WebSocket v2 did not open: ${e.message}",
                    answered = conn.answered,
                    cause = e,
                ),
            )
        }
        try {
            var errorPauseMillis = PollingGameConnection.MIN_BACKOFF_MILLIS
            while (true) {
                if (run.expiresSoon()) {
                    socket.close()
                    return Outcome.Reopen
                }
                val up = buildUp(conn, run)
                // A socket the server closed can hold a send up for good, without a close to read.
                withTimeoutOrNull(SocketLimits.REPLY_TIMEOUT_MILLIS) { socket.send(Up.ADAPTER.encode(up)) }
                    ?: throw LiveChannelException(
                        "The Up was not sent within ${SocketLimits.REPLY_TIMEOUT_MILLIS} ms",
                        answered = conn.answered,
                    )
                var reply: Handled? = null
                withTimeoutOrNull(SocketLimits.REPLY_TIMEOUT_MILLIS) {
                    while (reply == null) {
                        val (down, bytes) = nextDown(socket)
                        val handled = handle(conn, run, down, bytes)
                        if (down.reply_to == up.seq) reply = handled
                    }
                }
                    ?: throw LiveChannelException(
                        "No answer within ${SocketLimits.REPLY_TIMEOUT_MILLIS} ms",
                        answered = conn.answered,
                    )
                val pauseMillis = when (val answer = reply) {
                    is Handled.Refused -> {
                        val pause = answer.retryAfterMillis ?: errorPauseMillis
                        errorPauseMillis =
                            (errorPauseMillis * 2).coerceAtMost(PollingGameConnection.MAX_BACKOFF_MILLIS)
                        emit(ConnectionEvent.Problem(answer.error, pause))
                        pause
                    }

                    else -> {
                        errorPauseMillis = PollingGameConnection.MIN_BACKOFF_MILLIS
                        val more = answer is Handled.State && answer.chatMore
                        if (more || run.states.needFull) 0 else pauseAfter(conn.last, conn.intervalMillis)
                    }
                }
                // The pause; the pushes come meanwhile. A push that could not be applied, or more chat waiting, cuts
                // it short.
                withTimeoutOrNull(pauseMillis) {
                    while (true) {
                        val (down, bytes) = nextDown(socket)
                        val handled = handle(conn, run, down, bytes)
                        if (handled == Handled.Dropped || (handled is Handled.State && handled.chatMore)) break
                    }
                }
            }
        } catch (e: CancellationException) {
            // Ktor's send into a session the server closed throws the session's CancellationException into a
            // coroutine nobody cancelled: the server's close, not ours (as v1's WebSocketGameConnection).
            if (currentCoroutineContext().isActive) return closed(socket, conn)
            socket.close()
            throw e
        } catch (e: Closing) {
            socket.close()
            return e.outcome
        } catch (e: LinkClosed) {
            return closed(socket, conn)
        } catch (e: LiveChannelException) {
            socket.close()
            return Outcome.Broken(e)
        } catch (e: Exception) {
            socket.close()
            return Outcome.Broken(
                LiveChannelException("The WebSocket v2 failed: ${e.message}", answered = conn.answered, cause = e),
            )
        }
    }

    /** The next Up of [run] (spec §7.1). */
    private fun buildUp(conn: Connection, run: Run): Up {
        val seq = ++run.seq
        val samples = conn.fixes.take(conn.outbox, seq)
        // Sightings are not sent again after a failure: by then they are stale.
        val (nearby, device) = conn.extras()
        val report = device?.takeIf { it != run.deviceAcked }
        if (report != null) {
            run.deviceSent = report
            run.deviceSentSeq = seq
        }
        return LiveUplink.up(
            seq = seq,
            ackState = run.states.ackState,
            timeMillis = serverTime(),
            samples = samples,
            nearby = nearby,
            device = report,
            chatAfter = conn.chatAfter(),
            full = run.states.needFull,
        )
    }

    /** One Down, in the order they came (spec §8.6): what it says goes out as events. */
    private suspend fun FlowCollector<ConnectionEvent>.handle(
        conn: Connection,
        run: Run,
        down: Down,
        bytes: Int,
    ): Handled {
        conn.answered = true
        conn.resetBackoff()
        down.close?.let { throw Closing(outcomeOf(it.reason, conn)) }
        if (down.ack_up > 0) {
            conn.fixes.acknowledge(down.ack_up)
            run.acknowledgeDevice(down.ack_up)
        }
        down.error?.let { error ->
            val apiError = LiveDownlink.apiError(error)
            val retryAfter = error.retry_after_s.takeIf { it > 0 }?.toLong()
            return Handled.Refused(
                ApiException(apiError.httpStatus(), apiError, retryAfter),
                retryAfter?.times(MILLIS_PER_SECOND),
            )
        }
        val applied = run.states.apply(down.state_id, down.base_state_id, down.state)
        val chat = down.chat.map(LiveDownlink::chatMessage)
        if (down.chat_push) {
            // The chat as it was sent: no snapshot, no cursor moved (spec §11).
            if (chat.isNotEmpty()) emit(ConnectionEvent.Chat(chat))
            if (down.state_id == 0) return Handled.Chat
        }
        if (!applied) return Handled.Dropped
        val shown = run.states.shown ?: return Handled.Dropped
        if (down.server_time_ms >= conn.lastEmittedMillis) {
            val snapshot = run.snapshots.toGameSnapshot(
                shown,
                down.server_time_ms,
                if (down.chat_push) emptyList() else chat,
            )
            conn.lastEmittedMillis = down.server_time_ms
            conn.last = snapshot
            emit(ConnectionEvent.Snapshot(snapshot, Transport.LIVE_WS, bytes))
        }
        return Handled.State(chatMore = down.chat_more)
    }

    /** The next Down of [socket]; a frame that is no Down (a newer server's, or broken) is skipped. */
    private suspend fun nextDown(socket: LiveSocket): Pair<Down, Int> {
        while (true) {
            val bytes = socket.receive() ?: throw LinkClosed()
            val down = try {
                Down.ADAPTER.decode(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (down != null) return down to bytes.size
        }
    }

    /** The socket is closed: the server's close code says what next (spec §6). */
    private suspend fun closed(socket: LiveSocket, conn: Connection): Outcome = when (val code = socket.closeCode()) {
        SocketClose.SESSION_REJECTED -> Outcome.Ended(EndReason.SESSION_REJECTED)

        SocketClose.GAME_NOT_FOUND -> Outcome.Ended(EndReason.GAME_NOT_FOUND)

        LiveWs.CLOSE_REOPEN -> Outcome.Reopen

        SocketClose.POLL -> Outcome.Fallback

        SocketClose.TOO_MANY -> Outcome.Broken(
            LiveChannelException("The WebSocket v2 closed: too many", code, conn.answered),
            reopen = true,
        )

        else -> Outcome.Broken(
            LiveChannelException("The WebSocket v2 closed (${code ?: "no code"})", code, conn.answered),
        )
    }

    /** A Down's `close` (spec §13), as UDP will send it; the WebSocket v2 closes with a code instead. */
    private fun outcomeOf(reason: CloseReason, conn: Connection): Outcome = when (reason) {
        CloseReason.CLOSE_SESSION_REJECTED -> Outcome.Ended(EndReason.SESSION_REJECTED)

        CloseReason.CLOSE_GAME_NOT_FOUND -> Outcome.Ended(EndReason.GAME_NOT_FOUND)

        CloseReason.CLOSE_FALLBACK_V1 -> Outcome.Fallback

        CloseReason.CLOSE_TOO_MANY -> Outcome.Broken(
            LiveChannelException("The live session closed: too many", SocketClose.TOO_MANY, conn.answered),
            reopen = true,
        )

        else -> Outcome.Reopen
    }

    /**
     * The game's pace, never longer than [LiveLimits.IDLE_UP_MILLIS]; the lobby and the results rest that long: what
     * happens there comes with a push.
     */
    private fun pauseAfter(snapshot: GameSnapshot?, intervalMillis: (GameSnapshot) -> Long): Long {
        if (snapshot == null) return PollingGameConnection.MIN_INTERVAL_MILLIS
        val quiet = snapshot.phase == GamePhase.LOBBY || snapshot.phase == GamePhase.FINISHED
        if (quiet) return LiveLimits.IDLE_UP_MILLIS
        return intervalMillis(snapshot)
            .coerceAtLeast(PollingGameConnection.MIN_INTERVAL_MILLIS)
            .coerceAtMost(LiveLimits.IDLE_UP_MILLIS)
    }

    /** What one `connect` keeps across its live sessions. */
    private class Connection(
        val session: PlayerSession,
        val outbox: LocationOutbox,
        val chatAfter: () -> Long?,
        val extras: () -> SyncExtras,
        val intervalMillis: (GameSnapshot) -> Long,
    ) {
        val fixes = Unacked()

        /** A Down came: the channel worked at least once. */
        var answered = false

        /** The newest snapshot given out, and its server time: an older one never follows it. */
        var last: GameSnapshot? = null
        var lastEmittedMillis = Long.MIN_VALUE

        private var backoffMillis = PollingGameConnection.MIN_BACKOFF_MILLIS

        fun nextBackoff(): Long = backoffMillis.also {
            backoffMillis = (it * 2).coerceAtMost(PollingGameConnection.MAX_BACKOFF_MILLIS)
        }

        fun resetBackoff() {
            backoffMillis = PollingGameConnection.MIN_BACKOFF_MILLIS
        }
    }

    /** One live session: its states, its Ups' numbers, the report the server has. */
    private class Run(val live: LiveOpenResponse, private val openedAt: TimeMark) {
        val states = LiveClientStates()
        val snapshots = LiveSnapshots()
        var seq = 0
        var deviceAcked: DeviceReport? = null
        var deviceSent: DeviceReport? = null
        var deviceSentSeq = 0

        /** The server's lifetime of the session, on this phone's monotonic clock: a wrong device clock can't cut it. */
        private val renewAfter = (live.expiresAtMillis - live.serverTimeMillis - LiveLimits.REOPEN_BEFORE_MILLIS)
            .coerceAtLeast(MIN_SESSION_MILLIS)
            .milliseconds

        fun expiresSoon(): Boolean = openedAt.elapsedNow() >= renewAfter

        fun acknowledgeDevice(ackUp: Int) {
            if (deviceSentSeq in 1..ackUp) {
                deviceAcked = deviceSent
                deviceSentSeq = 0
            }
        }
    }

    /** The fixes sent and not acknowledged yet. */
    private class Unacked {
        var samples: List<LocationSample> = emptyList()
            private set

        /** The Up that carried them last; 0: none of this session. */
        private var upSeq = 0

        fun take(outbox: LocationOutbox, seq: Int): List<LocationSample> {
            samples = (samples + outbox.drain()).takeLast(LiveLimits.MAX_FIXES_PER_UP)
            if (samples.isNotEmpty()) upSeq = seq
            return samples
        }

        fun acknowledge(ackUp: Int) {
            if (upSeq in 1..ackUp) clear()
        }

        /** A new session numbers its Ups from 1 again: the fixes go in its first Up. */
        fun newSession() {
            upSeq = 0
        }

        fun clear() {
            samples = emptyList()
            upSeq = 0
        }
    }

    private sealed interface Opened {
        data class Session(val live: LiveOpenResponse) : Opened

        data class Over(val reason: EndReason) : Opened

        data object Fallback : Opened

        data class Failed(val error: Exception, val retryInMillis: Long?) : Opened
    }

    private sealed interface Outcome {
        data class Ended(val reason: EndReason) : Outcome

        /** A new live session. */
        data object Reopen : Outcome

        /** Back to v1 for a while. */
        data object Fallback : Outcome

        /** The WebSocket broke: opened again after the backoff, on a new session when [reopen]. */
        data class Broken(val error: LiveChannelException, val reopen: Boolean = false) : Outcome
    }

    private sealed interface Handled {
        data class State(val chatMore: Boolean) : Handled

        data object Chat : Handled

        /** A state whose base is gone: the next Up asks for a full one. */
        data object Dropped : Handled

        data class Refused(val error: ApiException, val retryAfterMillis: Long?) : Handled
    }

    private class LinkClosed : Exception("The WebSocket v2 closed")

    private class Closing(val outcome: Outcome) : Exception("The live session closed")

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY = 429
        const val MILLIS_PER_SECOND = 1_000L

        /** A session the server gives less than this is still used this long. */
        const val MIN_SESSION_MILLIS = 60_000L
    }
}

/**
 * The live channel v2 failed: [code] is the WebSocket's close code (null: none, the network broke or it never opened),
 * [answered] whether a Down had come on this connection before, [fallbackToV1] that the server has v2 off.
 */
class LiveChannelException(
    message: String,
    val code: Int? = null,
    val answered: Boolean = false,
    val fallbackToV1: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The HTTP status v1 answers [this] error with, for an [ApiException] like a refused sync's. */
private fun ApiError.httpStatus(): Int = when (code) {
    ErrorCode.BAD_REQUEST -> if (reason == ErrorReason.TOO_MANY_REQUESTS) 429 else 400
    ErrorCode.UNAUTHORIZED -> 401
    ErrorCode.FORBIDDEN -> 403
    ErrorCode.NOT_FOUND -> 404
    ErrorCode.WRONG_STATE -> if (reason == ErrorReason.TOO_MANY_REQUESTS) 429 else 409
    ErrorCode.NO_LOCATION, ErrorCode.TOO_FAR, ErrorCode.INVALID_CODE -> 422
    ErrorCode.INTERNAL -> 500
}
