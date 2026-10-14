package app.hovanki.client.network.live

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.hovanki.client.network.ApiException
import app.hovanki.client.network.ConnectionEvent
import app.hovanki.client.network.EndReason
import app.hovanki.client.network.GameConnection
import app.hovanki.client.network.LocationOutbox
import app.hovanki.client.network.SyncExtras
import app.hovanki.client.network.Transport
import app.hovanki.client.network.testSession
import app.hovanki.client.network.testSnapshot
import app.hovanki.shared.live.LiveLimits
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.ServerFeature
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** v2 or v1 (docs/live-v2/client.md §5): when the app switches, on scripted connections. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveFirstGameConnectionTest {
    private val v2On = testSnapshot().copy(enabledFeatures = listOf(ServerFeature.LIVE_V2.name))
    private val plain = testSnapshot()

    @Test
    fun itStaysOnV1UntilTheServerHasV2On() = runTest {
        val v1 = Scripted(Transport.POLLING)
        val live = Scripted(Transport.LIVE_WS)
        liveFirst(live, v1).test {
            v1.snapshot(plain)
            assertEquals(Transport.POLLING, awaitSnapshot().transport)
            v1.snapshot(v2On)
            assertEquals(Transport.POLLING, awaitSnapshot().transport)
            // v1 stops, v2 takes over.
            live.awaitConnect(1)
            live.snapshot(v2On)
            assertEquals(Transport.LIVE_WS, awaitSnapshot().transport)
            assertEquals(1, v1.cancelled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theServerTurningV2OffSendsTheAppToV1ForTenMinutes() = runTest {
        val v1 = Scripted(Transport.POLLING)
        val live = Scripted(Transport.LIVE_WS)
        liveFirst(live, v1).test {
            v1.snapshot(v2On)
            awaitSnapshot()
            live.awaitConnect(1)
            live.snapshot(v2On)
            awaitSnapshot()
            live.problem(LiveChannelException("Off", fallbackToV1 = true, answered = true))
            // Not shown: v1 reports its own.
            v1.awaitConnect(2)
            val fellBackAt = currentTime
            v1.snapshot(v2On)
            assertEquals(Transport.POLLING, awaitSnapshot().transport, "still v1 within ten minutes")
            testScheduler.advanceTimeBy(LiveLimits.V1_AFTER_DISABLED_MILLIS)
            v1.snapshot(v2On)
            awaitSnapshot()
            live.awaitConnect(2)
            assertEquals(LiveLimits.V1_AFTER_DISABLED_MILLIS, currentTime - fellBackAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aChannelThatNeverWorkedGoesToV1() = runTest {
        val v1 = Scripted(Transport.POLLING)
        val live = Scripted(Transport.LIVE_WS)
        liveFirst(live, v1).test {
            v1.snapshot(v2On)
            awaitSnapshot()
            live.awaitConnect(1)
            live.problem(LiveChannelException("No upgrade", answered = false))
            v1.awaitConnect(2)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aWorkingChannelReconnectsByItselfUntilItBreaksTooOften() = runTest {
        val v1 = Scripted(Transport.POLLING)
        val live = Scripted(Transport.LIVE_WS)
        liveFirst(live, v1).test {
            v1.snapshot(v2On)
            awaitSnapshot()
            live.awaitConnect(1)
            live.snapshot(v2On)
            awaitSnapshot()
            repeat(LiveFirstGameConnection.MAX_BREAKS - 1) {
                live.problem(LiveChannelException("Closed", code = 1001, answered = true))
                assertIs<ConnectionEvent.Problem>(awaitItem(), "shown: the channel reconnects")
            }
            // A refused Up is no break.
            live.problem(ApiException(500, ApiError(ErrorCode.INTERNAL, "Internal error")))
            assertIs<ConnectionEvent.Problem>(awaitItem())
            live.problem(LiveChannelException("Closed", code = 1001, answered = true))
            v1.awaitConnect(2) // the third break within a minute
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theEndOfTheSessionEndsIt() = runTest {
        val v1 = Scripted(Transport.POLLING)
        val live = Scripted(Transport.LIVE_WS)
        liveFirst(live, v1).test {
            v1.snapshot(v2On)
            awaitSnapshot()
            live.awaitConnect(1)
            live.end(EndReason.GAME_NOT_FOUND)
            assertEquals(ConnectionEvent.Ended(EndReason.GAME_NOT_FOUND), awaitItem())
            awaitComplete()
        }
    }

    private fun TestScope.liveFirst(live: Scripted, v1: Scripted): Flow<ConnectionEvent> =
        LiveFirstGameConnection(live, v1, testScheduler.timeSource).connect(testSession, LocationOutbox())

    private suspend fun ReceiveTurbine<ConnectionEvent>.awaitSnapshot() =
        assertIs<ConnectionEvent.Snapshot>(awaitItem())

    /** A connection whose events the test sends; counts its connects and cancellations. */
    private class Scripted(private val transport: Transport) : GameConnection {
        private val connects = Channel<Int>(Channel.UNLIMITED)
        var cancelled = 0
            private set
        private var count = 0
        private val events = Channel<ConnectionEvent>(Channel.UNLIMITED)

        /** Waits for the [n]th connect. */
        suspend fun awaitConnect(n: Int) {
            while (connects.receive() < n) Unit
        }

        suspend fun snapshot(snapshot: GameSnapshot) = events.send(ConnectionEvent.Snapshot(snapshot, transport))

        suspend fun problem(error: Throwable) = events.send(ConnectionEvent.Problem(error, 1_000))

        suspend fun end(reason: EndReason) = events.send(ConnectionEvent.Ended(reason))

        override fun connect(
            session: PlayerSession,
            outbox: LocationOutbox,
            chatAfter: () -> Long?,
            extras: () -> SyncExtras,
            intervalMillis: (GameSnapshot) -> Long,
        ): Flow<ConnectionEvent> = flow {
            connects.send(++count)
            try {
                forward()
            } finally {
                cancelled++
            }
        }

        private suspend fun FlowCollector<ConnectionEvent>.forward() {
            for (event in events) {
                emit(event)
                if (event is ConnectionEvent.Ended) return
            }
            awaitCancellation()
        }
    }
}
