package app.hovanki.client.network.live

import app.hovanki.client.network.SocketUnreachable
import app.hovanki.shared.live.LiveDatagrams
import app.hovanki.shared.live.LiveDownlink
import app.hovanki.shared.live.LiveJson
import app.hovanki.shared.live.LiveLimits
import app.hovanki.shared.live.LiveSections
import app.hovanki.shared.live.LiveServerStates
import app.hovanki.shared.live.v2.Down
import app.hovanki.shared.live.v2.Up
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.LiveOpenRequest
import app.hovanki.shared.protocol.LiveOpenResponse
import app.hovanki.shared.protocol.PlayerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlin.test.assertNotNull

/**
 * The server's side of the live channel v2 for the unit tests: opens sessions and WebSockets, and answers the phone's
 * Ups with Downs cut by the real [LiveServerStates] from the snapshots the test gives, as the server does.
 */
class FakeLiveServer :
    LiveApi,
    LiveSocketOpener {
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

    override suspend fun open(session: PlayerSession, request: LiveOpenRequest): LiveOpenResponse {
        openError?.let { throw it }
        opens += request
        val live = FakeLiveSession(sessions.size + 1L)
        sessions += live
        return LiveOpenResponse(
            sessionId = LiveDatagrams.sessionIdText(live.id),
            upKey = KEY,
            downKey = KEY,
            resetToken = KEY,
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

    /** The [index]th WebSocket opened, waiting for it. */
    suspend fun awaitSocket(index: Int): FakeLiveSocket {
        while (sockets.size <= index) openings.receive()
        return sockets[index]
    }

    private companion object {
        const val SERVER_TIME = 1_000L
        const val KEY = "AAAAAAAAAAAAAAAAAAAAAA=="
    }
}

/** A live session on the fake server: the states it sent, the Ups it took. */
class FakeLiveSession(val id: Long) {
    val states = LiveServerStates()
    val json = LiveJson()
    var lastSeq = 0
}

/** One WebSocket v2; the test reads the phone's Ups and sends Downs. */
class FakeLiveSocket(val session: FakeLiveSession) : LiveSocket {
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

    /** The last Up [awaitUp] took. */
    lateinit var lastUp: Up
        private set

    /** The phone's next Up, taken as the server takes it: its acknowledgement and its `full`. */
    suspend fun awaitUp(): Up = assertNotNull(awaitUpOrNull(), "the phone closed the WebSocket")

    /** The phone's next Up; null once the phone closed the WebSocket. */
    suspend fun awaitUpOrNull(): Up? {
        val up = Up.ADAPTER.decode(fromApp.receiveCatching().getOrNull() ?: return null)
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

    fun send(down: Down) {
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
