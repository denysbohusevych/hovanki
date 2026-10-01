package app.hovanki.client.network

import app.cash.turbine.test
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ClientFrame
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.ServerFrame
import app.hovanki.shared.protocol.SocketClose
import app.hovanki.shared.protocol.SocketLimits
import app.hovanki.shared.protocol.protocolJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The live channel's connection (docs/adr/0015-websockets.md, section 4) on a fake socket, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class WebSocketGameConnectionTest {
    @Test
    fun aSyncIsAFrameAndItsAnswerTheSnapshot() = runTest {
        val server = FakeSocketServer()
        val outbox = LocationOutbox().apply { add(testSample(1)) }
        connection(server).connect(testSession, outbox, chatAfter = { 7 }).test {
            val socket = server.awaitSocket(0)
            val sync = socket.awaitSync()
            assertEquals(1, sync.seq)
            assertEquals(listOf(testSample(1)), sync.request.samples)
            assertEquals(7, sync.request.chatAfter)
            socket.answer(sync, testSnapshot(serverTimeMillis = 5, syncIntervalSeconds = 3, phase = GamePhase.SEEKING))
            val event = assertIs<ConnectionEvent.Snapshot>(awaitItem())
            assertEquals(5, event.snapshot.serverTimeMillis)
            // The size of the answer's text frame, for the field log.
            val frame = ServerFrame.Snapshot(sync.seq, event.snapshot)
            val frameText = app.hovanki.shared.protocol.protocolJson.encodeToString(ServerFrame.serializer(), frame)
            assertEquals(frameText.encodeToByteArray().size, event.bytes)
            assertEquals(Transport.SOCKET, event.transport)
            assertEquals(0, outbox.size)

            // The next one after the game's pace.
            val before = currentTime
            assertEquals(2, socket.awaitSync().seq)
            assertEquals(3_000, currentTime - before)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun anOpeningThatHangsIsGivenUpOn() = runTest {
        val hanging = GameSocketOpener { awaitCancellation() }
        connection(hanging).connect(testSession, LocationOutbox()).test {
            val problem = assertIs<ConnectionEvent.Problem>(awaitItem())
            val error = assertIs<GameSocketException>(problem.error)
            assertFalse(error.answered)
            assertEquals(SocketLimits.OPEN_TIMEOUT_MILLIS, currentTime)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aSendIntoASocketTheServerClosedEndsTheSessionInsteadOfTheFlow() = runTest {
        val socket = FakeGameSocket().apply {
            sendThrowsCancellation = true
            closeFromServer(SocketClose.SESSION_REJECTED)
        }
        connection({ socket }).connect(testSession, LocationOutbox().apply { add(testSample(1)) }).test {
            val ended = assertIs<ConnectionEvent.Ended>(awaitItem())
            assertEquals(EndReason.SESSION_REJECTED, ended.reason)
            awaitComplete()
        }
    }

    @Test
    fun aPokeCutsThePauseShort() = runTest {
        val server = FakeSocketServer()
        connection(server).connect(testSession, LocationOutbox()).test {
            val socket = server.awaitSocket(0)
            socket.answer(socket.awaitSync(), testSnapshot(syncIntervalSeconds = 3, phase = GamePhase.SEEKING))
            awaitItem()
            val answeredAt = currentTime
            testScheduler.advanceTimeBy(1_000)
            socket.send(ServerFrame.Poke)
            assertEquals(2, socket.awaitSync().seq)
            assertEquals(1_000, currentTime - answeredAt, "right after the poke")

            // A poke right after a sync waits a moment: the answer was that fresh.
            socket.answer(socket.lastSync, testSnapshot(syncIntervalSeconds = 3, phase = GamePhase.SEEKING))
            awaitItem()
            val sentAt = currentTime
            socket.send(ServerFrame.Poke)
            socket.awaitSync()
            assertEquals(SocketLimits.SYNC_AFTER_POKE_GAP_MILLIS, currentTime - sentAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aPokeDuringASyncMeansAnotherSyncSoon() = runTest {
        val server = FakeSocketServer()
        connection(server).connect(testSession, LocationOutbox()).test {
            val socket = server.awaitSocket(0)
            val sync = socket.awaitSync()
            val sentAt = currentTime
            // The poke overtakes the answer, which may be older than what it pokes about.
            socket.send(ServerFrame.Poke)
            socket.answer(sync, testSnapshot(syncIntervalSeconds = 3, phase = GamePhase.SEEKING))
            awaitItem()
            socket.awaitSync()
            assertEquals(SocketLimits.SYNC_AFTER_POKE_GAP_MILLIS, currentTime - sentAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theLobbyRestsLongerOnTheSocket() = runTest {
        val server = FakeSocketServer()
        connection(server).connect(testSession, LocationOutbox()).test {
            val socket = server.awaitSocket(0)
            socket.answer(socket.awaitSync(), testSnapshot(syncIntervalSeconds = 3, phase = GamePhase.LOBBY))
            awaitItem()
            val before = currentTime
            socket.awaitSync()
            assertEquals(WebSocketGameConnection.QUIET_INTERVAL_MILLIS, currentTime - before)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun samplesWithoutAnAnswerGoBackAndTheSocketOpensAgain() = runTest {
        val server = FakeSocketServer()
        val outbox = LocationOutbox().apply { add(testSample(1)) }
        connection(server).connect(testSession, outbox).test {
            val socket = server.awaitSocket(0)
            assertEquals(listOf(testSample(1)), socket.awaitSync().request.samples)
            socket.closeFromServer(code = null)
            val problem = assertIs<ConnectionEvent.Problem>(awaitItem())
            val error = assertIs<GameSocketException>(problem.error)
            assertFalse(error.answered)
            assertEquals(1, outbox.size, "back in the outbox")

            val again = server.awaitSocket(1)
            assertEquals(listOf(testSample(1)), again.awaitSync().request.samples)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun noAnswerInTimeIsADeadSocket() = runTest {
        val server = FakeSocketServer()
        connection(server).connect(testSession, LocationOutbox()).test {
            val socket = server.awaitSocket(0)
            socket.answer(socket.awaitSync(), testSnapshot(phase = GamePhase.SEEKING))
            awaitItem()
            socket.awaitSync()
            val problem = assertIs<ConnectionEvent.Problem>(awaitItem())
            assertTrue(assertIs<GameSocketException>(problem.error).answered)
            assertTrue(socket.isClosedByApp)
            server.awaitSocket(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theServersCloseCodesEndTheSession() = runTest {
        for ((code, reason) in listOf(
            SocketClose.SESSION_REJECTED to EndReason.SESSION_REJECTED,
            SocketClose.GAME_NOT_FOUND to EndReason.GAME_NOT_FOUND,
        )) {
            val server = FakeSocketServer()
            connection(server).connect(testSession, LocationOutbox()).test {
                val socket = server.awaitSocket(0)
                socket.awaitSync()
                socket.closeFromServer(code)
                assertEquals(ConnectionEvent.Ended(reason), awaitItem())
                awaitComplete()
            }
        }
        // «Poll instead» is a problem for the adaptive connection to act on.
        val server = FakeSocketServer()
        connection(server).connect(testSession, LocationOutbox()).test {
            val socket = server.awaitSocket(0)
            socket.awaitSync()
            socket.closeFromServer(SocketClose.POLL)
            val error = assertIs<GameSocketException>(assertIs<ConnectionEvent.Problem>(awaitItem()).error)
            assertEquals(SocketClose.POLL, error.code)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aRefusedSyncKeepsTheSocketAndDropsItsSamples() = runTest {
        val server = FakeSocketServer()
        val outbox = LocationOutbox().apply { add(testSample(1)) }
        connection(server).connect(testSession, outbox).test {
            val socket = server.awaitSocket(0)
            val sync = socket.awaitSync()
            socket.send(ServerFrame.Error(sync.seq, ApiError(ErrorCode.BAD_REQUEST, "Too many samples")))
            val problem = assertIs<ConnectionEvent.Problem>(awaitItem())
            assertEquals(400, assertIs<ApiException>(problem.error).status)
            assertEquals(0, outbox.size, "the same samples would be refused again")
            val before = currentTime
            socket.awaitSync()
            assertEquals(problem.retryInMillis, currentTime - before)
            assertEquals(1, server.opened.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aSocketThatDoesNotOpenIsAProblem() = runTest {
        val server = FakeSocketServer(refuse = true)
        connection(server).connect(testSession, LocationOutbox()).test {
            val error = assertIs<GameSocketException>(assertIs<ConnectionEvent.Problem>(awaitItem()).error)
            assertFalse(error.answered)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aFrameOfANewerServerIsSkipped() = runTest {
        val server = FakeSocketServer()
        connection(server).connect(testSession, LocationOutbox()).test {
            val socket = server.awaitSocket(0)
            val sync = socket.awaitSync()
            socket.sendText("""{"type":"delta","seq":1}""")
            socket.answer(sync, testSnapshot(serverTimeMillis = 9))
            assertEquals(9, assertIs<ConnectionEvent.Snapshot>(awaitItem()).snapshot.serverTimeMillis)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun TestScope.connection(server: GameSocketOpener) =
        WebSocketGameConnection(server, testScheduler.timeSource)
}

/** The server side of fake sockets: every [open] makes one, [refuse] makes it fail. */
class FakeSocketServer(var refuse: Boolean = false) : GameSocketOpener {
    val opened = mutableListOf<FakeGameSocket>()
    private val openings = Channel<FakeGameSocket>(Channel.UNLIMITED)

    override suspend fun open(session: PlayerSession): GameSocket {
        if (refuse) throw SocketUnreachable("No upgrade")
        return FakeGameSocket().also {
            opened += it
            openings.send(it)
        }
    }

    /** The [index]th socket opened, waiting for it. */
    suspend fun awaitSocket(index: Int): FakeGameSocket {
        while (opened.size <= index) openings.receive()
        return opened[index]
    }
}

class SocketUnreachable(message: String) : Exception(message)

class FakeGameSocket : GameSocket {
    private val toApp = Channel<String>(Channel.UNLIMITED)
    private val fromApp = Channel<String>(Channel.UNLIMITED)
    private var code: Int? = null
    var isClosedByApp = false
        private set
    lateinit var lastSync: ClientFrame.Sync
        private set

    override suspend fun receive(): String? = toApp.receiveCatching().getOrNull()

    /** Like Ktor's send into a session the server closed: a CancellationException nobody cancelled with. */
    var sendThrowsCancellation: Boolean = false

    override suspend fun send(text: String) {
        if (sendThrowsCancellation) throw CancellationException("WebSocket session closed with code 4401.")
        if (toApp.isClosedForSend) throw SocketUnreachable("Closed")
        fromApp.send(text)
    }

    override suspend fun closeCode(): Int? = code

    override suspend fun close() {
        isClosedByApp = true
        toApp.close()
    }

    suspend fun awaitSync(): ClientFrame.Sync {
        val frame = protocolJson.decodeFromString(ClientFrame.serializer(), fromApp.receive())
        return assertIs<ClientFrame.Sync>(frame).also { lastSync = it }
    }

    fun send(frame: ServerFrame) = sendText(protocolJson.encodeToString(ServerFrame.serializer(), frame))

    fun sendText(text: String) {
        toApp.trySend(text)
    }

    fun answer(sync: ClientFrame.Sync, snapshot: GameSnapshot) = send(ServerFrame.Snapshot(sync.seq, snapshot))

    fun closeFromServer(code: Int?) {
        this.code = code
        toApp.close()
    }
}
