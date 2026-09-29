package app.hovanki.client.network

import app.cash.turbine.test
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.SocketClose
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

/** Socket or polling (docs/adr/0015-websockets.md, section 4): when the app switches, on scripted connections. */
@OptIn(ExperimentalCoroutinesApi::class)
class AdaptiveGameConnectionTest {
    private val live = testSnapshot().copy(enabledFeatures = listOf(ServerFeature.LIVE_SOCKET.name))
    private val plain = testSnapshot()

    @Test
    fun itPollsUntilTheServerHasTheSocketOn() = runTest {
        val polling = Scripted(Transport.POLLING)
        val socket = Scripted(Transport.SOCKET)
        adaptive(socket, polling).test {
            polling.snapshot(plain)
            assertEquals(Transport.POLLING, awaitSnapshot().transport)
            polling.snapshot(live)
            assertEquals(Transport.POLLING, awaitSnapshot().transport)
            // Polling stops, the socket takes over.
            socket.awaitConnect(1)
            socket.snapshot(live)
            assertEquals(Transport.SOCKET, awaitSnapshot().transport)
            assertEquals(1, polling.cancelled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aSocketThatDoesNotOpenPollsForAMinute() = runTest {
        val polling = Scripted(Transport.POLLING)
        val socket = Scripted(Transport.SOCKET)
        adaptive(socket, polling).test {
            polling.snapshot(live)
            awaitSnapshot()
            socket.awaitConnect(1)
            socket.problem(GameSocketException("No upgrade", answered = false))
            // Not shown: polling reports its own problems.
            polling.awaitConnect(2)
            val fellBackAt = currentTime
            polling.snapshot(live)
            assertEquals(Transport.POLLING, awaitSnapshot().transport, "still polling within the minute")
            testScheduler.advanceTimeBy(AdaptiveGameConnection.POLL_AFTER_FAILURE_MILLIS)
            polling.snapshot(live)
            awaitSnapshot()
            socket.awaitConnect(2)
            assertEquals(AdaptiveGameConnection.POLL_AFTER_FAILURE_MILLIS, currentTime - fellBackAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theServerSaysPoll() = runTest {
        val polling = Scripted(Transport.POLLING)
        val socket = Scripted(Transport.SOCKET)
        adaptive(socket, polling).test {
            polling.snapshot(live)
            awaitSnapshot()
            socket.awaitConnect(1)
            socket.snapshot(live)
            awaitSnapshot()
            socket.problem(GameSocketException("Poll", code = SocketClose.POLL, answered = true))
            polling.awaitConnect(2)
            // Switched off: the snapshots say so, and the app polls on.
            testScheduler.advanceTimeBy(AdaptiveGameConnection.POLL_AFTER_FAILURE_MILLIS * 2)
            polling.snapshot(plain)
            assertEquals(Transport.POLLING, awaitSnapshot().transport)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aWorkingSocketReconnectsByItselfUntilItBreaksTooOften() = runTest {
        val polling = Scripted(Transport.POLLING)
        val socket = Scripted(Transport.SOCKET)
        adaptive(socket, polling).test {
            polling.snapshot(live)
            awaitSnapshot()
            socket.awaitConnect(1)
            socket.snapshot(live)
            awaitSnapshot()
            repeat(AdaptiveGameConnection.MAX_BREAKS - 1) {
                socket.problem(GameSocketException("Closed", code = 1001, answered = true))
                assertIs<ConnectionEvent.Problem>(awaitItem(), "shown: the socket reconnects")
            }
            // A refused sync is no break.
            socket.problem(ApiException(400, ApiError(ErrorCode.BAD_REQUEST, "Too many samples")))
            assertIs<ConnectionEvent.Problem>(awaitItem())
            socket.problem(GameSocketException("Closed", code = 1001, answered = true))
            polling.awaitConnect(2) // the third break within a minute
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theEndOfTheSessionEndsIt() = runTest {
        val polling = Scripted(Transport.POLLING)
        val socket = Scripted(Transport.SOCKET)
        adaptive(socket, polling).test {
            polling.snapshot(live)
            awaitSnapshot()
            socket.awaitConnect(1)
            socket.end(EndReason.GAME_NOT_FOUND)
            assertEquals(ConnectionEvent.Ended(EndReason.GAME_NOT_FOUND), awaitItem())
            awaitComplete()
        }
        val polling2 = Scripted(Transport.POLLING)
        adaptive(Scripted(Transport.SOCKET), polling2).test {
            polling2.end(EndReason.SESSION_REJECTED)
            assertEquals(ConnectionEvent.Ended(EndReason.SESSION_REJECTED), awaitItem())
            awaitComplete()
        }
    }

    private fun TestScope.adaptive(socket: Scripted, polling: Scripted): Flow<ConnectionEvent> =
        AdaptiveGameConnection(socket, polling, testScheduler.timeSource).connect(testSession, LocationOutbox())

    private suspend fun app.cash.turbine.ReceiveTurbine<ConnectionEvent>.awaitSnapshot() =
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
