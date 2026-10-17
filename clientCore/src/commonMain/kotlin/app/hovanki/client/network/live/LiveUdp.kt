package app.hovanki.client.network.live

import app.hovanki.shared.live.LiveAead
import app.hovanki.shared.live.LiveDatagrams
import app.hovanki.shared.live.LiveLimits
import app.hovanki.shared.live.LiveReassembler
import app.hovanki.shared.live.LiveReplayWindow
import app.hovanki.shared.protocol.LiveOpenResponse
import app.hovanki.shared.protocol.LiveWs
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ConnectedDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import okio.ByteString.Companion.decodeBase64

/**
 * The live channel v2's UDP on this phone (docs/live-v2/spec.md §5, client.md §4.1): the sockets, the platform's
 * AES-128-GCM and the network's changes. A phone without it (an iPhone whose CryptoKit bridge is missing or failed its
 * check at start) offers the WebSocket v2 only.
 */
class LiveUdp(val opener: LiveDatagramOpener, val aeads: LiveAeads, val network: NetworkChanges = NetworkChanges.None)

/**
 * One UDP socket "connected" to a live session's server address: whole datagrams in and out. The seam between the
 * live channel and the network, as [LiveSocket] is the WebSocket's: Ktor on the phones, a fake in the unit tests, a
 * simulated network around Ktor for the e2e bots.
 */
interface LiveDatagramSocket {
    /** The next datagram from the server; null once closed. Throws when the network fails: the phone takes it as lost. */
    suspend fun receive(): ByteArray?

    /** Sends one datagram; throws when the network can't, which is a loss too. */
    suspend fun send(datagram: ByteArray)

    fun close()
}

/** Opens a new socket, on a new local port, to the UDP address of [live] ([LiveOpenResponse.udp], never null here). */
fun interface LiveDatagramOpener {
    suspend fun open(live: LiveOpenResponse): LiveDatagramSocket
}

/** The platform's AES-128-GCM (spec §5.2): one [LiveAead] per key. */
fun interface LiveAeads {
    fun forKey(key: ByteArray): LiveAead
}

/**
 * The phone's default network: [generation] grows by one at every change (Wi-Fi ↔ mobile), and the live channel takes
 * a new UDP socket on the new network (spec §5.6) and tries UDP again after it gave it up (§5.7).
 */
fun interface NetworkChanges {
    fun generation(): Long

    companion object {
        /** No news of the network: a lost socket is found by its lost Ups instead. */
        val None = NetworkChanges { 0L }
    }
}

/** UDP sockets over Ktor's (POSIX on iOS, NIO on the JVM and Android); one selector for all of them. */
class KtorLiveDatagramOpener(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : LiveDatagramOpener {
    private val selector by lazy { SelectorManager(dispatcher) }

    override suspend fun open(live: LiveOpenResponse): LiveDatagramSocket {
        val udp = requireNotNull(live.udp) { "The live session has no UDP address" }
        // The name is resolved here, which may block: on the dispatcher, never the caller's thread.
        val socket = withContext(dispatcher) { aSocket(selector).udp().connect(InetSocketAddress(udp.host, udp.port)) }
        return KtorDatagramSocket(socket)
    }
}

private class KtorDatagramSocket(private val socket: ConnectedDatagramSocket) : LiveDatagramSocket {
    override suspend fun receive(): ByteArray? = try {
        socket.receive().packet.readByteArray()
    } catch (e: ClosedReceiveChannelException) {
        null
    }

    override suspend fun send(datagram: ByteArray) {
        socket.send(Datagram(Buffer().apply { write(datagram) }, socket.remoteAddress))
    }

    override fun close() {
        socket.close()
    }
}

/**
 * One live session's UDP (spec §5.1–5.5): its keys, its packet numbers, its window and its fragments, kept across
 * the sockets the session goes through (a new network, a while on the WebSocket v2): the server takes a new address
 * only from a packet number above every one it saw (§5.6).
 */
internal class UdpSession private constructor(
    val sessionId: Long,
    val upAead: LiveAead,
    val downAead: LiveAead,
    val resetToken: ByteArray,
) {
    private var pn = 0
    private var messageId = 0
    val window = LiveReplayWindow()
    val pieces = LiveReassembler()

    /** The next Up datagram's number; null past [LiveLimits.MAX_DATAGRAMS_PER_KEY]: the key is spent. */
    fun nextPn(): Int? = if (pn >= LiveLimits.MAX_DATAGRAMS_PER_KEY) null else ++pn

    fun nextMessageId(): Int = messageId.also { messageId = (it + 1) and 0xffff }

    companion object {
        /** Null when the opening's keys don't read: then this session goes over the WebSocket v2. */
        fun of(live: LiveOpenResponse, aeads: LiveAeads): UdpSession? {
            val id = LiveDatagrams.sessionIdOf(live.sessionId) ?: return null
            val up = live.upKey.decodeBase64()?.toByteArray()?.takeIf { it.size == KEY_BYTES } ?: return null
            val down = live.downKey.decodeBase64()?.toByteArray()?.takeIf { it.size == KEY_BYTES } ?: return null
            val reset = live.resetToken.decodeBase64()?.toByteArray()
                ?.takeIf { it.size == LiveDatagrams.RESET_TAG_BYTES } ?: return null
            return UdpSession(id, aeads.forKey(up), aeads.forKey(down), reset)
        }

        private const val KEY_BYTES = 16
    }
}

/**
 * A live session over UDP behind the [LiveSocket] seam: an Up goes out sealed (in fragments when it is long), whole
 * Downs come in once they decrypt, pass the window and are put together. A RESET of the session closes it with
 * [LiveWs.CLOSE_REOPEN]; so does a spent key. Anything else that goes wrong is a lost datagram: UDP has no errors to
 * show, and [LiveGameConnection] counts the Ups left without an answer.
 */
internal class UdpLiveSocket private constructor(
    private val session: UdpSession,
    private val opener: LiveDatagramOpener,
    private val live: LiveOpenResponse,
    private val network: NetworkChanges,
    private val nowMillis: () -> Long,
    private var socket: LiveDatagramSocket,
    private var networkGeneration: Long,
) : LiveSocket {
    private var code: Int? = null
    private var isClosed = false

    /** The socket failed: nothing more comes from it until [renew]. */
    private var isBroken = false

    override suspend fun receive(): ByteArray? {
        while (true) {
            if (isClosed) return null
            if (isBroken) awaitCancellation()
            val datagram = try {
                socket.receive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                isBroken = true
                continue
            }
            if (datagram == null) {
                // Closed under the reader: by [close], or by the network; a new socket comes with [renew].
                if (isClosed) return null
                isBroken = true
                continue
            }
            message(datagram)?.let { return it }
            if (isClosed) return null
        }
    }

    /** The whole message [datagram] completes; sets [code] on this session's RESET. */
    private fun message(datagram: ByteArray): ByteArray? {
        val head = LiveDatagrams.head(datagram) ?: return null
        if (head.sessionId != session.sessionId) return null
        if (head.type == LiveDatagrams.TYPE_RESET) {
            if (LiveDatagrams.isReset(datagram, session.sessionId, session.resetToken)) {
                code = LiveWs.CLOSE_REOPEN
                isClosed = true
                socket.close()
            }
            return null
        }
        if (head.type != LiveDatagrams.TYPE_DATA) return null
        val plaintext = LiveDatagrams.open(session.downAead, datagram) ?: return null
        if (!session.window.accept(head.pn)) return null
        return session.pieces.accept(plaintext, nowMillis())
    }

    override suspend fun send(bytes: ByteArray) {
        if (isClosed) return
        for (plaintext in LiveDatagrams.plaintexts(bytes, session.nextMessageId())) {
            val pn = session.nextPn()
            if (pn == null) {
                // A new session gets new keys (spec §5.3).
                code = LiveWs.CLOSE_REOPEN
                isClosed = true
                socket.close()
                return
            }
            try {
                socket.send(LiveDatagrams.seal(session.upAead, session.sessionId, pn, plaintext))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Lost, as UDP loses.
            }
        }
    }

    override suspend fun closeCode(): Int? = code

    override suspend fun close() {
        isClosed = true
        socket.close()
    }

    /** A new socket on a new local port, the session kept: after Ups went unanswered, or a new network. */
    suspend fun renew() {
        if (isClosed) return
        networkGeneration = network.generation()
        socket.close()
        isBroken = try {
            socket = opener.open(live)
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No socket on this network (yet): the Ups are lost until the next renew.
            true
        }
    }

    /** [renew] when the phone's network changed since this socket opened. */
    suspend fun followNetwork() {
        if (network.generation() != networkGeneration) renew()
    }

    companion object {
        suspend fun open(
            session: UdpSession,
            udp: LiveUdp,
            live: LiveOpenResponse,
            nowMillis: () -> Long,
        ): UdpLiveSocket {
            val generation = udp.network.generation()
            return UdpLiveSocket(session, udp.opener, live, udp.network, nowMillis, udp.opener.open(live), generation)
        }
    }
}
