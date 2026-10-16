package app.hovanki.client.network.live

import app.hovanki.client.network.SocketUnreachable
import app.hovanki.shared.live.LiveAead
import app.hovanki.shared.live.LiveDatagrams
import app.hovanki.shared.live.LiveDownlink
import app.hovanki.shared.live.LiveJson
import app.hovanki.shared.live.LiveLimits
import app.hovanki.shared.live.LiveReassembler
import app.hovanki.shared.live.LiveReplayWindow
import app.hovanki.shared.live.LiveSections
import app.hovanki.shared.live.LiveServerStates
import app.hovanki.shared.live.v2.Down
import app.hovanki.shared.live.v2.Up
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.LiveOpenRequest
import app.hovanki.shared.protocol.LiveOpenResponse
import app.hovanki.shared.protocol.LiveUdpEndpoint
import app.hovanki.shared.protocol.PlayerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.test.assertNotNull

/**
 * The server's side of the live channel v2 for the unit tests: opens sessions, WebSockets and UDP sockets, and answers
 * the phone's Ups with Downs cut by the real [LiveServerStates] from the snapshots the test gives, as the server does.
 */
class FakeLiveServer :
    LiveApi,
    LiveSocketOpener,
    LiveDatagramOpener {
    /** The UDP address the opens give; null: none, the WebSocket v2 only. */
    var udp: LiveUdpEndpoint? = null

    /** Every datagram both ways vanishes, as on a network that lets no UDP through. */
    var udpBlocked = false

    /** The phone's UDP sockets, each a new address to the server. */
    val datagramSockets = mutableListOf<FakeDatagramSocket>()

    /** Thrown by the next opens instead of opening a session. */
    var openError: Exception? = null

    /** The WebSocket does not open. */
    var refuseSockets = false

    /** How long a session lives, as the server says it at the opening. */
    var sessionMillis = LiveLimits.SESSION_MAX_AGE_MILLIS

    val opens = mutableListOf<LiveOpenRequest>()
    val sessions = mutableListOf<FakeLiveSession>()
    val sockets = mutableListOf<FakeLiveSocket>()
    private val openings = Channel<FakeLiveSocket>(Channel.UNLIMITED)
    private val sessionOpenings = Channel<FakeLiveSession>(Channel.UNLIMITED)

    override suspend fun open(session: PlayerSession, request: LiveOpenRequest): LiveOpenResponse {
        openError?.let { throw it }
        opens += request
        val live = FakeLiveSession(sessions.size + 1L)
        sessions += live
        sessionOpenings.send(live)
        return LiveOpenResponse(
            sessionId = LiveDatagrams.sessionIdText(live.id),
            upKey = KEY,
            downKey = KEY,
            resetToken = KEY,
            udp = udp,
            expiresAtMillis = SERVER_TIME + sessionMillis,
            serverTimeMillis = SERVER_TIME,
        )
    }

    override suspend fun open(session: PlayerSession, live: LiveOpenResponse): LiveSocket {
        if (refuseSockets) throw SocketUnreachable("No upgrade")
        val owner = sessions.first { LiveDatagrams.sessionIdText(it.id) == live.sessionId }
        return FakeLiveSocket(owner).also {
            sockets += it
            openings.send(it)
        }
    }

    override suspend fun open(live: LiveOpenResponse): LiveDatagramSocket {
        val owner = sessions.first { LiveDatagrams.sessionIdText(it.id) == live.sessionId }
        return FakeDatagramSocket(this, owner.udp).also { datagramSockets += it }
    }

    /** The [index]th live session opened, waiting for it. */
    suspend fun awaitSession(index: Int): FakeLiveSession {
        while (sessions.size <= index) sessionOpenings.receive()
        return sessions[index]
    }

    /** The [index]th WebSocket opened, waiting for it. */
    suspend fun awaitSocket(index: Int): FakeLiveSocket {
        while (sockets.size <= index) openings.receive()
        return sockets[index]
    }

    companion object {
        const val SERVER_TIME = 1_000L
        const val KEY = "AAAAAAAAAAAAAAAAAAAAAA=="
    }
}

/** A live session on the fake server: the states it sent, the Ups it took, its UDP. */
class FakeLiveSession(val id: Long) {
    val states = LiveServerStates()
    val json = LiveJson()
    var lastSeq = 0
    val udp = FakeUdpPath(this)
}

/** One way of a live session, as the test sees it: it reads the phone's Ups and sends Downs. */
abstract class FakeLink(val session: FakeLiveSession) {
    /** The next Up's bytes; null once the phone closed this way. */
    protected abstract suspend fun nextUp(): ByteArray?

    abstract fun send(down: Down)

    /** The last Up [awaitUp] took. */
    lateinit var lastUp: Up
        private set

    /** The phone's next Up, taken as the server takes it: its acknowledgement and its `full`. */
    suspend fun awaitUp(): Up = assertNotNull(awaitUpOrNull(), "the phone closed the WebSocket")

    /** The phone's next Up; null once the phone closed the WebSocket. */
    suspend fun awaitUpOrNull(): Up? {
        val up = Up.ADAPTER.decode(nextUp() ?: return null)
        session.states.acknowledge(up.ack_state)
        if (up.full) session.states.forgetAcknowledged()
        lastUp = up
        return up
    }

    /** The answer to [up]: [snapshot] as a delta against what the phone acknowledged. */
    fun answer(up: Up, snapshot: GameSnapshot, chatMore: Boolean = false) {
        session.lastSeq = up.seq
        val next = session.states.next(LiveSections.fromSnapshot(snapshot, session.json))
        send(
            Down(
                reply_to = up.seq,
                ack_up = up.seq,
                server_time_ms = snapshot.serverTimeMillis,
                state_id = next?.stateId ?: 0,
                base_state_id = next?.baseStateId ?: 0,
                state = next?.state,
                chat = snapshot.chat.map(LiveDownlink::chatMessage),
                chat_more = chatMore,
            ),
        )
    }

    /** [up] refused: the session stays, the Up is not acknowledged. */
    fun refuse(up: Up, error: ApiError, retryAfterSeconds: Long? = null) = send(
        Down(
            reply_to = up.seq,
            ack_up = session.lastSeq,
            server_time_ms = 0,
            error = LiveDownlink.error(error, retryAfterSeconds),
        ),
    )

    /** A push of [snapshot]; false when it was the state sent last already (nothing goes). */
    fun push(snapshot: GameSnapshot): Boolean {
        val next = session.states.next(LiveSections.fromSnapshot(snapshot, session.json), push = true) ?: return false
        send(
            Down(
                ack_up = session.lastSeq,
                server_time_ms = snapshot.serverTimeMillis,
                state_id = next.stateId,
                base_state_id = next.baseStateId,
                state = next.state,
            ),
        )
        return true
    }

    fun pushChat(messages: List<ChatMessage>, serverTimeMillis: Long) = send(
        Down(
            ack_up = session.lastSeq,
            server_time_ms = serverTimeMillis,
            chat = messages.map(LiveDownlink::chatMessage),
            chat_push = true,
        ),
    )
}

/** One WebSocket v2. */
class FakeLiveSocket(session: FakeLiveSession) :
    FakeLink(session),
    LiveSocket {
    private val toApp = Channel<ByteArray>(Channel.UNLIMITED)
    private val fromApp = Channel<ByteArray>(Channel.UNLIMITED)
    private var code: Int? = null
    var isClosedByApp = false
        private set

    /** Like Ktor's send into a session the server closed: a CancellationException nobody cancelled with. */
    var sendThrowsCancellation = false

    override suspend fun receive(): ByteArray? = toApp.receiveCatching().getOrNull()

    override suspend fun send(bytes: ByteArray) {
        if (sendThrowsCancellation) throw CancellationException("WebSocket session closed with code 4401.")
        if (toApp.isClosedForSend) throw SocketUnreachable("Closed")
        fromApp.send(bytes)
    }

    override suspend fun closeCode(): Int? = code

    override suspend fun close() {
        isClosedByApp = true
        toApp.close()
        fromApp.close()
    }

    override suspend fun nextUp(): ByteArray? = fromApp.receiveCatching().getOrNull()

    override fun send(down: Down) {
        toApp.trySend(Down.ADAPTER.encode(down))
    }

    fun sendBytes(bytes: ByteArray) {
        toApp.trySend(bytes)
    }

    fun closeFromServer(code: Int?) {
        this.code = code
        toApp.close()
    }
}

/**
 * A live session's UDP on the fake server (spec §5): datagrams sealed with [TestAeads], a new address (a new
 * [FakeDatagramSocket]) checked with a `path_challenge` before an Up from it reaches the test (§5.6), the Downs to the
 * checked one only, in fragments when long.
 */
class FakeUdpPath(session: FakeLiveSession) : FakeLink(session) {
    private val aead = TestAeads.forKey(ByteArray(16))
    private val fromApp = Channel<Pair<FakeDatagramSocket, ByteArray>>(Channel.UNLIMITED)
    private val window = LiveReplayWindow()
    private val pieces = LiveReassembler()
    private var pn = 0
    private var messageId = 0

    /** The checked address; null before the first. */
    var address: FakeDatagramSocket? = null
        private set

    private var candidate: FakeDatagramSocket? = null
    private var challenge = ByteString.EMPTY

    /** The checks of a new address sent. */
    var challenges = 0
        private set

    /** The next Ups (whole messages) that vanish on the way. */
    var dropUps = 0

    internal fun datagram(from: FakeDatagramSocket, datagram: ByteArray) {
        fromApp.trySend(from to datagram)
    }

    override suspend fun nextUp(): ByteArray? {
        while (true) {
            val (from, datagram) = fromApp.receiveCatching().getOrNull() ?: return null
            val head = assertNotNull(LiveDatagrams.head(datagram), "a datagram of v2")
            check(head.sessionId == session.id) { "another session's datagram" }
            val plaintext = LiveDatagrams.open(aead, datagram) ?: continue
            if (!window.accept(head.pn)) continue
            val bytes = pieces.accept(plaintext, 0) ?: continue
            if (dropUps > 0) {
                dropUps--
                continue
            }
            if (from === address) return bytes
            val up = Up.ADAPTER.decode(bytes)
            if (from === candidate && challenge.size > 0 && up.path_response == challenge) {
                address = from
                candidate = null
                challenge = ByteString.EMPTY
                return bytes
            }
            // A new address: the answer is the check only, the state waits for its echo.
            candidate = from
            challenge = ByteArray(8) { (challenges * 8 + it + 1).toByte() }.toByteString()
            challenges++
            session.lastSeq = up.seq
            sendTo(from, Down(reply_to = up.seq, ack_up = up.seq, path_challenge = challenge))
        }
    }

    override fun send(down: Down) {
        address?.let { sendTo(it, down) }
    }

    private fun sendTo(to: FakeDatagramSocket, down: Down) {
        val bytes = Down.ADAPTER.encode(down)
        for (plaintext in LiveDatagrams.plaintexts(bytes, messageId++)) {
            to.deliver(LiveDatagrams.seal(aead, session.id, ++pn, plaintext))
        }
    }

    /** The session is gone (spec §5.5): its RESET, with [tag] or the session's own. */
    fun reset(tag: ByteArray = ByteArray(LiveDatagrams.RESET_TAG_BYTES)) {
        address?.deliver(LiveDatagrams.reset(session.id, tag))
    }
}

/** One UDP socket of the phone: an address of its own to the server. */
class FakeDatagramSocket(private val server: FakeLiveServer, private val path: FakeUdpPath) : LiveDatagramSocket {
    private val toApp = Channel<ByteArray>(Channel.UNLIMITED)
    var isClosed = false
        private set

    override suspend fun receive(): ByteArray? = toApp.receiveCatching().getOrNull()

    override suspend fun send(datagram: ByteArray) {
        if (isClosed) throw SocketUnreachable("Closed")
        if (!server.udpBlocked) path.datagram(this, datagram)
    }

    override fun close() {
        isClosed = true
        toApp.close()
    }

    internal fun deliver(datagram: ByteArray) {
        if (!server.udpBlocked) toApp.trySend(datagram)
    }
}

/**
 * A stand-in for AES-GCM in the tests that run on every platform: the plaintext in the clear and a 16-byte tag over the
 * key, the nonce, the header and the plaintext, so a datagram changed on the way or sealed with another key is refused.
 */
object TestAeads : LiveAeads {
    override fun forKey(key: ByteArray): LiveAead = object : LiveAead {
        override fun seal(nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
            plaintext + tag(key, nonce, aad, plaintext)

        override fun open(nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray? {
            if (ciphertextAndTag.size < TAG_BYTES) return null
            val plaintext = ciphertextAndTag.copyOfRange(0, ciphertextAndTag.size - TAG_BYTES)
            val tag = ciphertextAndTag.copyOfRange(ciphertextAndTag.size - TAG_BYTES, ciphertextAndTag.size)
            return plaintext.takeIf { tag.contentEquals(tag(key, nonce, aad, plaintext)) }
        }
    }

    private fun tag(vararg parts: ByteArray): ByteArray {
        val tag = ByteArray(TAG_BYTES)
        for (half in 0..1) {
            var hash = -0x340d631b7bdddcdbL + half
            for (part in parts) {
                for (byte in part) hash = (hash xor (byte.toLong() and 0xff)) * 0x100000001b3L
                hash = (hash xor part.size.toLong()) * 0x100000001b3L
            }
            for (i in 0 until 8) tag[half * 8 + i] = (hash ushr (8 * i)).toByte()
        }
        return tag
    }

    private const val TAG_BYTES = 16
}
