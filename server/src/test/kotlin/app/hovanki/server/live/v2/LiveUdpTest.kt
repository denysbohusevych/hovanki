package app.hovanki.server.live.v2

import app.hovanki.server.features.FeatureFlags
import app.hovanki.shared.live.LiveAead
import app.hovanki.shared.live.LiveClientStates
import app.hovanki.shared.live.LiveDatagrams
import app.hovanki.shared.live.LiveDownlink
import app.hovanki.shared.live.LiveLimits
import app.hovanki.shared.live.LiveReassembler
import app.hovanki.shared.live.LiveReplayWindow
import app.hovanki.shared.live.LiveSnapshots
import app.hovanki.shared.live.LiveUplink
import app.hovanki.shared.live.LiveValues
import app.hovanki.shared.live.v2.CloseReason
import app.hovanki.shared.live.v2.Down
import app.hovanki.shared.live.v2.Up
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.LiveOpenRequest
import app.hovanki.shared.protocol.LiveOpenResponse
import app.hovanki.shared.protocol.LiveTransports
import app.hovanki.shared.protocol.LiveUdpEndpoint
import app.hovanki.shared.protocol.LiveWs
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.RolesRequest
import app.hovanki.shared.protocol.SendChatRequest
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.shrinkingZone
import io.micrometer.core.instrument.MeterRegistry
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.socket.CloseStatus
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live channel v2 over UDP on a real server (docs/live-v2/spec.md §5, server.md §4.3): the open's address, the
 * encrypted datagrams, the challenge of a new address and its echo, pushes, pieces, RESET, and what is dropped without a
 * word. The phone here is the spec's, on a plain [DatagramSocket] with the JDK's AES-GCM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveUdpTest(
    @LocalServerPort private val port: Int,
    @Autowired private val features: FeatureFlags,
    @Autowired private val sessions: LiveSessions,
    @Autowired private val udp: LiveUdpServer,
    @Autowired private val clock: Clock,
    @Autowired private val meters: MeterRegistry,
) {
    private val http = HttpClient.newHttpClient()
    private val park = GeoPoint(50.4501, 30.5234)
    private val settings = GameSettings(zone = shrinkingZone(park, steps = 0), hidingSeconds = 60, seekingSeconds = 600)

    @BeforeEach
    fun on() {
        set(ServerFeature.LIVE_V2, true)
        set(ServerFeature.LIVE_UDP, true)
    }

    /** The tests share the database: the switches go back off for the others. */
    @AfterEach
    fun off() {
        set(ServerFeature.LIVE_UDP, false)
        set(ServerFeature.LIVE_V2, false)
    }

    private fun set(feature: ServerFeature, on: Boolean) {
        if (features.isEnabled(feature) != on) features.set(feature, on, "test", clock.instant())
    }

    @Test
    fun theOpenGivesAnAddressOnlyWhileUdpIsOnToAPhoneThatOffersIt() {
        val host = create()
        val opened = open(host.session)
        assertEquals(LiveUdpEndpoint("127.0.0.1", assertNotNull(udp.boundPort())), opened.udp)
        assertNull(open(host.session, listOf(LiveTransports.WS)).udp, "a phone without UDP")
        set(ServerFeature.LIVE_UDP, false)
        assertNull(open(host.session).udp, "UDP off: the WebSocket v2 only")
    }

    @Test
    fun aNewAddressIsChallengedAndItsEchoBringsTheState() {
        val syncsBefore = syncsTimed()
        val host = create()
        val phone = UdpPhone(host.session)
        val fix = LocationSample(park, accuracyMeters = 5.0, timestampMillis = clock.millis())

        // The first Up of the session: processed, but only acknowledged and challenged, no state for an address the
        // phone has not confirmed.
        val challenged = phone.exchange(samples = listOf(fix), chatAfter = 0)
        assertEquals(1 to 1, challenged.reply_to to challenged.ack_up)
        assertEquals(0, challenged.state_id)
        assertEquals(8, challenged.path_challenge.size)
        assertNull(sessions.get(phone.sessionIdText)?.link, "no pushes before the echo")

        val first = phone.exchange(chatAfter = 0)
        assertEquals(2 to 2, first.reply_to to first.ack_up)
        assertEquals(1 to 0, first.state_id to first.base_state_id, "the first state is full")
        assertEquals(0, first.path_challenge.size)
        val posted = post<GameSnapshot>(ApiRoutes.sync(host.session.gameId), SyncRequest(chatAfter = 0), host.session)
        assertEquals(comparable(posted), comparable(phone.shown))
        assertEquals(LiveMetrics.UDP, sessions.get(phone.sessionIdText)?.link?.transport)
        assertEquals(2, syncsTimed() - syncsBefore, "hovanki.game.sync{transport=udp}")
        phone.close()
    }

    @Test
    fun pushesGoToTheConfirmedAddressOnly() {
        val host = create()
        val guest = join(host)
        val phone = UdpPhone(guest.session).apply { confirm() }

        setRoles(host, listOf(guest))
        val push = phone.next()
        assertEquals(0, push.reply_to)
        phone.apply(push)
        assertEquals(Role.SEEKER, phone.shown.players.single { it.id == guest.session.playerId }.role)

        // A new network: the first Up from it is challenged, the push meanwhile goes to the address confirmed before.
        val before = phone.moveToNewAddress()
        val challenged = phone.exchange()
        assertEquals(8, challenged.path_challenge.size)
        assertEquals(0, challenged.state_id)
        setRoles(host, emptyList())
        assertNotNull(phone.poll(before, 3_000), "a push to the old address while the new one is unconfirmed")
        assertNull(phone.poll(timeoutMillis = 300), "nothing to the new one")

        phone.exchange()
        setRoles(host, listOf(guest))
        phone.apply(phone.next())
        assertEquals(Role.SEEKER, phone.shown.players.single { it.id == guest.session.playerId }.role)
        before.close()
        phone.close()
    }

    @Test
    fun aRepeatedOrForgedDatagramIsDroppedWithoutAWord() {
        val host = create()
        val phone = UdpPhone(host.session).apply { confirm() }
        val failsBefore = meters.counter("hovanki.live.auth_fail").count()

        val datagram = phone.sealUp(phone.bareUp()).single()
        phone.sendRaw(datagram)
        assertEquals(3, phone.next().reply_to)
        phone.sendRaw(datagram)
        assertNull(phone.poll(timeoutMillis = 300), "a repeated packet number")

        val forged = phone.sealUp(phone.bareUp()).single()
        forged[forged.size - 1] = (forged[forged.size - 1].toInt() xor 1).toByte()
        phone.sendRaw(forged)
        assertNull(phone.poll(timeoutMillis = 300), "a datagram that does not decrypt")
        assertEquals(1.0, meters.counter("hovanki.live.auth_fail").count() - failsBefore)
        phone.sendRaw(byteArrayOf(0x21, 1, 2))
        phone.sendRaw(ByteArray(60) { 0x31 })
        assertNull(phone.poll(timeoutMillis = 300), "no v2 datagram at all")
        assertEquals(5, phone.exchange().reply_to, "the session goes on")
        phone.close()
    }

    @Test
    fun anUnknownSessionGetsAResetOnceASecond() {
        val host = create()
        val phone = UdpPhone(host.session).apply { confirm() }
        // Forgotten as the server forgets an idle one (or after a restart); its close comes first.
        sessions.sweepAt(clock.millis() + LiveLimits.SESSION_IDLE_MILLIS + 1_000)
        assertNull(sessions.get(phone.sessionIdText))
        assertEquals(CloseReason.CLOSE_REOPEN, phone.awaitClose())

        val fix = LocationSample(park, accuracyMeters = 5.0, timestampMillis = clock.millis())
        phone.sendRaw(phone.sealUp(phone.bareUp(listOf(fix))).single().copyOf(LiveDatagrams.MIN_DATA_FOR_RESET - 1))
        assertNull(phone.pollRaw(300), "smaller than the RESET: no answer")
        phone.sendRaw(phone.sealUp(phone.bareUp(listOf(fix))).single())
        val reset = assertNotNull(phone.pollRaw(3_000))
        assertTrue(phone.isReset(reset), "the RESET of this session, by its token")
        phone.sendRaw(phone.sealUp(phone.bareUp(listOf(fix))).single())
        assertNull(phone.pollRaw(300), "once a second to an address")
        phone.close()
    }

    @Test
    fun aLongDownComesInPieces() {
        val host = create()
        // Within the chat's pace (ChatRules): 4 long messages, over one datagram together.
        repeat(4) {
            post<GameSnapshot>(
                ApiRoutes.chat(host.session.gameId),
                SendChatRequest("message $it " + "x".repeat(280), chatAfter = 0),
                host.session,
            )
        }
        val phone = UdpPhone(host.session)
        phone.exchange()
        val first = phone.exchange(chatAfter = 0)
        assertTrue(phone.piecesOfLast > 1, "${phone.piecesOfLast} datagrams")
        assertEquals(1, first.state_id)
        assertEquals(4, phone.shown.chat.size)
        phone.close()
    }

    @Test
    fun udpOffSendsTheSessionsOnItToOpenAnewAndTheirEndGoesBack() {
        val host = create()
        val guest = join(host)
        val hostPhone = UdpPhone(host.session).apply { confirm() }
        val guestPhone = UdpPhone(guest.session).apply { confirm() }

        set(ServerFeature.LIVE_UDP, false)
        sessions.sweepAt(clock.millis())
        assertEquals(CloseReason.CLOSE_REOPEN, hostPhone.awaitClose())
        assertEquals(CloseReason.CLOSE_REOPEN, guestPhone.awaitClose())
        assertNull(open(host.session).udp)
        set(ServerFeature.LIVE_UDP, true)

        // A player who left: the session's end comes over UDP.
        val again = UdpPhone(guest.session).apply { confirm() }
        post<Unit>(ApiRoutes.leave(host.session.gameId), null, guest.session)
        sessions.sweepAt(clock.millis())
        assertEquals(CloseReason.CLOSE_SESSION_REJECTED, again.awaitClose())
        listOf(hostPhone, guestPhone, again).forEach { it.close() }
    }

    @Test
    fun aFreshUpOverUdpTakesTheSessionBackFromTheWebSocket() {
        val host = create()
        val guest = join(host)
        val phone = UdpPhone(guest.session).apply { confirm() }
        val switchesBefore = switches(LiveMetrics.WS2)
        val ws = connect(guest.session.token, phone.sessionIdText)
        // The WebSocket took the session: pushes go there. The switch is counted after the link is set.
        waitFor { switches(LiveMetrics.WS2) - switchesBefore == 1.0 }
        assertEquals(LiveMetrics.WS2, sessions.get(phone.sessionIdText)?.link?.transport)
        setRoles(host, listOf(guest))
        assertNotNull(ws.poll(3_000), "a push on the WebSocket")
        assertNull(phone.poll(timeoutMillis = 300), "none over UDP")

        // A fresh Up over UDP from the confirmed address: back on UDP, the WebSocket closes quietly.
        phone.exchange()
        assertEquals(CloseStatus.NORMAL.code, ws.awaitClose())
        assertEquals(LiveMetrics.UDP, sessions.get(phone.sessionIdText)?.link?.transport)
        setRoles(host, emptyList())
        assertEquals(0, phone.next().reply_to, "pushes over UDP again")
        phone.close()
    }

    // ---- helpers ----

    private fun syncsTimed(): Long = meters.find("hovanki.game.sync").tag("transport", "udp").timer()?.count() ?: 0

    private fun switches(to: String): Double =
        meters.find("hovanki.live.transport_switch").tag("to", to).counter()?.count() ?: 0.0

    private fun waitFor(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            assertTrue(System.nanoTime() < until, "not within 5 s")
            Thread.sleep(20)
        }
    }

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

    private fun create(): SessionResponse = post(ApiRoutes.GAMES, CreateGameRequest("Host", settings), session = null)

    private fun join(host: SessionResponse, name: String = "Guest"): SessionResponse =
        post(ApiRoutes.JOIN, JoinGameRequest(host.snapshot.joinCode, name), session = null)

    private fun setRoles(host: SessionResponse, seekers: List<SessionResponse>) {
        post<GameSnapshot>(
            ApiRoutes.roles(host.session.gameId),
            RolesRequest(seekers.map { it.session.playerId }),
            host.session,
        )
    }

    private fun open(
        session: PlayerSession,
        transports: List<String> = listOf(LiveTransports.UDP, LiveTransports.WS),
    ): LiveOpenResponse {
        val request = LiveOpenRequest(appVersion = "test", transports = transports)
        val body = protocolJson.encodeToString(LiveOpenRequest.serializer(), request)
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

    /** A WebSocket v2 of [sessionId]: binary frames in a queue, the close code once closed. */
    private fun connect(token: String, sessionId: String): TestSocket {
        val listener = TestSocket()
        http.newWebSocketBuilder()
            .subprotocols(LiveWs.SUBPROTOCOL)
            .header("Authorization", "${ApiRoutes.AUTH_SCHEME} $token")
            .buildAsync(URI("ws://127.0.0.1:$port${ApiRoutes.liveWs(sessionId)}"), listener)
            .get(5, TimeUnit.SECONDS)
        return listener
    }

    /**
     * A phone on v2 over UDP as the spec has it (§5, §8.6): its datagrams sealed and numbered, the server's checked
     * against its window and put together, its states applied, the challenge echoed in the next Up.
     */
    private inner class UdpPhone(session: PlayerSession, opened: LiveOpenResponse = open(session)) {
        val sessionIdText = opened.sessionId
        private val sessionId = assertNotNull(LiveDatagrams.sessionIdOf(opened.sessionId))
        private val upAead = JdkAead(Base64.getDecoder().decode(opened.upKey))
        private val downAead = JdkAead(Base64.getDecoder().decode(opened.downKey))
        private val resetToken = Base64.getDecoder().decode(opened.resetToken)
        private val server = assertNotNull(opened.udp).let { InetSocketAddress(it.host, it.port) }
        private var socket = DatagramSocket()
        private var pn = 0
        private var messageId = 0
        private val window = LiveReplayWindow()
        private val pieces = LiveReassembler()
        private val states = LiveClientStates()
        private val snapshots = LiveSnapshots()
        private var seq = 0
        private var chat: List<ChatMessage> = emptyList()
        private var shownSnapshot: GameSnapshot? = null
        private var pathResponse = ByteArray(0)
        val shown: GameSnapshot get() = assertNotNull(shownSnapshot, "no state yet")
        val ackState: Int get() = states.ackState

        /** The datagrams the last Down came in. */
        var piecesOfLast = 0
            private set

        /** The first Up, challenged, and its echo: the address is the phone's, the state came. */
        fun confirm() {
            assertEquals(8, exchange().path_challenge.size)
            assertTrue(exchange().state_id != 0)
        }

        /** An Up with what the phone has; the Downs that come before its answer are applied too. */
        fun exchange(samples: List<LocationSample> = emptyList(), chatAfter: Long? = null): Down {
            val up = LiveUplink.up(
                seq = ++seq,
                ackState = states.ackState,
                timeMillis = clock.millis(),
                samples = samples,
                chatAfter = chatAfter,
                full = states.needFull,
                pathResponse = pathResponse.toByteString(),
            )
            sealUp(up).forEach(::sendRaw)
            while (true) {
                val down = next()
                apply(down)
                if (down.reply_to == up.seq) return down
                assertEquals(0, down.reply_to, "a push, or the answer to this Up")
            }
        }

        fun apply(down: Down) {
            if (down.path_challenge.size > 0) pathResponse = down.path_challenge.toByteArray()
            assertTrue(states.apply(down.state_id, down.base_state_id, down.state), "the state applies")
            if (down.state_id == 0) return
            if (!down.chat_push) chat = down.chat.map(LiveDownlink::chatMessage)
            states.shown?.let { shownSnapshot = snapshots.toGameSnapshot(it, down.server_time_ms, chat) }
        }

        /** An Up with the next number and nothing to say but [samples]: for the datagrams the test makes itself. */
        fun bareUp(samples: List<LocationSample> = emptyList()): Up =
            LiveUplink.up(seq = ++seq, ackState = states.ackState, timeMillis = clock.millis(), samples = samples)

        /** The close of the session, past the Downs before it. */
        fun awaitClose(): CloseReason? {
            while (true) {
                next().close?.let { return it.reason }
            }
        }

        /** [up]'s datagrams: sealed with the next packet numbers, in pieces when it is long. */
        fun sealUp(up: Up): List<ByteArray> = LiveDatagrams.plaintexts(Up.ADAPTER.encode(up), ++messageId)
            .map { LiveDatagrams.seal(upAead, sessionId, ++pn, it) }

        fun sendRaw(datagram: ByteArray) = socket.send(DatagramPacket(datagram, datagram.size, server))

        /** The next datagram on [on] as it came, or null within [timeoutMillis]. */
        fun pollRaw(timeoutMillis: Int, on: DatagramSocket = socket): ByteArray? {
            on.soTimeout = timeoutMillis.coerceAtLeast(1)
            val packet = DatagramPacket(ByteArray(2_048), 2_048)
            return try {
                on.receive(packet)
                packet.data.copyOf(packet.length)
            } catch (e: SocketTimeoutException) {
                null
            }
        }

        fun isReset(datagram: ByteArray): Boolean = LiveDatagrams.isReset(datagram, sessionId, resetToken)

        fun next(): Down = assertNotNull(poll(timeoutMillis = 5_000), "no Down in 5 s")

        /** The next whole Down on [on], or null within [timeoutMillis]. */
        fun poll(on: DatagramSocket = socket, timeoutMillis: Long): Down? {
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            var count = 0
            while (true) {
                val left = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())
                if (left <= 0) return null
                val datagram = pollRaw(left.toInt(), on) ?: return null
                val head = LiveDatagrams.head(datagram) ?: continue
                val plaintext = LiveDatagrams.open(downAead, datagram) ?: continue
                if (!window.accept(head.pn)) continue
                count++
                val message = pieces.accept(plaintext, clock.millis()) ?: continue
                piecesOfLast = count
                return Down.ADAPTER.decode(message)
            }
        }

        /** The phone's next socket, as on a new network; the one before is returned, still open. */
        fun moveToNewAddress(): DatagramSocket = socket.also { socket = DatagramSocket() }

        fun close() = socket.close()
    }

    /** The JDK's AES-128-GCM (spec §5.2), as Android has it. */
    private class JdkAead(key: ByteArray) : LiveAead {
        private val key = SecretKeySpec(key, "AES")

        override fun seal(nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(plaintext)
            }

        override fun open(nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray? = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(ciphertextAndTag)
            }
        } catch (_: AEADBadTagException) {
            null
        }
    }

    private class TestSocket : WebSocket.Listener {
        private val frames = LinkedBlockingQueue<ByteArray>()
        private val closed = CompletableFuture<Int>()
        private var bytes = java.io.ByteArrayOutputStream()

        override fun onOpen(webSocket: WebSocket) {
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
    }
}
