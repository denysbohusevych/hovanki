package app.hovanki.client.network

import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.SocketClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * What the app syncs with (docs/adr/0015-websockets.md, section 4): [polling] to begin with and whenever the live
 * channel can't be used, the [socket] while the server says it has [ServerFeature.LIVE_SOCKET] on
 * ([GameSnapshot.enabledFeatures]) and it works. The socket falls back to polling for [POLL_AFTER_FAILURE_MILLIS] when
 * it doesn't open, when it breaks for the [MAX_BREAKS]th time within a minute, or when the server says to poll
 * ([SocketClose.POLL]); a single break of a socket that worked is left to it to reconnect (a server restart, Wi-Fi to
 * LTE). The fall back itself is not a problem to show: polling reports its own. Old servers never see a socket.
 */
class AdaptiveGameConnection(
    private val socket: GameConnection,
    private val polling: GameConnection,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** [POLL_AFTER_FAILURE_MILLIS]; shorter only where a test waits for the socket's return. */
    private val pollAfterFailureMillis: Long = POLL_AFTER_FAILURE_MILLIS,
) : GameConnection {
    override fun connect(
        session: PlayerSession,
        outbox: LocationOutbox,
        chatAfter: () -> Long?,
        extras: () -> SyncExtras,
        intervalMillis: (GameSnapshot) -> Long,
    ): Flow<ConnectionEvent> = flow {
        // Polling until then at least, whatever the server says.
        var pollUntil: TimeMark? = null
        val breaks = ArrayDeque<TimeMark>()
        var ended = false
        while (!ended) {
            polling.connect(session, outbox, chatAfter, extras, intervalMillis).transformWhile { event ->
                emit(event)
                when (event) {
                    is ConnectionEvent.Ended -> {
                        ended = true
                        false
                    }

                    is ConnectionEvent.Problem -> true

                    is ConnectionEvent.Snapshot ->
                        !(event.snapshot.hasLiveSocket() && pollUntil?.hasPassedNow() != false)
                }
            }.collect { emit(it) }
            if (ended) break

            socket.connect(session, outbox, chatAfter, extras, intervalMillis).transformWhile { event ->
                when (event) {
                    is ConnectionEvent.Ended -> {
                        emit(event)
                        ended = true
                        false
                    }

                    is ConnectionEvent.Snapshot -> {
                        emit(event)
                        true
                    }

                    is ConnectionEvent.Problem -> {
                        val error = event.error
                        val poll = when (error) {
                            // A refused sync (a 4xx): the socket is fine, the request was not.
                            is ApiException -> false

                            is GameSocketException -> {
                                while (breaks.isNotEmpty() && breaks.first().elapsedNow() >= WINDOW) {
                                    breaks.removeFirst()
                                }
                                breaks.addLast(timeSource.markNow())
                                !error.answered || error.code == SocketClose.POLL || breaks.size >= MAX_BREAKS
                            }

                            else -> true
                        }
                        if (poll) {
                            pollUntil = timeSource.markNow() + pollAfterFailureMillis.milliseconds
                            breaks.clear()
                        } else {
                            emit(event)
                        }
                        !poll
                    }
                }
            }.collect { emit(it) }
        }
    }

    private fun GameSnapshot.hasLiveSocket(): Boolean = ServerFeature.LIVE_SOCKET.name in enabledFeatures

    companion object {
        /** Polling for this long after the socket failed, then the socket again. */
        const val POLL_AFTER_FAILURE_MILLIS = 60_000L

        /** Breaks of a working socket within a minute that send the app to polling. */
        const val MAX_BREAKS = 3
        private val WINDOW = 60.seconds
    }
}
