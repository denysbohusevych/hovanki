package app.hovanki.server.live.v2

import app.hovanki.server.features.FeatureFlags
import app.hovanki.shared.live.LiveAead
import app.hovanki.shared.live.LiveDatagrams
import app.hovanki.shared.live.LiveLimits
import app.hovanki.shared.live.LiveReassembler
import app.hovanki.shared.live.LiveReplayWindow
import app.hovanki.shared.live.v2.Close
import app.hovanki.shared.live.v2.CloseReason
import app.hovanki.shared.live.v2.Down
import app.hovanki.shared.live.v2.Up
import app.hovanki.shared.protocol.LiveUdpEndpoint
import app.hovanki.shared.protocol.ServerFeature
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The live channel v2 over UDP (docs/live-v2/spec.md §5, server.md §4.3): one socket on [LiveProperties.Udp.port] of
 * every address of the machine, opened by the first open that offers UDP while the server has
 * [ServerFeature.LIVE_UDP] on, and [LiveProperties.Udp.threads] threads that take its datagrams in. Each datagram finds
 * its session by the id in its header, decrypts with the session's key, passes the window of packet numbers and the
 * rule of addresses (spec §5.6), and goes together with its pieces into an Up, which [LiveExchange] answers as it
 * answers the WebSocket v2's; the receiving thread takes it to the end, the game's sync and the Down included. An
 * unknown session gets a RESET (spec §5.5) within its limits; anything else that is wrong is dropped without a word: the
 * server never answers what it can't authenticate with more than it got. Nothing of it is logged but counts.
 */
@Component
class LiveUdpServer(
    private val sessions: LiveSessions,
    private val exchange: LiveExchange,
    private val features: FeatureFlags,
    private val metrics: LiveMetrics,
    private val clock: Clock,
    properties: LiveProperties,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val settings = properties.udp
    private val random = SecureRandom()

    private val lock = Any()

    @Volatile private var channel: DatagramChannel? = null

    // Guarded by the lock.
    private var stopped = false
    private var failedAtNanos = 0L
    private val threads = ArrayList<Thread>()

    @Volatile private var running = false

    /** RESETs: when each address got its last, and how many went this second (spec §5.5). */
    private val resetsTo = ConcurrentHashMap<InetSocketAddress, Long>()
    private val resetSecond = AtomicLong()
    private val resetsThisSecond = AtomicInteger()

    /**
     * Where the phones send their datagrams: [LiveProperties.Udp.host], else [requestHost] (the name the phone asked
     * the open of), and the port. Null: no UDP now (the server has it off, or the socket would not open).
     */
    fun endpoint(requestHost: String): LiveUdpEndpoint? {
        if (!features.isEnabled(ServerFeature.LIVE_UDP)) return null
        val bound = channel ?: bind() ?: return null
        val port = settings.publicPort.takeIf { it > 0 } ?: (bound.localAddress as InetSocketAddress).port
        val host = settings.host.ifBlank { requestHost }
        if (host.isBlank()) return null
        return LiveUdpEndpoint(host, port)
    }

    /** The UDP port as it was bound; null before it opened. For the tests. */
    fun boundPort(): Int? = (channel?.localAddress as InetSocketAddress?)?.port

    private fun bind(): DatagramChannel? = synchronized(lock) {
        channel?.let { return it }
        if (stopped) return null
        // A port that would not open is tried again a minute later, not on every open.
        if (failedAtNanos != 0L && System.nanoTime() - failedAtNanos < RETRY_BIND_NANOS) return null
        val opened = try {
            DatagramChannel.open().also { opened ->
                try {
                    opened.setOption(StandardSocketOptions.SO_RCVBUF, SOCKET_BUFFER_BYTES)
                    opened.setOption(StandardSocketOptions.SO_SNDBUF, SOCKET_BUFFER_BYTES)
                    opened.bind(InetSocketAddress(settings.port))
                } catch (e: Exception) {
                    opened.close()
                    throw e
                }
            }
        } catch (e: IOException) {
            failedAtNanos = System.nanoTime().takeIf { it != 0L } ?: 1L
            metrics.udpBindFailed()
            log.warn("The live channel's UDP did not open on port {}: {}", settings.port, e.toString())
            return null
        }
        channel = opened
        repeat(settings.threads.coerceAtLeast(1)) { n ->
            threads += Thread({ receive(opened) }, "live-udp-${n + 1}").apply {
                isDaemon = true
                start()
            }
        }
        log.info("The live channel's UDP listens on port {}", (opened.localAddress as InetSocketAddress).port)
        opened
    }

    private fun receive(channel: DatagramChannel) {
        // One buffer per thread, outside the heap: no allocation for a datagram but its own bytes.
        val buffer = ByteBuffer.allocateDirect(RECEIVE_BYTES)
        while (true) {
            buffer.clear()
            val from = try {
                channel.receive(buffer) as InetSocketAddress?
            } catch (e: ClosedChannelException) {
                return
            } catch (e: IOException) {
                continue
            } ?: continue
            buffer.flip()
            metrics.datagramIn()
            // Longer than any datagram of v2 (and cut by the buffer): not ours.
            if (buffer.remaining() > LiveLimits.MAX_DATAGRAM_BYTES) continue
            val datagram = ByteArray(buffer.remaining()).also(buffer::get)
            try {
                datagram(datagram, from)
            } catch (e: Exception) {
                log.error("Unhandled error in a live datagram", e)
            }
        }
    }

    /** One datagram from [from] (spec §5.3–5.6, §5.8). Internal for the tests. */
    internal fun datagram(datagram: ByteArray, from: InetSocketAddress) {
        val head = LiveDatagrams.head(datagram) ?: return
        // A RESET is the server's only: a phone never sends one.
        if (head.type != LiveDatagrams.TYPE_DATA) return
        val session = sessions.get(head.sessionId)
        if (session == null) {
            reset(head.sessionId, datagram.size, from)
            return
        }
        val path = session.udp ?: session.locked { session.udp ?: LiveUdpPath(session).also { session.udp = it } }
        val plaintext = LiveDatagrams.open(ThreadAead(session.upKey), datagram)
        if (plaintext == null) {
            metrics.authFailed()
            if (path.authFailures.incrementAndGet() > LiveLimits.MAX_AUTH_FAILURES) {
                sessions.end(session, CloseReason.CLOSE_REOPEN)
            }
            return
        }
        var up: Up? = null
        var link: LiveLink? = null
        var attach = false
        session.locked {
            if (session.closed) return
            val highest = path.window.highest
            if (!path.window.accept(head.pn)) return
            val pn = head.pn.toLong() and 0xffffffffL
            if (pn > highest + 1) metrics.lost(pn - highest - 1)
            if (pn > LiveLimits.MAX_DATAGRAMS_PER_KEY) {
                // The phone's key is worn out (spec §5.2): a new session, new keys.
                sessions.end(session, CloseReason.CLOSE_REOPEN)
                return
            }
            if (!features.isEnabled(ServerFeature.LIVE_UDP)) {
                // UDP went off: a session on UDP opens anew and gets the WebSocket v2; any other drops the datagram,
                // and its phone goes to the WebSocket v2 when its Ups go unanswered.
                if (session.link === path.link) sessions.end(session, CloseReason.CLOSE_REOPEN)
                return
            }
            link = path.linkFor(from, newest = pn > highest, bytes = datagram.size) ?: return
            val message = path.pieces.accept(plaintext, clock.millis()) ?: return
            metrics.up(message.size, LiveMetrics.UDP)
            val decoded = try {
                Up.ADAPTER.decode(message)
            } catch (e: Exception) {
                // A newer app's message this server can't read, or garbage: skipped, the session stays.
                metrics.badMessage()
                return
            }
            if (path.confirm(decoded.path_response.toByteArray())) link = path.link
            // A fresh Up on the confirmed address takes the session back from the WebSocket v2 (spec §6); one that was
            // overtaken on the way does not.
            attach = link === path.link && session.link !== path.link && decoded.seq > session.lastSeq
            up = decoded
        }
        val received = up ?: return
        val replyLink = link ?: return
        if (attach) sessions.attach(session, path.link)
        exchange.up(session, received, replyLink)
    }

    /**
     * A RESET for [sessionId] to [to] (spec §5.5): only for a datagram bigger than the answer, once a second per
     * address, [LiveLimits.MAX_RESETS_PER_SECOND] a second in all.
     */
    private fun reset(sessionId: Long, size: Int, to: InetSocketAddress) {
        if (size < LiveDatagrams.MIN_DATA_FOR_RESET) return
        val channel = channel ?: return
        val now = System.nanoTime()
        val second = now / NANOS_PER_SECOND
        if (resetSecond.getAndSet(second) != second) resetsThisSecond.set(0)
        if (resetsThisSecond.incrementAndGet() > LiveLimits.MAX_RESETS_PER_SECOND) return
        val last = resetsTo[to]
        if (last != null && now - last < NANOS_PER_SECOND) return
        resetsTo[to] = now
        if (resetsTo.size > MAX_RESET_ADDRESSES) resetsTo.entries.removeIf { now - it.value >= NANOS_PER_SECOND }
        try {
            channel.send(ByteBuffer.wrap(LiveDatagrams.reset(sessionId, sessions.resetToken(sessionId))), to)
            metrics.datagramOut()
            metrics.reset()
        } catch (e: IOException) {
            // The phone's network is gone: nothing to tell it.
        }
    }

    /**
     * Sends [down] of [session] to [to] as datagrams: in pieces when it is long (spec §5.4), each with the next packet
     * number under the session's key for Downs. [budget]: at most this many bytes may go (an address not confirmed).
     */
    private fun sendDown(
        session: LiveSession,
        path: LiveUdpPath,
        down: ByteArray,
        to: InetSocketAddress,
        budget: Long?,
    ): Int? {
        val channel = channel ?: return null
        val plaintexts = try {
            LiveDatagrams.plaintexts(down, path.nextMessageId.incrementAndGet() and 0xffff)
        } catch (e: IllegalArgumentException) {
            // Over 64 pieces: the server's own bug (spec §5.4).
            metrics.oversize()
            sessions.end(session, CloseReason.CLOSE_REOPEN)
            return null
        }
        val size = plaintexts.sumOf { it.size + LiveLimits.HEADER_BYTES + LiveLimits.TAG_BYTES }
        if (budget != null && size > budget) return null
        val aead = ThreadAead(session.downKey)
        for (plaintext in plaintexts) {
            val datagram = LiveDatagrams.seal(aead, session.id, path.nextPn.incrementAndGet(), plaintext)
            try {
                channel.send(ByteBuffer.wrap(datagram), to)
                metrics.datagramOut()
            } catch (e: IOException) {
                return null
            }
        }
        return size
    }

    /**
     * With `LIVE_UDP` on at the start the port opens at once: a server that restarted after a crash answers the
     * phones' datagrams of its old sessions with their RESET (spec §5.5) before any new open.
     */
    override fun start() {
        running = true
        if (features.isEnabled(ServerFeature.LIVE_UDP)) bind()
    }

    /** After the live sessions' links closed ([LiveSessions.stop], a later phase): the socket and its threads go. */
    override fun stop() {
        running = false
        val closing = synchronized(lock) {
            stopped = true
            channel.also { channel = null }
        }
        try {
            closing?.close()
        } catch (e: IOException) {
            // Closed already.
        }
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = SmartLifecycle.DEFAULT_PHASE - 1

    /**
     * A live session's UDP (spec §5.3–5.6): the window of the phone's packet numbers, its messages in pieces, the
     * address it is on and the one it is asked to confirm, the numbers of the server's own datagrams. Guarded by the
     * session's lock, but the counters.
     */
    inner class LiveUdpPath(session: LiveSession) {
        val window = LiveReplayWindow()
        val pieces = LiveReassembler()
        val nextPn = AtomicInteger()
        val nextMessageId = AtomicInteger()
        val authFailures = AtomicInteger()

        /** The address the phone confirmed: replies to it, pushes and closes go there. */
        var address: InetSocketAddress? = null
            private set

        /** A newer address of the phone, the challenge it got and the bytes each way (3× at most out, spec §5.6). */
        private var candidate: InetSocketAddress? = null
        private var challenge: ByteArray? = null
        private var candidateIn = 0L
        private var candidateOut = 0L

        /** The session's link over UDP: to [address]. */
        val link = UdpLink(session, this)

        /**
         * The link the answer to a datagram from [from] goes back on, [bytes] long; [newest]: its packet number is the
         * highest yet. Null: the datagram is dropped (an older one from an address that is not the phone's now).
         */
        fun linkFor(from: InetSocketAddress, newest: Boolean, bytes: Int): LiveLink? {
            if (from == address) return link
            if (from != candidate) {
                if (!newest) return null
                candidate = from
                challenge = ByteArray(CHALLENGE_BYTES).also(random::nextBytes)
                candidateIn = 0
                candidateOut = 0
            }
            candidateIn += bytes
            return ChallengeLink(link.session, this, from, checkNotNull(challenge))
        }

        /** The phone echoed the challenge of [candidate]: it is the phone's address from now on, the old one forgot. */
        fun confirm(response: ByteArray): Boolean {
            val expected = challenge ?: return false
            if (response.isEmpty() || !MessageDigest.isEqual(response, expected)) return false
            address = candidate
            candidate = null
            challenge = null
            return true
        }

        /** Where a close goes: the confirmed address, else the one being confirmed. */
        fun closeAddress(): InetSocketAddress? = address ?: candidate

        /** Counts [bytes] sent to the candidate; the room left for the next send. */
        fun candidateRoom(): Long = AMPLIFICATION * candidateIn - candidateOut

        fun sentToCandidate(bytes: Int) {
            candidateOut += bytes
        }
    }

    /** A session's confirmed UDP address as its link: replies, pushes and the close. */
    inner class UdpLink(val session: LiveSession, private val path: LiveUdpPath) : LiveLink {
        override val transport: String get() = LiveMetrics.UDP

        /** Under the session's lock, as every send of [LiveExchange]. */
        override fun send(down: ByteArray): Boolean {
            if (session.closed) return false
            val to = path.address ?: return false
            if (path.nextPn.get() >= LiveLimits.MAX_DATAGRAMS_PER_KEY) return false
            return sendDown(session, path, down, to, budget = null) != null
        }

        /**
         * The close goes to the phone as a Down (spec §13). Without a [reason]: only this link goes (the phone moved to
         * the WebSocket v2, nothing to tell); the server stopping tells the phone to open anew.
         */
        override fun close(reason: CloseReason?, goingAway: Boolean) {
            val why = reason ?: (if (goingAway) CloseReason.CLOSE_REOPEN else return)
            session.locked {
                val to = path.closeAddress() ?: return
                val down = Down(ack_up = session.lastSeq, server_time_ms = clock.millis(), close = Close(reason = why))
                sendDown(session, path, Down.ADAPTER.encode(down), to, budget = null)
            }
        }
    }

    /** An address of the phone not confirmed yet (spec §5.6): its challenge, and 3× the bytes it sent at most. */
    private inner class ChallengeLink(
        private val session: LiveSession,
        private val path: LiveUdpPath,
        private val to: InetSocketAddress,
        override val pathChallenge: ByteArray,
    ) : LiveLink {
        override val transport: String get() = LiveMetrics.UDP

        override fun send(down: ByteArray): Boolean {
            if (session.closed) return false
            val sent = sendDown(session, path, down, to, budget = path.candidateRoom()) ?: return false
            path.sentToCandidate(sent)
            return true
        }

        /** The session's close goes by its [UdpLink]. */
        override fun close(reason: CloseReason?, goingAway: Boolean) = Unit
    }

    /** AES-128-GCM with [key] on this thread's own [Cipher] (spec §5.2): one per thread, never shared. */
    private class ThreadAead(private val key: SecretKeySpec) : LiveAead {
        override fun seal(nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
            val cipher = CIPHERS.get()
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            return cipher.doFinal(plaintext)
        }

        override fun open(nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray? = try {
            val cipher = CIPHERS.get()
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertextAndTag)
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    private companion object {
        const val TAG_BITS = 128
        const val CHALLENGE_BYTES = 8
        const val AMPLIFICATION = 3L

        /** Bigger than any datagram of v2: a longer one shows as cut and is dropped. */
        const val RECEIVE_BYTES = 2_048

        /** The kernel's buffers of the socket: a burst of a few thousand phones waits there, not lost. */
        const val SOCKET_BUFFER_BYTES = 4 * 1024 * 1024

        /** Addresses remembered for the RESETs' once a second; past this, the older than a second go. */
        const val MAX_RESET_ADDRESSES = 10_000

        val NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1)
        val RETRY_BIND_NANOS = TimeUnit.MINUTES.toNanos(1)
        val CIPHERS: ThreadLocal<Cipher> = ThreadLocal.withInitial { Cipher.getInstance("AES/GCM/NoPadding") }
    }
}
