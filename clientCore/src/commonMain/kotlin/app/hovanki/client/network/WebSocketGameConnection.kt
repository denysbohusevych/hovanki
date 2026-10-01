package app.hovanki.client.network

import app.hovanki.shared.protocol.ClientFrame
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.ServerFrame
import app.hovanki.shared.protocol.SocketClose
import app.hovanki.shared.protocol.SocketLimits
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.protocolJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlin.time.TimeSource

/**
 * The game's live channel (docs/adr/0015-websockets.md, section 4): the same `sync` as [PollingGameConnection], as
 * frames over one socket, and the server's pokes cut the pause short. One sync in flight at a time; its samples count
 * as sent only once its answer came, else they go back to the [LocationOutbox]. A broken socket is opened again with
 * the same backoff as polling; the server's close codes end the session like a 401 or a 404 would.
 *
 * Every [ConnectionEvent.Problem] carries a [GameSocketException] when the socket failed, which
 * [AdaptiveGameConnection] reads to fall back to polling.
 */
class WebSocketGameConnection(
    private val opener: GameSocketOpener,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : GameConnection {
    override fun connect(
        session: PlayerSession,
        outbox: LocationOutbox,
        chatAfter: () -> Long?,
        extras: () -> SyncExtras,
        intervalMillis: (GameSnapshot) -> Long,
    ): Flow<ConnectionEvent> = flow {
        var backoffMillis = PollingGameConnection.MIN_BACKOFF_MILLIS
        while (true) {
            val outcome = runSocket(session, outbox, chatAfter, extras, intervalMillis) {
                backoffMillis = PollingGameConnection.MIN_BACKOFF_MILLIS
            }
            when (outcome) {
                is Outcome.Ended -> {
                    emit(ConnectionEvent.Ended(outcome.reason))
                    return@flow
                }

                is Outcome.Broken -> {
                    emit(ConnectionEvent.Problem(outcome.error, backoffMillis))
                    delay(backoffMillis)
                    backoffMillis = (backoffMillis * 2).coerceAtMost(PollingGameConnection.MAX_BACKOFF_MILLIS)
                }
            }
        }
    }

    private sealed interface Outcome {
        data class Ended(val reason: EndReason) : Outcome

        data class Broken(val error: GameSocketException) : Outcome
    }

    /** One socket, from opening to its end. [onAnswer]: a snapshot came, the connection is healthy. */
    private suspend fun FlowCollector<ConnectionEvent>.runSocket(
        session: PlayerSession,
        outbox: LocationOutbox,
        chatAfter: () -> Long?,
        extras: () -> SyncExtras,
        intervalMillis: (GameSnapshot) -> Long,
        onAnswer: () -> Unit,
    ): Outcome {
        // Ktor's HttpTimeout does not cover the upgrade, so a hanging one is cut here.
        val socket = try {
            withTimeoutOrNull(SocketLimits.OPEN_TIMEOUT_MILLIS) { opener.open(session) }
                ?: return Outcome.Broken(
                    GameSocketException("The socket did not open within ${SocketLimits.OPEN_TIMEOUT_MILLIS} ms"),
                )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Broken(GameSocketException("The socket did not open: ${e.message}", cause = e))
        }
        var inFlight = emptyList<LocationSample>()
        var answered = false
        try {
            var seq = 0L
            var errorPauseMillis = PollingGameConnection.MIN_BACKOFF_MILLIS
            while (true) {
                val samples = outbox.drain()
                inFlight = samples
                // Sightings are not sent again after a failure: by then they are stale.
                val (nearby, device) = extras()
                val sent = ++seq
                val frame = encode(ClientFrame.Sync(sent, SyncRequest(samples, chatAfter(), nearby, device)))
                // A socket the server closed can hold a send up for good, without a close to read.
                withTimeoutOrNull(SocketLimits.REPLY_TIMEOUT_MILLIS) { socket.send(frame) }
                    ?: throw GameSocketException(
                        "The frame was not sent within ${SocketLimits.REPLY_TIMEOUT_MILLIS} ms",
                        answered = answered,
                    )
                val sentAt = timeSource.markNow()
                // A poke while the answer is on its way may be about something newer than the answer.
                var poked = false
                val answer = withTimeoutOrNull(SocketLimits.REPLY_TIMEOUT_MILLIS) {
                    var reply: ServerFrame? = null
                    while (reply == null) {
                        when (val frame = socket.nextFrame()) {
                            ServerFrame.Poke -> poked = true
                            is ServerFrame.Snapshot -> if (frame.seq == sent) reply = frame
                            is ServerFrame.Error -> if (frame.seq == sent) reply = frame
                        }
                    }
                    reply
                }
                    ?: throw GameSocketException(
                        "No answer within ${SocketLimits.REPLY_TIMEOUT_MILLIS} ms",
                        answered = answered,
                    )
                // Answered or refused: the samples are the server's now, sending them again would do nothing good.
                inFlight = emptyList()
                val pauseMillis = when (answer) {
                    is ServerFrame.Snapshot -> {
                        answered = true
                        errorPauseMillis = PollingGameConnection.MIN_BACKOFF_MILLIS
                        onAnswer()
                        emit(ConnectionEvent.Snapshot(answer.snapshot, Transport.SOCKET))
                        pauseAfter(answer.snapshot, intervalMillis)
                    }

                    is ServerFrame.Error -> {
                        val pause = errorPauseMillis
                        emit(ConnectionEvent.Problem(answer.toApiException(), pause))
                        errorPauseMillis = (pause * 2).coerceAtMost(PollingGameConnection.MAX_BACKOFF_MILLIS)
                        poked = false
                        pause
                    }

                    ServerFrame.Poke -> error("A poke is no answer")
                }
                if (!poked) {
                    // The pause, unless a poke cuts it short.
                    poked = withTimeoutOrNull(pauseMillis) {
                        while (socket.nextFrame() != ServerFrame.Poke) Unit
                        true
                    } == true
                }
                if (poked) {
                    val gap = SocketLimits.SYNC_AFTER_POKE_GAP_MILLIS - sentAt.elapsedNow().inWholeMilliseconds
                    if (gap > 0) delay(gap)
                }
            }
        } catch (e: CancellationException) {
            outbox.requeue(inFlight)
            // Ktor's send into a session the server closed throws a CancellationException of the session ("closed with
            // code 4401") into a coroutine nobody cancelled: that is the server's close, not ours. Rethrown, it would
            // end the flow silently and the app would never learn the session is over.
            if (currentCoroutineContext().isActive) return closed(socket, answered)
            socket.close()
            throw e
        } catch (e: SocketClosed) {
            outbox.requeue(inFlight)
            return closed(socket, answered)
        } catch (e: GameSocketException) {
            outbox.requeue(inFlight)
            socket.close()
            return Outcome.Broken(e)
        } catch (e: Exception) {
            outbox.requeue(inFlight)
            socket.close()
            return Outcome.Broken(
                GameSocketException("The socket failed: ${e.message}", answered = answered, cause = e),
            )
        }
    }

    /** The socket is closed: the server's close code says whether the session is over or to open the socket again. */
    private suspend fun closed(socket: GameSocket, answered: Boolean): Outcome = when (val code = socket.closeCode()) {
        SocketClose.SESSION_REJECTED -> Outcome.Ended(EndReason.SESSION_REJECTED)
        SocketClose.GAME_NOT_FOUND -> Outcome.Ended(EndReason.GAME_NOT_FOUND)
        else -> Outcome.Broken(GameSocketException("The socket closed (${code ?: "no code"})", code, answered))
    }

    /** The game's pace; the lobby and the results rest longer: whatever happens there comes with a poke. */
    private fun pauseAfter(snapshot: GameSnapshot, intervalMillis: (GameSnapshot) -> Long): Long {
        val pace = intervalMillis(snapshot).coerceAtLeast(PollingGameConnection.MIN_INTERVAL_MILLIS)
        val quiet = snapshot.phase == GamePhase.LOBBY || snapshot.phase == GamePhase.FINISHED
        return if (quiet) maxOf(pace, QUIET_INTERVAL_MILLIS) else pace
    }

    private class SocketClosed : Exception("The socket closed")

    /** The next frame this app can read; a newer server's frame of another type is skipped. */
    private suspend fun GameSocket.nextFrame(): ServerFrame {
        while (true) {
            val text = receive() ?: throw SocketClosed()
            decode(text)?.let { return it }
        }
    }

    private fun encode(frame: ClientFrame): String = protocolJson.encodeToString(ClientFrame.serializer(), frame)

    private fun decode(text: String): ServerFrame? = try {
        protocolJson.decodeFromString(ServerFrame.serializer(), text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun ServerFrame.Error.toApiException(): ApiException {
        val status = when (error.code) {
            ErrorCode.BAD_REQUEST -> 400
            ErrorCode.UNAUTHORIZED -> 401
            ErrorCode.FORBIDDEN -> 403
            ErrorCode.NOT_FOUND -> 404
            ErrorCode.WRONG_STATE -> if (error.reason == ErrorReason.TOO_MANY_REQUESTS) 429 else 409
            ErrorCode.NO_LOCATION, ErrorCode.TOO_FAR, ErrorCode.INVALID_CODE -> 422
            ErrorCode.INTERNAL -> 500
        }
        return ApiException(status, error, retryAfterSeconds)
    }

    companion object {
        /** The lobby's and the results' pace on the socket: the pokes bring what happens there. */
        const val QUIET_INTERVAL_MILLIS = 10_000L
    }
}

/**
 * The live channel failed: [code] is the server's close code ([SocketClose]; null: none, the network broke or the socket
 * never opened), [answered] whether this socket had answered a sync before.
 */
class GameSocketException(
    message: String,
    val code: Int? = null,
    val answered: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)
