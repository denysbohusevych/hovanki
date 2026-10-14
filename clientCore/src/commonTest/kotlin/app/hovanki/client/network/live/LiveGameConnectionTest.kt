package app.hovanki.client.network.live

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.hovanki.client.network.ApiException
import app.hovanki.client.network.ConnectionEvent
import app.hovanki.client.network.EndReason
import app.hovanki.client.network.LocationOutbox
import app.hovanki.client.network.SyncExtras
import app.hovanki.client.network.Transport
import app.hovanki.client.network.testMessage
import app.hovanki.client.network.testPlayer
import app.hovanki.client.network.testSample
import app.hovanki.client.network.testSession
import app.hovanki.client.network.testSnapshot
import app.hovanki.shared.live.LiveLimits
import app.hovanki.shared.live.LiveUplink
import app.hovanki.shared.live.v2.Down
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.DeviceReport
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.LiveTransports
import app.hovanki.shared.protocol.LiveWs
import app.hovanki.shared.protocol.NearbySighting
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.SocketClose
import app.hovanki.shared.protocol.SocketLimits
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The live channel v2's connection (docs/live-v2/client.md §3, §8) on a fake server, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveGameConnectionTest {
    private val seeking = testSnapshot(serverTimeMillis = 5_000, syncIntervalSeconds = 3, phase = GamePhase.SEEKING)

    @Test
    fun anUpIsAnsweredWithTheWholeStateThenOnlyWithWhatChanged() = runTest {
        val server = FakeLiveServer()
        val outbox = LocationOutbox().apply { add(testSample(1)) }
        connection(server).connect(testSession, outbox, chatAfter = { 7 }).test {
            val socket = server.awaitSocket(0)
            assertEquals(listOf(LiveTransports.WS), server.opens.single().transports)
            val first = socket.awaitUp()
            assertEquals(1, first.seq)
            assertEquals(0, first.ack_state)
            assertEquals(listOf(testSample(1)), LiveUplink.samples(first))
            assertEquals(7, first.chat_after)
            socket.answer(first, seeking)
            val event = awaitSnapshot()
            assertEquals(asV2Shows(seeking), event.snapshot)
            assertEquals(Transport.LIVE_WS, event.transport)
            assertTrue(event.bytes!! > 0)

            // The next Up after the game's pace, acknowledging the state; its answer carries only what changed.
            val before = currentTime
            val second = socket.awaitUp()
            assertEquals(3_000, currentTime - before)
            assertEquals(2, second.seq)
            assertEquals(1, second.ack_state)
            assertTrue(LiveUplink.samples(second).isEmpty(), "the fix was acknowledged")
            val caught = seeking.copy(
                serverTimeMillis = 8_000,
                players = listOf(testPlayer.copy(status = PlayerStatus.CAUGHT)),
            )
            socket.answer(second, caught)
            assertEquals(asV2Shows(caught), awaitSnapshot().snapshot)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun fixesGoInEveryUpUntilAcknowledgedAndBackToTheOutboxAtTheEnd() = runTest {
        val server = FakeLiveServer()
        val outbox = LocationOutbox().apply { add(testSample(1)) }
        connection(server).connect(testSession, outbox).test {
            val socket = server.awaitSocket(0)
            assertEquals(listOf(testSample(1)), LiveUplink.samples(socket.awaitUp()))
            outbox.add(testSample(2))
            // No answer: the WebSocket is given up and opened again on the same session; the fix goes again.
            val problem = assertIs<ConnectionEvent.Problem>(awaitItem())
            assertFalse(assertIs<LiveChannelException>(problem.error).answered)
            assertEquals(SocketLimits.REPLY_TIMEOUT_MILLIS, currentTime)
            val again = server.awaitSocket(1)
            assertEquals(1, server.opens.size, "the same live session")
            val up = again.awaitUp()
            assertEquals(listOf(testSample(1), testSample(2)), LiveUplink.samples(up))
            again.answer(up, seeking)
            awaitSnapshot()

            outbox.add(testSample(3))
            assertEquals(listOf(testSample(3)), LiveUplink.samples(again.awaitUp()))
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(testSample(3)), outbox.drain(), "not acknowledged: back for whoever sends next")
    }

    @Test
    fun theReportGoesUntilAcknowledgedAndSightingsOnce() = runTest {
        val server = FakeLiveServer()
        var report = DeviceReport(Platform.ANDROID, BluetoothState.ON)
        var heard = listOf(NearbySighting("0a1b2c3d", -60, 4_000))
        val extras = {
            SyncExtras(heard, report).also { heard = emptyList() }
        }
        connection(server).connect(testSession, LocationOutbox(), extras = extras).test {
            val socket = server.awaitSocket(0)
            val first = socket.awaitUp()
            assertNotNull(first.device)
            assertEquals(1, first.sightings.size)
            // Refused: not acknowledged, so the report goes again; the sightings don't.
            socket.refuse(first, ApiError(ErrorCode.INTERNAL, "Internal error"))
            val refused = assertIs<ConnectionEvent.Problem>(awaitItem())
            assertEquals(500, assertIs<ApiException>(refused.error).status)
            val second = socket.awaitUp()
            assertNotNull(second.device)
            assertTrue(second.sightings.isEmpty())
            socket.answer(second, seeking)
            awaitSnapshot()

            assertNull(socket.awaitUp().device, "acknowledged, unchanged")
            socket.answer(socket.lastUp, seeking)
            awaitSnapshot()
            report = report.copy(bluetooth = BluetoothState.OFF)
            assertNotNull(socket.awaitUp().device, "changed")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aPushBringsTheStateWithoutAnUpAndAChatPushOnlyTheChat() = runTest {
        val server = FakeLiveServer()
        connection(server).connect(testSession, LocationOutbox()).test {
            val socket = server.awaitSocket(0)
            socket.answer(socket.awaitUp(), seeking)
            awaitSnapshot()
            val answeredAt = currentTime

            testScheduler.advanceTimeBy(1_000)
            val glowing = seeking.copy(serverTimeMillis = 6_000, players = listOf(testPlayer, seeker))
            assertTrue(socket.push(glowing))
            assertEquals(asV2Shows(glowing), awaitSnapshot().snapshot)
            assertFalse(socket.push(glowing.copy(serverTimeMillis = 6_100)), "nothing new: no push")

            socket.pushChat(listOf(testMessage(4)), serverTimeMillis = 6_200)
            assertEquals(listOf(4L), assertIs<ConnectionEvent.Chat>(awaitItem()).messages.map { it.seq })
            expectNoEvents()

            // The pause goes on: pushes are no reason for an Up.
            socket.awaitUp()
            assertEquals(3_000, currentTime - answeredAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aStateWhoseBaseIsGoneAsksAtOnceForAWholeOne() = runTest {
        val server = FakeLiveServer()
        connection(server).connect(testSession, LocationOutbox()).test {
            val socket = server.awaitSocket(0)
            socket.answer(socket.awaitUp(), seeking)
            awaitSnapshot()
            val at = currentTime
            socket.send(Down(server_time_ms = 6_000, state_id = 9, base_state_id = 7))
            val up = socket.awaitUp()
            assertEquals(at, currentTime, "no pause")
            assertTrue(up.full)
            socket.answer(up, seeking.copy(serverTimeMillis = 7_000))
            assertEquals(7_000, awaitSnapshot().snapshot.serverTimeMillis)
            assertFalse(socket.awaitUp().full)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aRefusedUpWaitsAsTheServerSays() = runTest {
        val server = FakeLiveServer()
        connection(server).connect(testSession, LocationOutbox()).test {
            val socket = server.awaitSocket(0)
            val tooMany = ApiError(ErrorCode.BAD_REQUEST, "Too many Ups", ErrorReason.TOO_MANY_REQUESTS)
            socket.refuse(socket.awaitUp(), tooMany, retryAfterSeconds = 4)
            val problem = assertIs<ConnectionEvent.Problem>(awaitItem())
            assertEquals(429, assertIs<ApiException>(problem.error).status)
            assertEquals(4_000, problem.retryInMillis)
            val at = currentTime
            socket.awaitUp()
            assertEquals(4_000, currentTime - at)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theCloseCodesSayWhatNext() = runTest {
        for ((code, reason) in listOf(
            SocketClose.SESSION_REJECTED to EndReason.SESSION_REJECTED,
            SocketClose.GAME_NOT_FOUND to EndReason.GAME_NOT_FOUND,
        )) {
            val server = FakeLiveServer()
            connection(server).connect(testSession, LocationOutbox()).test {
                server.awaitSocket(0).closeFromServer(code)
                assertEquals(ConnectionEvent.Ended(reason), awaitItem())
                awaitComplete()
            }
        }
        // A new live session.
        val reopened = FakeLiveServer()
        connection(reopened).connect(testSession, LocationOutbox()).test {
            reopened.awaitSocket(0).closeFromServer(LiveWs.CLOSE_REOPEN)
            reopened.awaitSocket(1)
            assertEquals(2, reopened.opens.size)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        // Back to v1.
        val off = FakeLiveServer()
        connection(off).connect(testSession, LocationOutbox()).test {
            off.awaitSocket(0).closeFromServer(SocketClose.POLL)
            val problem = assertIs<ConnectionEvent.Problem>(awaitItem())
            assertTrue(assertIs<LiveChannelException>(problem.error).fallbackToV1)
            cancelAndIgnoreRemainingEvents()
        }
        // A send into a WebSocket the server closed ends the session instead of the flow.
        val closed = FakeLiveServer()
        connection(closed).connect(testSession, LocationOutbox()).test {
            closed.awaitSocket(0).apply {
                sendThrowsCancellation = true
                closeFromServer(SocketClose.SESSION_REJECTED)
            }
            assertEquals(ConnectionEvent.Ended(EndReason.SESSION_REJECTED), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun theOpeningSaysWhatNext() = runTest {
        val off = FakeLiveServer().apply {
            openError = ApiException(404, ApiError(ErrorCode.NOT_FOUND, "Off", ErrorReason.FEATURE_DISABLED))
        }
        connection(off).connect(testSession, LocationOutbox()).test {
            val problem = assertIs<ConnectionEvent.Problem>(awaitItem())
            assertTrue(assertIs<LiveChannelException>(problem.error).fallbackToV1)
            assertEquals(LiveLimits.V1_AFTER_DISABLED_MILLIS, problem.retryInMillis)
            cancelAndIgnoreRemainingEvents()
        }
        val gone = FakeLiveServer().apply { openError = ApiException(404, ApiError(ErrorCode.NOT_FOUND, "No game")) }
        connection(gone).connect(testSession, LocationOutbox()).test {
            assertEquals(ConnectionEvent.Ended(EndReason.GAME_NOT_FOUND), awaitItem())
            awaitComplete()
        }
        val unreachable = FakeLiveServer().apply { refuseSockets = true }
        connection(unreachable).connect(testSession, LocationOutbox()).test {
            val problem = assertIs<ConnectionEvent.Problem>(awaitItem())
            assertFalse(assertIs<LiveChannelException>(problem.error).answered)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aSessionIsRenewedBeforeItExpires() = runTest {
        val server = FakeLiveServer().apply { sessionMillis = 10 * 60_000L }
        val lobby = testSnapshot(serverTimeMillis = 5_000)
        connection(server).connect(testSession, LocationOutbox()).test {
            val socket = server.awaitSocket(0)
            // The lobby rests between Ups, until the phone renews the session.
            while (true) socket.answer(socket.awaitUpOrNull() ?: break, lobby)
            val renewed = server.awaitSocket(1)
            assertEquals(2, server.opens.size)
            val renewAt = 10 * 60_000L - LiveLimits.REOPEN_BEFORE_MILLIS
            assertTrue(currentTime in renewAt..renewAt + LiveLimits.IDLE_UP_MILLIS, "five minutes before the end")
            val up = renewed.awaitUp()
            assertEquals(1, up.seq)
            assertEquals(0, up.ack_state, "a new session starts from a whole state")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aDownThatIsNoDownIsSkipped() = runTest {
        val server = FakeLiveServer()
        connection(server).connect(testSession, LocationOutbox()).test {
            val socket = server.awaitSocket(0)
            val up = socket.awaitUp()
            socket.sendBytes(byteArrayOf(0x0f, 0x7f, 0x01))
            socket.answer(up, seeking)
            awaitSnapshot()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private val seeker = app.hovanki.shared.protocol.PlayerView(PlayerId("p2"), "Bo", Role.SEEKER, PlayerStatus.ACTIVE)

    private fun TestScope.connection(server: FakeLiveServer): LiveGameConnection =
        LiveGameConnection(server, server, serverTime = { currentTime }, timeSource = testScheduler.timeSource)

    private suspend fun ReceiveTurbine<ConnectionEvent>.awaitSnapshot() =
        assertIs<ConnectionEvent.Snapshot>(awaitItem())

    /** What the phone shows of [snapshot] over v2: everybody heard from lately is seen at the Down's time (§9.1). */
    private fun asV2Shows(snapshot: GameSnapshot) = snapshot.copy(
        players = snapshot.players.map { player ->
            val seen = player.lastSeenMillis
            player.copy(
                lastSeenMillis =
                seen?.takeIf { snapshot.serverTimeMillis - it > LiveLimits.OFFLINE_AFTER_MILLIS }
                    ?: snapshot.serverTimeMillis,
            )
        },
    )
}
