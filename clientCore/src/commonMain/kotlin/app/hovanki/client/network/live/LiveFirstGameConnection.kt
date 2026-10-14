package app.hovanki.client.network.live

import app.hovanki.client.network.ApiException
import app.hovanki.client.network.ConnectionEvent
import app.hovanki.client.network.GameConnection
import app.hovanki.client.network.LocationOutbox
import app.hovanki.client.network.SyncExtras
import app.hovanki.shared.live.LiveLimits
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.ServerFeature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * What the app syncs with (docs/live-v2/client.md §5): [v1] (the polling and v1's socket, `AdaptiveGameConnection`) to
 * begin with and whenever the live channel v2 can't be used, [live] while the server says it has
 * [ServerFeature.LIVE_V2] on ([GameSnapshot.enabledFeatures]) and it works. The first snapshot so comes from v1, as now.
 *
 * Back to v1 for [v1AfterFailureMillis] when the server has v2 off ([LiveChannelException.fallbackToV1]: the open's
 * `FEATURE_DISABLED`, the close 4503), when the channel never worked on this connection, or when it broke for the
 * [MAX_BREAKS]th time within a minute; a single break of a channel that worked is left to it (a server restart, Wi-Fi
 * to LTE). The fall back is not a problem to show: v1 reports its own. The fixes v2 had not got acknowledged are back
 * in the outbox by then. Old servers never see v2.
 */
class LiveFirstGameConnection(
    private val live: GameConnection,
    private val v1: GameConnection,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** [LiveLimits.V1_AFTER_DISABLED_MILLIS]; shorter only where a test waits for v2's return. */
    private val v1AfterFailureMillis: Long = LiveLimits.V1_AFTER_DISABLED_MILLIS,
) : GameConnection {
    override fun connect(
        session: PlayerSession,
        outbox: LocationOutbox,
        chatAfter: () -> Long?,
        extras: () -> SyncExtras,
        intervalMillis: (GameSnapshot) -> Long,
    ): Flow<ConnectionEvent> = flow {
        // v1 until then at least, whatever the server says.
        var v1Until: TimeMark? = null
        val breaks = ArrayDeque<TimeMark>()
        var ended = false
        while (!ended) {
            v1.connect(session, outbox, chatAfter, extras, intervalMillis).transformWhile { event ->
                emit(event)
                when (event) {
                    is ConnectionEvent.Ended -> {
                        ended = true
                        false
                    }

                    is ConnectionEvent.Problem, is ConnectionEvent.Chat -> true

                    is ConnectionEvent.Snapshot -> !(event.snapshot.hasLiveV2() && v1Until?.hasPassedNow() != false)
                }
            }.collect { emit(it) }
            if (ended) break

            live.connect(session, outbox, chatAfter, extras, intervalMillis).transformWhile { event ->
                when (event) {
                    is ConnectionEvent.Ended -> {
                        emit(event)
                        ended = true
                        false
                    }

                    is ConnectionEvent.Snapshot, is ConnectionEvent.Chat -> {
                        emit(event)
                        true
                    }

                    is ConnectionEvent.Problem -> {
                        val toV1 = when (val error = event.error) {
                            // A refused Up (or too many opens): the channel is fine, the request was not.
                            is ApiException -> false

                            is LiveChannelException -> {
                                while (breaks.isNotEmpty() && breaks.first().elapsedNow() >= WINDOW) {
                                    breaks.removeFirst()
                                }
                                breaks.addLast(timeSource.markNow())
                                error.fallbackToV1 || !error.answered || breaks.size >= MAX_BREAKS
                            }

                            else -> true
                        }
                        if (toV1) {
                            v1Until = timeSource.markNow() + v1AfterFailureMillis.milliseconds
                            breaks.clear()
                        } else {
                            emit(event)
                        }
                        !toV1
                    }
                }
            }.collect { emit(it) }
        }
    }

    private fun GameSnapshot.hasLiveV2(): Boolean = ServerFeature.LIVE_V2.name in enabledFeatures

    companion object {
        /** Breaks of a working channel within a minute that send the app to v1. */
        const val MAX_BREAKS = 3
        private val WINDOW = 60.seconds
    }
}
