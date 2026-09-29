package app.hovanki.server.live

import app.hovanki.server.features.FeatureFlags
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ClientFrame
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.RolesRequest
import app.hovanki.shared.protocol.SendChatRequest
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.ServerFrame
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.SocketClose
import app.hovanki.shared.protocol.SocketLimits
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.shrinkingZone
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The game's live channel on a real server (docs/adr/0015-websockets.md): who may open it, a sync over it is the sync
 * of `POST /sync`, the pokes, and the close codes the app acts on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameSocketTest(
    @LocalServerPort private val port: Int,
    @Autowired private val features: FeatureFlags,
    @Autowired private val sockets: GameSockets,
    @Autowired private val clock: Clock,
) {
    private val http = HttpClient.newHttpClient()
    private val park = GeoPoint(50.4501, 30.5234)
    private val settings = GameSettings(zone = shrinkingZone(park, steps = 0), hidingSeconds = 60, seekingSeconds = 600)

    @BeforeEach
    fun liveChannelOn() = setLive(true)

    /** The tests share the database: the switch goes back off for the others. */
    @AfterEach
    fun liveChannelOff() = setLive(false)

    private fun setLive(on: Boolean) {
        if (features.isEnabled(ServerFeature.LIVE_SOCKET) != on) {
            features.set(ServerFeature.LIVE_SOCKET, on, "test", clock.instant())
        }
    }

    @Test
    fun aSyncOverTheSocketIsTheSyncOfPost() {
        val host = create()
        val socket = open(host.session)
        val fix = LocationSample(park, accuracyMeters = 5.0, timestampMillis = clock.millis())
        socket.send(ClientFrame.Sync(1, SyncRequest(listOf(fix), chatAfter = 0)))
        val answer = assertIs<ServerFrame.Snapshot>(socket.next())
        assertEquals(1, answer.seq)
        val posted = post<GameSnapshot>(ApiRoutes.sync(host.session.gameId), SyncRequest(chatAfter = 0), host.session)
        assertEquals(posted.withoutTimes(), answer.snapshot.withoutTimes())
        assertEquals(listOf(ServerFeature.LIVE_SOCKET.name), answer.snapshot.enabledFeatures)

        // A refused sync is an error frame; the socket stays.
        val tooMany = List(101) { fix }
        socket.send(ClientFrame.Sync(2, SyncRequest(tooMany)))
        val error = assertIs<ServerFrame.Error>(socket.next())
        assertEquals(2, error.seq)
        assertEquals(ErrorCode.BAD_REQUEST, error.error.code)
        // A frame of a newer app is skipped.
        socket.sendText("""{"type":"hello","seq":3}""")
        socket.send(ClientFrame.Sync(4, SyncRequest()))
        assertEquals(4, assertIs<ServerFrame.Snapshot>(socket.next()).seq)
        socket.close()
    }

    @Test
    fun onlyAPlayerOfTheGameWithTheServerOnGetsIn() {
        val host = create()
        val other = create()
        assertEquals(SocketClose.SESSION_REJECTED, open(host.session, token = null).awaitClose())
        assertEquals(SocketClose.SESSION_REJECTED, open(host.session, token = "nope").awaitClose())
        // The token of another game.
        assertEquals(SocketClose.SESSION_REJECTED, open(host.session, token = other.session.token).awaitClose())

        setLive(false)
        assertEquals(SocketClose.POLL, open(host.session).awaitClose())
        setLive(true)
        val socket = open(host.session)
        socket.send(ClientFrame.Sync(1, SyncRequest()))
        assertIs<ServerFrame.Snapshot>(socket.next())
        // Switched off while open: the next sync is told to poll.
        setLive(false)
        socket.send(ClientFrame.Sync(2, SyncRequest()))
        assertEquals(SocketClose.POLL, socket.awaitClose())
    }

    @Test
    fun aPlayerWhoLeftOrAGoneGameIsClosed() {
        val host = create()
        val guest = join(host)
        val guestSocket = open(guest.session)
        post<Unit>(ApiRoutes.leave(host.session.gameId), null, guest.session)
        guestSocket.send(ClientFrame.Sync(1, SyncRequest()))
        assertEquals(SocketClose.SESSION_REJECTED, guestSocket.awaitClose())

        val hostSocket = open(host.session)
        // The last player leaves the lobby: the game is gone, and with it the token.
        post<Unit>(ApiRoutes.leave(host.session.gameId), null, host.session)
        hostSocket.send(ClientFrame.Sync(1, SyncRequest()))
        val code = hostSocket.awaitClose()
        assertTrue(code == SocketClose.SESSION_REJECTED || code == SocketClose.GAME_NOT_FOUND, "closed with $code")
    }

    @Test
    fun whatAnotherPlayerDidPokesThePhone() {
        val host = create()
        val guest = join(host)
        val guestSocket = open(guest.session)
        val hostSocket = open(host.session)
        guestSocket.send(ClientFrame.Sync(1, SyncRequest(chatAfter = 0)))
        assertIs<ServerFrame.Snapshot>(guestSocket.next())

        // The host picks the roles: the guest is poked, the host has the answer already.
        post<GameSnapshot>(
            ApiRoutes.roles(host.session.gameId),
            RolesRequest(listOf(guest.session.playerId)),
            host.session,
        )
        assertEquals(ServerFrame.Poke, guestSocket.next())
        assertNull(hostSocket.poll(300), "the one who did it is not poked")
        guestSocket.send(ClientFrame.Sync(2, SyncRequest(chatAfter = 0)))
        val seen = assertIs<ServerFrame.Snapshot>(guestSocket.next()).snapshot
        assertEquals(guest.session.playerId, seen.players.single { it.role.name == "SEEKER" }.id)

        // A chat message pokes those who can read it.
        post<GameSnapshot>(ApiRoutes.chat(host.session.gameId), SendChatRequest("ready?", chatAfter = 0), host.session)
        assertEquals(ServerFrame.Poke, guestSocket.next())
        // A burst of changes is one poke per gap.
        repeat(3) {
            post<GameSnapshot>(
                ApiRoutes.chat(host.session.gameId),
                SendChatRequest("go $it", chatAfter = 0),
                host.session,
            )
        }
        assertEquals(ServerFrame.Poke, guestSocket.next(timeoutMillis = 2_000))
        assertNull(guestSocket.poll(SocketLimits.POKE_GAP_MILLIS * 2), "the burst came as one poke")
        guestSocket.close()
        hostSocket.close()
    }

    @Test
    fun theEndOfHidingPokesWithoutAnyRequest() {
        val host = create(settings.copy(hidingSeconds = 1))
        val guest = join(host)
        post<GameSnapshot>(
            ApiRoutes.start(host.session.gameId),
            StartGameRequest(listOf(host.session.playerId)),
            host.session,
        )
        val guestSocket = open(guest.session)
        guestSocket.send(ClientFrame.Sync(1, SyncRequest()))
        assertEquals(GamePhase.HIDING, assertIs<ServerFrame.Snapshot>(guestSocket.next()).snapshot.phase)
        // Nobody asks: the game wakes itself when the hiding is over.
        assertEquals(ServerFrame.Poke, guestSocket.next(timeoutMillis = 3_000))
        guestSocket.send(ClientFrame.Sync(2, SyncRequest()))
        assertEquals(GamePhase.SEEKING, assertIs<ServerFrame.Snapshot>(guestSocket.next()).snapshot.phase)
        guestSocket.close()
    }

    @Test
    fun aSocketThatSyncsTooOftenIsClosed() {
        val host = create()
        val socket = open(host.session)
        repeat(SocketLimits.MAX_SYNCS_PER_WINDOW) {
            socket.send(ClientFrame.Sync(it + 1L, SyncRequest()))
            assertIs<ServerFrame.Snapshot>(socket.next())
        }
        socket.send(ClientFrame.Sync(99, SyncRequest()))
        assertEquals(SocketClose.TOO_MANY, socket.awaitClose())
    }

    @Test
    fun aPlayerHasAFewSocketsAtMost() {
        val host = create()
        val first = open(host.session)
        val others = List(SocketLimits.MAX_SOCKETS_PER_PLAYER) { open(host.session) }
        assertEquals(SocketClose.TOO_MANY, first.awaitClose(), "the oldest goes")
        for (socket in others) {
            socket.send(ClientFrame.Sync(1, SyncRequest()))
            assertIs<ServerFrame.Snapshot>(socket.next())
            socket.close()
        }
        eventually { sockets.count(host.session.gameId) == 0 }
    }

    private fun GameSnapshot.withoutTimes() =
        copy(serverTimeMillis = 0, players = players.map { it.copy(lastSeenMillis = null) })

    // ---- helpers ----

    private fun create(settings: GameSettings = this.settings): SessionResponse =
        post(ApiRoutes.GAMES, CreateGameRequest("Host", settings), session = null)

    private fun join(host: SessionResponse): SessionResponse =
        post(ApiRoutes.JOIN, JoinGameRequest(host.snapshot.joinCode, "Guest"), session = null)

    private inline fun <reified T> post(path: String, body: Any?, session: PlayerSession?): T {
        val json = when (body) {
            null -> ""
            is CreateGameRequest -> protocolJson.encodeToString(CreateGameRequest.serializer(), body)
            is JoinGameRequest -> protocolJson.encodeToString(JoinGameRequest.serializer(), body)
            is SyncRequest -> protocolJson.encodeToString(SyncRequest.serializer(), body)
            is RolesRequest -> protocolJson.encodeToString(RolesRequest.serializer(), body)
            is SendChatRequest -> protocolJson.encodeToString(SendChatRequest.serializer(), body)
            is StartGameRequest -> protocolJson.encodeToString(StartGameRequest.serializer(), body)
            else -> error("No serializer for $body")
        }
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .apply { if (session != null) header("Authorization", "${ApiRoutes.AUTH_SCHEME} ${session.token}") }
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertTrue(response.statusCode() in 200..299, "$path: ${response.statusCode()} ${response.body()}")
        return if (T::class == Unit::class) Unit as T else protocolJson.decodeFromString<T>(response.body())
    }

    private fun open(session: PlayerSession, token: String? = session.token): TestSocket {
        val listener = TestSocket()
        http.newWebSocketBuilder()
            .apply { if (token != null) header("Authorization", "${ApiRoutes.AUTH_SCHEME} $token") }
            .buildAsync(URI("ws://127.0.0.1:$port${ApiRoutes.socket(session.gameId)}"), listener)
            .get(5, TimeUnit.SECONDS)
        return listener
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Not within 5 s")
            Thread.sleep(20)
        }
    }

    /** A socket as the tests drive it: frames in a queue, the close code once closed. */
    private class TestSocket : WebSocket.Listener {
        private val frames = LinkedBlockingQueue<String>()
        private val closeCode = CompletableFuture<Int>()
        private val text = StringBuilder()
        private lateinit var socket: WebSocket

        override fun onOpen(webSocket: WebSocket) {
            socket = webSocket
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            text.append(data)
            if (last) {
                frames += text.toString()
                text.clear()
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            closeCode.complete(statusCode)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            closeCode.complete(-1)
        }

        fun send(frame: ClientFrame) = sendText(protocolJson.encodeToString(ClientFrame.serializer(), frame))

        fun sendText(text: String) {
            socket.sendText(text, true).get(5, TimeUnit.SECONDS)
        }

        fun next(timeoutMillis: Long = 5_000): ServerFrame =
            assertNotNull(poll(timeoutMillis), "no frame within $timeoutMillis ms")

        fun poll(timeoutMillis: Long): ServerFrame? = frames.poll(timeoutMillis, TimeUnit.MILLISECONDS)?.let {
            protocolJson.decodeFromString(ServerFrame.serializer(), it)
        }

        fun awaitClose(): Int = closeCode.get(5, TimeUnit.SECONDS)

        fun close() {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS)
        }
    }
}
