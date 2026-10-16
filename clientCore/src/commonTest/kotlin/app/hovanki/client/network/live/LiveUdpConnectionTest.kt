package app.hovanki.client.network.live

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.hovanki.client.network.ConnectionEvent
import app.hovanki.client.network.EndReason
import app.hovanki.client.network.LocationOutbox
import app.hovanki.client.network.Transport
import app.hovanki.client.network.testSample
import app.hovanki.client.network.testSession
import app.hovanki.client.network.testSnapshot
import app.hovanki.shared.live.LiveLimits
import app.hovanki.shared.live.LiveUplink
import app.hovanki.shared.live.v2.Close
import app.hovanki.shared.live.v2.CloseReason
import app.hovanki.shared.live.v2.Down
import app.hovanki.shared.protocol.ChatChannel
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.LiveTransports
import app.hovanki.shared.protocol.LiveUdpEndpoint
import app.hovanki.shared.protocol.LiveWs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The live channel v2 over UDP (docs/live-v2/spec.md §5, client.md §4.1) on a fake server, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveUdpConnectionTest {
    private val seeking = testSnapshot(serverTimeMillis = 5_000, syncIntervalSeconds = 3, phase = GamePhase.SEEKING)
    private val lobby = testSnapshot(serverTimeMillis = 5_000)

    @Test
    fun theSessionGoesOverUdpOnceItsAddressIsChecked() = runTest {
        val server = udpServer()
        val chat = List(5) { ChatMessage(it + 1L, testSession.playerId, "x".repeat(400), 1_000L + it, ChatChannel.ALL) }
        val long = seeking.copy(chat = chat)
        connection(server).connect(testSession, LocationOutbox().apply { add(testSample(1)) }).test {
            val path = server.awaitSession(0).udp
            assertEquals(listOf(LiveTransports.UDP, LiveTransports.WS), server.opens.single().transports)
            // The first Up was answered with the check of its address only; the next one echoes it at once.
            val up = path.awaitUp()
            assertEquals(0, currentTime)
            assertEquals(2, up.seq)
            assertEquals(1, path.challenges)
            assertTrue(up.path_response.size > 0)
            assertTrue(LiveUplink.samples(up).isEmpty(), "the check's answer acknowledged the fix")
            // A Down longer than a datagram comes in pieces.
            path.answer(up, long)
            val event = awaitSnapshot()
            assertEquals(Transport.LIVE_UDP, event.transport)
            assertEquals(chat, event.snapshot.chat)
            assertTrue(server.sockets.isEmpty(), "no WebSocket")

            val before = currentTime
            val next = path.awaitUp()
            assertEquals(3_000, currentTime - before, "the game's pace")
            assertEquals(0, next.path_response.size, "the address is checked")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun withoutAnAddressTheSessionGoesOverTheWebSocket() = runTest {
        val server = FakeLiveServer()
        connection(server).connect(testSession, LocationOutbox()).test {
            val socket = server.awaitSocket(0)
            assertEquals(listOf(LiveTransports.UDP, LiveTransports.WS), server.opens.single().transports)
            socket.answer(socket.awaitUp(), seeking)
            assertEquals(Transport.LIVE_WS, awaitSnapshot().transport)
            assertTrue(server.datagramSockets.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun lostUpsGoAgainAtOnceThenFromANewPortThenOverTheWebSocketUntilUdpIsTriedAgain() = runTest {
        val server = udpServer()
        connection(server).connect(testSession, LocationOutbox()).test {
            val path = server.awaitSession(0).udp
            path.answer(path.awaitUp(), lobby)
            assertEquals(Transport.LIVE_UDP, awaitSnapshot().transport)
            server.udpBlocked = true
            val lostFrom = currentTime

            // At the lobby's pace an Up, lost; again after the wait for its answer, lost; from a new port, lost.
            val socket = server.awaitSocket(0)
            val givenUpAt = currentTime
            val gap = LiveLimits.OPEN_UDP_GAP_MILLIS
            assertEquals(LiveLimits.IDLE_UP_MILLIS + LiveLimits.UDP_LOST_AFTER_UPS * gap, currentTime - lostFrom)
            assertEquals(2, server.datagramSockets.size, "a new port before the last try")
            assertTrue(server.datagramSockets.all { it.isClosed })
            assertEquals(1, server.sessions.size, "the same session")
            val up = socket.awaitUp()
            assertEquals(6, up.seq, "the session's Ups go on")
            socket.answer(up, lobby.copy(serverTimeMillis = 9_000))
            assertEquals(Transport.LIVE_WS, awaitSnapshot().transport)

            // UDP again after a while, on the same session, from a new port checked by the server.
            server.udpBlocked = false
            while (true) socket.answer(socket.awaitUpOrNull() ?: break, lobby)
            assertTrue(socket.isClosedByApp)
            assertTrue(currentTime - givenUpAt >= LiveLimits.UDP_RETRY_MILLIS)
            val again = path.awaitUp()
            assertTrue(again.path_response.size > 0)
            assertEquals(1, server.sessions.size)
            path.answer(again, lobby.copy(serverTimeMillis = 700_000))
            assertEquals(Transport.LIVE_UDP, awaitSnapshotAt(700_000).transport)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aNewNetworkTakesANewPortOnTheSameSession() = runTest {
        var generation = 0L
        val server = udpServer()
        connection(server, network = { generation }).connect(testSession, LocationOutbox()).test {
            val path = server.awaitSession(0).udp
            path.answer(path.awaitUp(), seeking)
            awaitSnapshot()

            generation++
            val up = path.awaitUp()
            assertEquals(2, server.datagramSockets.size)
            assertTrue(server.datagramSockets[0].isClosed)
            assertTrue(up.path_response.size > 0, "the new address is checked")
            assertEquals(2, path.challenges)
            assertEquals(1, server.opens.size, "no new session")
            path.answer(up, seeking.copy(serverTimeMillis = 9_000))
            assertEquals(Transport.LIVE_UDP, awaitSnapshot().transport)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theSessionsResetOpensANewOneAndAForgedOneIsDropped() = runTest {
        val server = udpServer()
        connection(server).connect(testSession, LocationOutbox()).test {
            val path = server.awaitSession(0).udp
            path.answer(path.awaitUp(), seeking)
            awaitSnapshot()
            path.reset(ByteArray(16) { 1 })
            path.reset()
            val next = server.awaitSession(1)
            assertEquals(2, server.opens.size)
            next.udp.awaitUp()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aCloseOverUdpSaysWhatNext() = runTest {
        val server = udpServer()
        connection(server).connect(testSession, LocationOutbox()).test {
            val path = server.awaitSession(0).udp
            path.answer(path.awaitUp(), seeking)
            awaitSnapshot()
            // Another session of the player took this one's place: a new one.
            path.send(Down(ack_up = path.session.lastSeq, close = Close(CloseReason.CLOSE_REPLACED)))
            val next = server.awaitSession(1).udp
            next.awaitUp()
            next.send(Down(close = Close(CloseReason.CLOSE_GAME_NOT_FOUND)))
            assertEquals(ConnectionEvent.Ended(EndReason.GAME_NOT_FOUND), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun udpGivenUpRightBeforeTheSessionTurnedOutGoneIsTriedOnTheNextSession() = runTest {
        val server = udpServer().apply { udpBlocked = true }
        connection(server).connect(testSession, LocationOutbox()).test {
            val socket = server.awaitSocket(0)
            assertEquals(LiveLimits.UDP_LOST_AFTER_UPS * LiveLimits.OPEN_UDP_GAP_MILLIS, currentTime)
            // The server restarted without its reset secret: the WebSocket doesn't know the session either.
            server.udpBlocked = false
            socket.closeFromServer(LiveWs.CLOSE_REOPEN)
            val path = server.awaitSession(1).udp
            path.answer(path.awaitUp(), seeking)
            assertEquals(Transport.LIVE_UDP, awaitSnapshot().transport)
            assertEquals(1, server.sockets.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun udpServer() = FakeLiveServer().apply { udp = LiveUdpEndpoint("live.example", 443) }

    private fun TestScope.connection(
        server: FakeLiveServer,
        network: NetworkChanges = NetworkChanges.None,
    ): LiveGameConnection = LiveGameConnection(
        server,
        server,
        serverTime = { currentTime },
        timeSource = testScheduler.timeSource,
        udp = LiveUdp(server, TestAeads, network),
    )

    private suspend fun ReceiveTurbine<ConnectionEvent>.awaitSnapshot() =
        assertIs<ConnectionEvent.Snapshot>(awaitItem())

    /** The snapshot of [serverTimeMillis], skipping the ones before. */
    private suspend fun ReceiveTurbine<ConnectionEvent>.awaitSnapshotAt(
        serverTimeMillis: Long,
    ): ConnectionEvent.Snapshot {
        while (true) {
            val snapshot = awaitSnapshot()
            if (snapshot.snapshot.serverTimeMillis == serverTimeMillis) return snapshot
        }
    }
}
