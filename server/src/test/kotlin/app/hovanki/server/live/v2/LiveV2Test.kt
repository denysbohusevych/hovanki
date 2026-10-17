package app.hovanki.server.live.v2

import app.hovanki.server.features.FeatureFlags
import app.hovanki.shared.live.LiveClientStates
import app.hovanki.shared.live.LiveDatagrams
import app.hovanki.shared.live.LiveDownlink
import app.hovanki.shared.live.LiveLimits
import app.hovanki.shared.live.LiveSnapshots
import app.hovanki.shared.live.LiveUplink
import app.hovanki.shared.live.LiveValues
import app.hovanki.shared.live.v2.CloseReason
import app.hovanki.shared.live.v2.Down
import app.hovanki.shared.live.v2.Up
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.LiveOpenRequest
import app.hovanki.shared.protocol.LiveOpenResponse
import app.hovanki.shared.protocol.LiveWs
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.RolesRequest
import app.hovanki.shared.protocol.SendChatRequest
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.SocketClose
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.shrinkingZone
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.socket.CloseStatus
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Clock
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import app.hovanki.shared.live.v2.ErrorCode as WireErrorCode

/**
 * The live channel v2 over its WebSocket on a real server (docs/live-v2/spec.md §4, §6–8, §11, §13): who may open a
 * session, an Up is v1's sync and its Down rebuilds v1's snapshot, deltas, pushes and chat, and the close codes the app
 * acts on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveV2Test(
    @LocalServerPort private val port: Int,
    @Autowired private val features: FeatureFlags,
    @Autowired private val sessions: LiveSessions,
    @Autowired private val clock: Clock,
    @Autowired private val meters: MeterRegistry,
) {
    private val http = HttpClient.newHttpClient()
    private val park = GeoPoint(50.4501, 30.5234)
    private val settings = GameSettings(zone = shrinkingZone(park, steps = 0), hidingSeconds = 60, seekingSeconds = 600)

    @BeforeEach
    fun liveV2On() = setV2(true)

    /** The tests share the database: the switch goes back off for the others. */
    @AfterEach
    fun liveV2Off() = setV2(false)

    private fun setV2(on: Boolean) {
        if (features.isEnabled(ServerFeature.LIVE_V2) != on) {
            features.set(ServerFeature.LIVE_V2, on, "test", clock.instant())
        }
    }

    @Test
    fun onlyAPlayerOpensASessionAndOnlyWhileV2IsOn() {
        val host = create()
        setV2(false)
        val off = postRaw(ApiRoutes.LIVE_OPEN, "", host.session.token)
        assertEquals(404, off.statusCode())
        assertEquals(ErrorReason.FEATURE_DISABLED, protocolJson.decodeFromString<ApiError>(off.body()).reason)
        setV2(true)
        assertEquals(401, postRaw(ApiRoutes.LIVE_OPEN, "", token = null).statusCode())
        assertEquals(401, postRaw(ApiRoutes.LIVE_OPEN, "", token = "nope").statusCode())
        assertEquals(400, postRaw(ApiRoutes.LIVE_OPEN, "{not json", host.session.token).statusCode())

        val opened = open(host.session)
        assertNotNull(LiveDatagrams.sessionIdOf(opened.sessionId))
        assertEquals(16, Base64.getDecoder().decode(opened.upKey).size)
        assertEquals(16, Base64.getDecoder().decode(opened.downKey).size)
        assertTrue(opened.upKey != opened.downKey)
        assertEquals(16, Base64.getDecoder().decode(opened.resetToken).size)
        assertNull(opened.udp, "UDP off (LIVE_UDP): the WebSocket v2 only")
        assertEquals(ApiRoutes.LIVE_WS, opened.wsPath)
        val age = opened.expiresAtMillis - opened.serverTimeMillis
        assertTrue(age in LiveLimits.SESSION_MAX_AGE_MILLIS - 1_000..LiveLimits.SESSION_MAX_AGE_MILLIS, "$age")
        // Every open is a new session with new keys; a body from a newer app is fine.
        val again = protocolJson.decodeFromString<LiveOpenResponse>(
            postRaw(
                ApiRoutes.LIVE_OPEN,
                """{"appVersion":"9.9","transports":["ws","quic"],"newer":1}""",
                host.session.token,
            ).body(),
        )
        assertTrue(again.sessionId != opened.sessionId && again.upKey != opened.upKey)
    }

    @Test
    fun anUpIsTheSyncOfPostAndTheDownRebuildsItsSnapshot() {
        val syncsBefore = syncsTimed("ws2")
        val host = create()
        val phone = Phone(host.session)
        assertEquals(LiveWs.SUBPROTOCOL, phone.ws.subprotocol)
        val fix = LocationSample(park, accuracyMeters = 5.0, timestampMillis = clock.millis())
        post<GameSnapshot>(ApiRoutes.chat(host.session.gameId), SendChatRequest("hi", chatAfter = 0), host.session)

        val first = phone.exchange(samples = listOf(fix), chatAfter = 0)
        assertEquals(1 to 1, first.reply_to to first.ack_up)
        assertEquals(1 to 0, first.state_id to first.base_state_id, "the first state is full")
        val posted = post<GameSnapshot>(ApiRoutes.sync(host.session.gameId), SyncRequest(chatAfter = 0), host.session)
        assertEquals(comparable(posted), comparable(phone.shown))
        assertEquals(listOf("hi"), phone.shown.chat.map { it.text })
        assertTrue(ServerFeature.LIVE_V2.name in phone.shown.enabledFeatures)

        // Nothing changed since the state the phone has: a Down without a state.
        val second = phone.exchange(chatAfter = phone.shown.chat.last().seq)
        assertEquals(2 to 0, second.reply_to to second.state_id)
        assertNull(second.state)
        assertTrue(second.chat.isEmpty(), "no message newer than the cursor")
        assertEquals(2, syncsTimed("ws2") - syncsBefore, "hovanki.game.sync{transport=ws2}")
        phone.close()
    }

    @Test
    fun aRefusedUpIsAnErrorAndTheSessionStays() {
        val host = create()
        val phone = Phone(host.session)
        val fix = LocationSample(park, accuracyMeters = 5.0, timestampMillis = clock.millis())
        val tooMany = Up(seq = 1, time_ms = clock.millis(), fixes = LiveUplink.fixes(List(101) { fix }, clock.millis()))
        val refused = phone.send(tooMany).next()
        assertEquals(1, refused.reply_to)
        assertEquals(0, refused.ack_up, "a refused Up is not processed: its fixes go again")
        assertEquals(WireErrorCode.ERROR_CODE_BAD_REQUEST, refused.error?.code)
        // Garbage is skipped; an Up overtaken by a later one gets no answer of its own.
        phone.sendBytes(byteArrayOf(0x7f, 0x7f, 0x7f))
        assertEquals(2, phone.send(Up(seq = 2, time_ms = clock.millis())).next().reply_to)
        phone.send(Up(seq = 2, time_ms = clock.millis()))
        assertEquals(3, phone.send(Up(seq = 3, time_ms = clock.millis())).next().reply_to)

        // Over the pace: an error with when to come back, the session stays.
        repeat(LiveLimits.MAX_UPS_PER_WINDOW - 3) { phone.send(Up(seq = 4 + it, time_ms = clock.millis())).next() }
        val fast = phone.send(Up(seq = 100, time_ms = clock.millis())).next()
        assertEquals(ErrorReason.TOO_MANY_REQUESTS.name, fast.error?.reason)
        assertTrue(fast.error!!.retry_after_s in 1..6, "${fast.error}")
        assertTrue(phone.isOpen)
        phone.close()
    }

    @Test
    fun whatAnotherPlayerDidIsPushedAsADelta() {
        val host = create()
        val guest = join(host)
        val hostPhone = Phone(host.session)
        val guestPhone = Phone(guest.session)
        hostPhone.exchange()
        guestPhone.exchange()
        // The guest says it has state 1: the next push is a delta against it.
        assertEquals(0, guestPhone.exchange().state_id)

        post<GameSnapshot>(
            ApiRoutes.roles(host.session.gameId),
            RolesRequest(listOf(guest.session.playerId)),
            host.session,
        )
        val push = guestPhone.next()
        assertEquals(0, push.reply_to)
        assertEquals(1, push.base_state_id)
        assertTrue(push.state?.players?.upsert.orEmpty().isNotEmpty())
        guestPhone.apply(push)
        assertEquals(Role.SEEKER, guestPhone.shown.players.single { it.id == guest.session.playerId }.role)
        assertNull(hostPhone.poll(300), "the one who did it has the answer already")

        // A burst of changes: one push per gap, and the phone ends up with the last state.
        repeat(3) {
            post<GameSnapshot>(
                ApiRoutes.roles(host.session.gameId),
                RolesRequest(if (it % 2 == 0) emptyList() else listOf(guest.session.playerId)),
                host.session,
            )
        }
        val pushes = generateSequence { guestPhone.poll(LiveLimits.PUSH_GAP_MILLIS * 3) }.toList()
        assertTrue(pushes.size in 1..2, "${pushes.size} pushes")
        pushes.forEach(guestPhone::apply)
        val posted = post<GameSnapshot>(ApiRoutes.sync(guest.session.gameId), SyncRequest(), guest.session)
        assertEquals(comparable(posted), comparable(guestPhone.shown))
        hostPhone.close()
        guestPhone.close()
    }

    @Test
    fun aPushIsNoSignThatThePhoneIsThere() {
        val host = create()
        val guest = join(host)
        val guestPhone = Phone(guest.session)
        guestPhone.exchange()
        val lastUp = guestPhone.exchange().server_time_ms
        Thread.sleep(20)

        post<GameSnapshot>(
            ApiRoutes.roles(host.session.gameId),
            RolesRequest(listOf(guest.session.playerId)),
            host.session,
        )
        val push = guestPhone.next()
        assertEquals(0, push.reply_to)
        assertTrue(push.server_time_ms > lastUp)
        val hostView = post<GameSnapshot>(ApiRoutes.sync(host.session.gameId), SyncRequest(), host.session)
        assertEquals(
            lastUp,
            hostView.players.single { it.id == guest.session.playerId }.lastSeenMillis,
            "the guest was last seen at their last Up, not at the push (docs/live-v2/server.md §3.2)",
        )
        guestPhone.close()
    }

    @Test
    fun aChatMessageIsPushedItselfToWhoMayReadIt() {
        val host = create()
        val guest = join(host)
        val hostPhone = Phone(host.session)
        val guestPhone = Phone(guest.session)
        hostPhone.exchange(chatAfter = 0)
        guestPhone.exchange(chatAfter = 0)

        post<GameSnapshot>(ApiRoutes.chat(host.session.gameId), SendChatRequest("ready?", chatAfter = 0), host.session)
        val pushed = guestPhone.next()
        assertTrue(pushed.chat_push)
        assertEquals(0, pushed.state_id, "the chat alone: no state with it")
        assertEquals(listOf("ready?"), pushed.chat.map { it.text })
        assertEquals(host.session.playerId.value, pushed.chat.single().player_id)
        assertNull(guestPhone.poll(LiveLimits.PUSH_GAP_MILLIS * 2), "nothing else changed")
        assertNull(hostPhone.poll(0), "the sender has the answer")
        hostPhone.close()
        guestPhone.close()
    }

    @Test
    fun theWebSocketClosesWithACodeTheAppActsOn() {
        val host = create()
        val guest = join(host)
        val hostSession = open(host.session)
        val guestSession = open(guest.session)
        assertEquals(LiveWs.CLOSE_REOPEN, connect(host.session.token, sessionId = null).awaitClose())
        assertEquals(LiveWs.CLOSE_REOPEN, connect(host.session.token, LiveDatagrams.sessionIdText(42)).awaitClose())
        assertEquals(SocketClose.SESSION_REJECTED, connect("nope", hostSession.sessionId).awaitClose())
        // Another player's session is not this one's: a new one, never the end of the game for a good token.
        assertEquals(LiveWs.CLOSE_REOPEN, connect(guest.session.token, hostSession.sessionId).awaitClose())
        // No token at all: the upgrade lost it, v1 decides.
        assertEquals(SocketClose.POLL, connect(token = null, hostSession.sessionId).awaitClose())
        setV2(false)
        assertEquals(SocketClose.POLL, connect(host.session.token, hostSession.sessionId).awaitClose())
        setV2(true)

        // Switched off while open: the next Up goes back to v1.
        val hostPhone = Phone(host.session, hostSession)
        hostPhone.exchange()
        setV2(false)
        runCatching { hostPhone.send(Up(seq = 2, time_ms = clock.millis())) }
        assertEquals(SocketClose.POLL, hostPhone.awaitClose())
        setV2(true)

        // A player who left: their session is over.
        val guestPhone = Phone(guest.session, guestSession)
        guestPhone.exchange()
        post<Unit>(ApiRoutes.leave(host.session.gameId), null, guest.session)
        // Closed by the next Up, or already by the push of the change.
        runCatching { guestPhone.send(Up(seq = 2, time_ms = clock.millis())) }
        assertEquals(SocketClose.SESSION_REJECTED, guestPhone.awaitClose())
    }

    @Test
    fun theSweepEndsIdleSessionsAndOnesOfAServerWithV2Off() {
        val host = create()
        val idle = Phone(host.session)
        idle.exchange()
        sessions.sweepAt(clock.millis())
        assertTrue(idle.isOpen, "a session that just talked stays")
        sessions.sweepAt(clock.millis() + LiveLimits.SESSION_IDLE_MILLIS + 1_000)
        assertEquals(LiveWs.CLOSE_REOPEN, idle.awaitClose())

        val other = Phone(host.session)
        setV2(false)
        sessions.sweepAt(clock.millis())
        assertEquals(SocketClose.POLL, other.awaitClose())
    }

    @Test
    fun aPlayerHasAFewSessionsAndOpensAtMost() {
        val host = create()
        val oldest = Phone(host.session)
        repeat(LiveLimits.MAX_SESSIONS_PER_PLAYER) { open(host.session) }
        assertEquals(LiveWs.CLOSE_REOPEN, oldest.awaitClose(), "the oldest goes")
        repeat(LiveLimits.MAX_OPENS_PER_MINUTE - LiveLimits.MAX_SESSIONS_PER_PLAYER - 1) { open(host.session) }
        val tooMany = postRaw(ApiRoutes.LIVE_OPEN, "", host.session.token)
        assertEquals(429, tooMany.statusCode())
        assertTrue(tooMany.headers().firstValue("Retry-After").isPresent)
    }

    @Test
    fun theSessionsOfAGameShareTheJsonOfItsSettings() {
        val host = create()
        val guest = join(host)
        val elsewhere = create()
        val (first, second, third) =
            listOf(host, guest, elsewhere).map { assertNotNull(sessions.get(open(it.session).sessionId)) }
        assertSame(first.json.game, second.json.game, "one game, its settings' JSON once")
        assertNotSame(first.json.game, third.json.game)
        assertNotSame(first.json.items, second.json.items, "the board's items are each player's own")
        // The game's last session gone, the JSON goes with it: the next session starts anew.
        sessions.end(first, CloseReason.CLOSE_REOPEN)
        sessions.end(second, CloseReason.CLOSE_REOPEN)
        val again = assertNotNull(sessions.get(open(host.session).sessionId))
        assertNotSame(first.json.game, again.json.game)
    }

    @Test
    fun aNewLinkTakesTheSessionOverWithItsStates() {
        val host = create()
        val guest = join(host)
        val opened = open(guest.session)
        val first = Phone(guest.session, opened)
        first.exchange()
        val second = Phone(guest.session, opened, states = first)
        assertEquals(CloseStatus.NORMAL.code, first.awaitClose(), "the link before goes quietly")
        // The same session: a delta against the state the first link brought, and pushes come on the new link.
        val answer = second.exchange()
        assertEquals(2, answer.reply_to)
        post<GameSnapshot>(
            ApiRoutes.roles(host.session.gameId),
            RolesRequest(listOf(guest.session.playerId)),
            host.session,
        )
        val push = second.next()
        assertTrue(push.base_state_id != 0)
        second.apply(push)
        assertEquals(Role.SEEKER, second.shown.players.single { it.id == guest.session.playerId }.role)
        second.close()
    }

    @Test
    fun aLongUpComesInPiecesAndOneOverTheLimitClosesTheLink() {
        val host = create()
        val phone = Phone(host.session)
        val now = clock.millis()
        val fixes =
            List(100) { LocationSample(park, accuracyMeters = 5.0, timestampMillis = now - (100 - it) * 1_000L) }
        val up = LiveUplink.up(seq = 1, ackState = 0, timeMillis = now, samples = fixes, chatAfter = 0)
        phone.sendParts(Up.ADAPTER.encode(up).toList().chunked(100).map { it.toByteArray() })
        assertEquals(1, phone.next().reply_to)

        runCatching { phone.sendBytes(ByteArray(LiveLimits.MAX_WS_FRAME_BYTES + 1)) }
        assertEquals(CloseStatus.TOO_BIG_TO_PROCESS.code, phone.awaitClose())
    }

    @Test
    fun framesAreNeverCompressed() {
        val host = create()
        val opened = open(host.session)
        Socket("127.0.0.1", port).use { raw ->
            raw.soTimeout = 5_000
            val upgrade = listOf(
                "GET ${ApiRoutes.liveWs(opened.sessionId)} HTTP/1.1",
                "Host: 127.0.0.1:$port",
                "Upgrade: websocket",
                "Connection: Upgrade",
                "Sec-WebSocket-Version: 13",
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
                "Sec-WebSocket-Extensions: permessage-deflate; client_max_window_bits",
                "Sec-WebSocket-Protocol: ${LiveWs.SUBPROTOCOL}",
                "Authorization: ${ApiRoutes.AUTH_SCHEME} ${host.session.token}",
            ).joinToString("\r\n", postfix = "\r\n\r\n")
            raw.getOutputStream().apply {
                write(upgrade.toByteArray())
                flush()
            }
            val head = raw.getInputStream().bufferedReader().lineSequence().takeWhile { it.isNotEmpty() }.toList()
            assertTrue(head.first().startsWith("HTTP/1.1 101"), head.joinToString(" | "))
            assertTrue(head.none { it.startsWith("Sec-WebSocket-Extensions", ignoreCase = true) }, head.joinToString())
            assertTrue(head.any { it.equals("Sec-WebSocket-Protocol: ${LiveWs.SUBPROTOCOL}", ignoreCase = true) })
        }
    }

    private fun syncsTimed(transport: String): Long =
        meters.find("hovanki.game.sync").tag("transport", transport).timer()?.count() ?: 0

    /** What v2 promises of v1's snapshot (spec §9.1, §10): positions rounded; the times aside. */
    private fun comparable(snapshot: GameSnapshot) = snapshot.copy(
        serverTimeMillis = 0,
        chat = emptyList(),
        players = snapshot.players.map { player ->
            player.copy(
                lastSeenMillis = null,
                location = player.location?.let {
                    it.copy(
                        point = LiveValues.round(it.point),
                        accuracyMeters = LiveValues.roundAccuracy(it.accuracyMeters),
                    )
                },
            )
        },
    )

    // ---- helpers ----

    private fun create(): SessionResponse = post(ApiRoutes.GAMES, CreateGameRequest("Host", settings), session = null)

    private fun join(host: SessionResponse, name: String = "Guest"): SessionResponse =
        post(ApiRoutes.JOIN, JoinGameRequest(host.snapshot.joinCode, name), session = null)

    private fun open(session: PlayerSession): LiveOpenResponse {
        val body = protocolJson.encodeToString(LiveOpenRequest.serializer(), LiveOpenRequest(appVersion = "test"))
        val response = postRaw(ApiRoutes.LIVE_OPEN, body, session.token)
        assertEquals(200, response.statusCode(), response.body())
        return protocolJson.decodeFromString(response.body())
    }

    private fun postRaw(path: String, json: String, token: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .apply { if (token != null) header("Authorization", "${ApiRoutes.AUTH_SCHEME} $token") }
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private inline fun <reified T> post(path: String, body: Any?, session: PlayerSession?): T {
        val json = when (body) {
            null -> ""
            is CreateGameRequest -> protocolJson.encodeToString(CreateGameRequest.serializer(), body)
            is JoinGameRequest -> protocolJson.encodeToString(JoinGameRequest.serializer(), body)
            is SyncRequest -> protocolJson.encodeToString(SyncRequest.serializer(), body)
            is RolesRequest -> protocolJson.encodeToString(RolesRequest.serializer(), body)
            is SendChatRequest -> protocolJson.encodeToString(SendChatRequest.serializer(), body)
            else -> error("No serializer for $body")
        }
        val response = postRaw(path, json, session?.token)
        assertTrue(response.statusCode() in 200..299, "$path: ${response.statusCode()} ${response.body()}")
        return if (T::class == Unit::class) Unit as T else protocolJson.decodeFromString<T>(response.body())
    }

    /** A WebSocket v2 of [sessionId] with [token]. */
    private fun connect(token: String?, sessionId: String?): TestSocket {
        val listener = TestSocket()
        val path = if (sessionId == null) ApiRoutes.LIVE_WS else ApiRoutes.liveWs(sessionId)
        http.newWebSocketBuilder()
            .subprotocols(LiveWs.SUBPROTOCOL)
            .apply { if (token != null) header("Authorization", "${ApiRoutes.AUTH_SCHEME} $token") }
            .buildAsync(URI("ws://127.0.0.1:$port$path"), listener)
            .get(5, TimeUnit.SECONDS)
        return listener
    }

    /**
     * A phone on v2 as the spec has it (§8.6): its Ups numbered, its states applied, the snapshot it shows. [states]:
     * a phone whose session this one takes over, its states and numbers with it.
     */
    private inner class Phone(
        session: PlayerSession,
        opened: LiveOpenResponse = open(session),
        states: Phone? = null,
    ) {
        private val socket = connect(session.token, opened.sessionId)
        val ws: WebSocket get() = socket.ws
        private val phoneStates: LiveClientStates = states?.phoneStates ?: LiveClientStates()
        private val snapshots: LiveSnapshots = states?.snapshots ?: LiveSnapshots()
        private var seq: Int = states?.seq ?: 0
        private var chat: List<ChatMessage> = states?.chat.orEmpty()
        private var shownSnapshot: GameSnapshot? = states?.shownSnapshot
        val shown: GameSnapshot get() = assertNotNull(shownSnapshot, "no state yet")
        val isOpen: Boolean get() = !socket.closed.isDone

        /**
         * An Up with what the phone has, and the Down answering it applied; pushes that come before the answer are
         * applied too, as the phone would.
         */
        fun exchange(samples: List<LocationSample> = emptyList(), chatAfter: Long? = null): Down {
            val up = LiveUplink.up(
                seq = ++seq,
                ackState = phoneStates.ackState,
                timeMillis = clock.millis(),
                samples = samples,
                chatAfter = chatAfter,
                full = phoneStates.needFull,
            )
            send(up)
            while (true) {
                val down = next()
                apply(down)
                if (down.reply_to == up.seq) return down
                assertEquals(0, down.reply_to, "a push, or the answer to this Up")
            }
        }

        fun apply(down: Down) {
            assertTrue(phoneStates.apply(down.state_id, down.base_state_id, down.state), "the state applies")
            if (!down.chat_push) chat = down.chat.map(LiveDownlink::chatMessage)
            phoneStates.shown?.let { shownSnapshot = snapshots.toGameSnapshot(it, down.server_time_ms, chat) }
        }

        fun send(up: Up) = apply { sendBytes(Up.ADAPTER.encode(up)) }

        fun sendBytes(bytes: ByteArray) {
            ws.sendBinary(ByteBuffer.wrap(bytes), true).get(5, TimeUnit.SECONDS)
        }

        fun sendParts(parts: List<ByteArray>) = parts.forEachIndexed { index, part ->
            ws.sendBinary(ByteBuffer.wrap(part), index == parts.lastIndex).get(5, TimeUnit.SECONDS)
        }

        fun next(timeoutMillis: Long = 5_000): Down = assertNotNull(poll(timeoutMillis), "no Down in $timeoutMillis ms")

        fun poll(timeoutMillis: Long): Down? = socket.poll(timeoutMillis)?.let(Down.ADAPTER::decode)

        fun awaitClose(): Int = socket.awaitClose()

        fun close() = socket.close()
    }

    /** A socket as the tests drive it: binary frames in a queue, the close code once closed. */
    private class TestSocket : WebSocket.Listener {
        private val frames = LinkedBlockingQueue<ByteArray>()
        val closed = CompletableFuture<Int>()
        private var bytes = java.io.ByteArrayOutputStream()
        lateinit var ws: WebSocket

        override fun onOpen(webSocket: WebSocket) {
            ws = webSocket
            webSocket.request(1)
        }

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            bytes.write(ByteArray(data.remaining()).also(data::get))
            if (last) {
                frames += bytes.toByteArray()
                bytes = java.io.ByteArrayOutputStream()
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            closed.complete(statusCode)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            closed.complete(-1)
        }

        fun poll(timeoutMillis: Long): ByteArray? = frames.poll(timeoutMillis, TimeUnit.MILLISECONDS)

        fun awaitClose(): Int = closed.get(5, TimeUnit.SECONDS)

        fun close() {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                // Closed by the server meanwhile.
            }
        }
    }
}
