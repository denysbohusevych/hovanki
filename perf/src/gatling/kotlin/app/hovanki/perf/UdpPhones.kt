package app.hovanki.perf

import app.hovanki.shared.live.LiveAead
import app.hovanki.shared.live.LiveDatagrams
import app.hovanki.shared.live.LiveLimits
import app.hovanki.shared.live.LiveReassembler
import app.hovanki.shared.live.LiveReplayWindow
import app.hovanki.shared.live.LiveUplink
import app.hovanki.shared.live.v2.Down
import app.hovanki.shared.live.v2.Up
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.SocketLimits
import okio.ByteString
import java.io.IOException
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.security.GeneralSecurityException
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import app.hovanki.shared.live.v2.GamePhase as WirePhase

/**
 * A live session of the live channel v2 as the open hands it to a phone (docs/live-v2/spec.md §4): its id, its two
 * keys, its RESET's tag and where its datagrams go.
 */
class UdpLive(
    val sessionId: Long,
    upKey: ByteArray,
    downKey: ByteArray,
    val resetToken: ByteArray,
    val address: InetSocketAddress,
) {
    internal val up: LiveAead = JceAead(SecretKeySpec(upKey, "AES"))
    internal val down: LiveAead = JceAead(SecretKeySpec(downKey, "AES"))
}

/**
 * One player's phone of [UdpGames] on the live channel v2's UDP: its walk, its game, and what the app keeps across the
 * live sessions it goes through (the phase it shows, the chat cursor, the fixes not acknowledged yet). Created on the
 * HTTP threads; once [UdpPhones.add]ed only its worker touches it.
 */
class UdpPhone(
    val name: String,
    val token: String,
    val isHost: Boolean,
    /** The generator's own: what it needs of the phone's game. */
    val tag: Any?,
    private val walker: Walker,
) {
    /** The phase the phone shows: from the Downs' `game` section. */
    @Volatile var phase: GamePhase = GamePhase.LOBBY
        internal set

    /** Over: it left, or its session closed for good. */
    @Volatile var isOver = false
        internal set

    internal var worker = -1
    internal var session: PhoneSession? = null
    internal var chatAfter = 0L
    internal var dueNanos = 0L

    /** The fixes no Down acknowledged yet, oldest first; each with the first Up of its session that carried it. */
    internal val fixes = ArrayDeque<Unacked>()
    private var lastFixMillis = 0L

    internal class Unacked(val sample: LocationSample, var firstSeq: Int)

    /** The fixes the location provider gave since the last one: one every sync interval, as the app's outbox. */
    internal fun walk(nowMillis: Long) {
        if (lastFixMillis == 0L) {
            fixes.addLast(Unacked(walker.fix(nowMillis), 0))
            lastFixMillis = nowMillis
            return
        }
        while (lastFixMillis + FIX_EVERY_MILLIS <= nowMillis) {
            lastFixMillis += FIX_EVERY_MILLIS
            fixes.addLast(Unacked(walker.fix(lastFixMillis), 0))
            if (fixes.size > LiveLimits.MAX_FIXES_PER_UP) fixes.removeFirst()
        }
    }

    internal companion object {
        val FIX_EVERY_MILLIS = GameRules().syncIntervalSeconds * 1000L
    }
}

/** One live session of a phone: what spec §5 keeps per session. Worker-only. */
internal class PhoneSession(val live: UdpLive, val socket: PhoneSocket) {
    var pn = 0
    var messageId = 0
    var seq = 0
    var ackState = 0
    val window = LiveReplayWindow()
    val pieces = LiveReassembler()
    var pathResponse: ByteString = ByteString.EMPTY
    val pending = ArrayDeque<Pending>()

    class Pending(val seq: Int, val sentNanos: Long, val sentMillis: Long, val phase: GamePhase)
}

/** A UDP socket "connected" to the server and the phones' sessions on it, by id: one, or [UdpPhones]' share. */
internal class PhoneSocket(val channel: DatagramChannel) {
    lateinit var key: SelectionKey
    val phones = HashMap<Long, UdpPhone>()

    /** Phones it took in its life: a full socket takes no more, even after some left. */
    var taken = 0
}

/**
 * The phones of [UdpGames] (docs/perf.md, «UDP»), each driven as the app's `LiveGameConnection` drives its live session
 * over UDP (docs/live-v2/client.md §11): a socket on a port of its own for every session (every phone has its own
 * address), the first Up at once, the echo of the server's `path_challenge` at once, then an Up every sync interval in
 * a round and every [LiveLimits.IDLE_UP_MILLIS] in the lobby; every fix not acknowledged rides along, the newest state is
 * acknowledged, the chat cursor moves. The Downs are decrypted, checked against the window, put together and read; the
 * states are not applied: the server can't tell, and the generator's CPU stays for the phones.
 *
 * Each sync is a request `sync <phase>` in [stats] from its Up to the Down that answers it, as Gatling names the syncs
 * of the other transports; an Up without an answer in [SocketLimits.REPLY_TIMEOUT_MILLIS] fails. [workers] threads
 * share the phones: each owns its phones' sockets in its own selector and their next Ups in its own queue.
 * [phonesPerSocket] over 1 puts that many sessions on one socket, as phones behind one NAT's port would look, for a
 * machine whose limit of open files is below the phones (the server finds a session by its id, not by its address).
 */
class UdpPhones(
    workers: Int,
    private val phonesPerSocket: Int,
    private val stats: ClientStats,
    private val watch: LoadWatch,
    private val events: Events,
) {
    /** What the game around the phones hears of them, on the phone's worker thread: never block in it. */
    interface Events {
        /** A Down came and was read: the phase may have changed. */
        fun onDown(phone: UdpPhone)

        /** The session is over ([why]: a RESET or the server's `Close`): the phone opens a new one, or leaves. */
        fun onClosed(phone: UdpPhone, why: String)
    }

    @Volatile private var running = true
    private val next = AtomicInteger()
    private val all = List(workers.coerceAtLeast(1)) { Worker(it) }

    /** What went over the network and how it went, for the run's summary. */
    val counters = Counters()

    class Counters {
        val ups = LongAdder()
        val answers = LongAdder()
        val pushes = LongAdder()
        val challenges = LongAdder()
        val unanswered = LongAdder()
        val datagramsOut = LongAdder()
        val bytesOut = LongAdder()
        val datagramsIn = LongAdder()
        val bytesIn = LongAdder()
        val dropped = LongAdder()
        val sendFailed = LongAdder()
        val unreachable = LongAdder()
        val resets = LongAdder()
        val closes = LongAdder()
        val errors = LongAdder()
        val sockets = AtomicInteger()
    }

    init {
        all.forEach { it.thread.start() }
    }

    /** Puts [phone] on [live]: a socket of its own (or a share of one), the first Up at once. From any thread. */
    fun add(phone: UdpPhone, live: UdpLive) {
        if (phone.worker < 0) phone.worker = Math.floorMod(next.getAndIncrement(), all.size)
        all[phone.worker].adopt(phone, live)
    }

    /** The phone leaves: its session ends, it sends nothing more. From any thread. */
    fun remove(phone: UdpPhone) {
        phone.isOver = true
        if (phone.worker >= 0) all[phone.worker].wake()
    }

    /** Stops every worker and closes every socket. */
    fun stop() {
        running = false
        all.forEach { it.wake() }
        all.forEach { it.thread.join(STOP_MILLIS) }
    }

    private inner class Worker(index: Int) : Runnable {
        val thread = Thread(this, "udp-phones-${index + 1}").apply { isDaemon = true }
        private val selector: Selector = Selector.open()
        private val adopted = ConcurrentLinkedQueue<Pair<UdpPhone, UdpLive>>()

        /** The phones' next Ups by time. A phone's [UdpPhone.dueNanos] is the truth: an older wake is skipped. */
        private val wakes = PriorityQueue<Wake>(compareBy { it.atNanos })
        private val buffer: ByteBuffer = ByteBuffer.allocateDirect(RECEIVE_BYTES)

        /** The socket new sessions share while it has room ([phonesPerSocket] over 1). */
        private var filling: PhoneSocket? = null

        private inner class Wake(val phone: UdpPhone, val atNanos: Long)

        fun adopt(phone: UdpPhone, live: UdpLive) {
            adopted.add(phone to live)
            wake()
        }

        fun wake() {
            selector.wakeup()
        }

        override fun run() {
            try {
                while (running) {
                    while (true) {
                        val (phone, live) = adopted.poll() ?: break
                        open(phone, live)
                    }
                    sendDue()
                    val first = wakes.peek()
                    val waitMillis =
                        if (first == null) IDLE_WAIT_MILLIS else ((first.atNanos - System.nanoTime()) / 1_000_000)
                    if (waitMillis <= 0) {
                        selector.selectNow()
                    } else {
                        selector.select(waitMillis.coerceAtMost(IDLE_WAIT_MILLIS))
                    }
                    val keys = selector.selectedKeys().iterator()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        keys.remove()
                        if (key.isValid && key.isReadable) receive(key.attachment() as PhoneSocket)
                    }
                }
            } finally {
                selector.keys().forEach { key ->
                    try {
                        key.channel().close()
                    } catch (e: IOException) {
                        // Closed already.
                    }
                }
                selector.close()
            }
        }

        private fun open(phone: UdpPhone, live: UdpLive) {
            phone.session?.let { leave(phone, it) }
            if (phone.isOver) return
            val socket = try {
                socketFor(live)
            } catch (e: IOException) {
                counters.sendFailed.increment()
                events.onClosed(phone, "socket: ${e.javaClass.simpleName}")
                return
            }
            socket.phones[live.sessionId] = phone
            socket.taken++
            phone.session = PhoneSession(live, socket)
            // A new session numbers its Ups anew: the fixes go again in its first one.
            phone.fixes.forEach { it.firstSeq = 0 }
            schedule(phone, System.nanoTime())
        }

        private fun socketFor(live: UdpLive): PhoneSocket {
            filling?.takeIf { it.taken < phonesPerSocket && it.channel.isOpen }?.let { return it }
            val channel = DatagramChannel.open()
            try {
                channel.configureBlocking(false)
                channel.connect(live.address)
                val socket = PhoneSocket(channel)
                socket.key = channel.register(selector, SelectionKey.OP_READ, socket)
                counters.sockets.incrementAndGet()
                filling = socket
                return socket
            } catch (e: IOException) {
                channel.close()
                throw e
            }
        }

        /** [phone]'s [session] ends here: off its socket, which closes once nobody is on it. */
        private fun leave(phone: UdpPhone, session: PhoneSession) {
            phone.session = null
            val socket = session.socket
            socket.phones.remove(session.live.sessionId)
            if (socket.phones.isEmpty() && (socket.taken >= phonesPerSocket || socket !== filling)) {
                socket.key.cancel()
                counters.sockets.decrementAndGet()
                try {
                    socket.channel.close()
                } catch (e: IOException) {
                    // Closed already.
                }
            }
        }

        private fun schedule(phone: UdpPhone, atNanos: Long) {
            phone.dueNanos = atNanos
            wakes.add(Wake(phone, atNanos))
        }

        private fun sendDue() {
            val now = System.nanoTime()
            while (true) {
                val wake = wakes.peek() ?: return
                if (wake.atNanos > now) return
                wakes.poll()
                val phone = wake.phone
                if (wake.atNanos != phone.dueNanos) continue
                val session = phone.session ?: continue
                if (phone.isOver) {
                    leave(phone, session)
                    continue
                }
                up(phone, session, now)
            }
        }

        private fun up(phone: UdpPhone, session: PhoneSession, nowNanos: Long) {
            val nowMillis = System.currentTimeMillis()
            expire(session, nowNanos, nowMillis)
            phone.walk(nowMillis)
            val seq = ++session.seq
            for (fix in phone.fixes) if (fix.firstSeq == 0) fix.firstSeq = seq
            val up = LiveUplink.up(
                seq = seq,
                ackState = session.ackState,
                timeMillis = nowMillis,
                samples = phone.fixes.map { it.sample },
                chatAfter = phone.chatAfter,
                pathResponse = session.pathResponse,
            )
            session.pathResponse = ByteString.EMPTY
            val message = Up.ADAPTER.encode(up)
            session.messageId = (session.messageId + 1) and 0xffff
            for (plaintext in LiveDatagrams.plaintexts(message, session.messageId)) {
                val datagram = LiveDatagrams.seal(session.live.up, session.live.sessionId, ++session.pn, plaintext)
                try {
                    if (session.socket.channel.write(ByteBuffer.wrap(datagram)) == 0) counters.sendFailed.increment()
                    counters.datagramsOut.increment()
                    counters.bytesOut.add(datagram.size.toLong())
                } catch (e: IOException) {
                    counters.sendFailed.increment()
                }
            }
            counters.ups.increment()
            session.pending.addLast(PhoneSession.Pending(seq, nowNanos, nowMillis, phone.phase))
            schedule(phone, nowNanos + paceNanos(phone.phase))
        }

        /** The Ups left without an answer too long: failed syncs. */
        private fun expire(session: PhoneSession, nowNanos: Long, nowMillis: Long) {
            while (true) {
                val oldest = session.pending.firstOrNull() ?: return
                if (nowNanos - oldest.sentNanos < REPLY_TIMEOUT_NANOS) return
                session.pending.removeFirst()
                counters.unanswered.increment()
                stats.record(syncName(oldest.phase), oldest.sentMillis, 0, ok = false, message = NO_ANSWER)
                watch.record(SocketLimits.REPLY_TIMEOUT_MILLIS, failed = true, nowMillis = nowMillis)
            }
        }

        private fun receive(socket: PhoneSocket) {
            while (true) {
                buffer.clear()
                val read = try {
                    socket.channel.read(buffer)
                } catch (e: PortUnreachableException) {
                    // The server's port is closed (ICMP): as lost as UDP loses.
                    counters.unreachable.increment()
                    return
                } catch (e: IOException) {
                    return
                }
                if (read <= 0) return
                buffer.flip()
                val datagram = ByteArray(buffer.remaining()).also(buffer::get)
                counters.datagramsIn.increment()
                counters.bytesIn.add(datagram.size.toLong())
                datagram(socket, datagram)
            }
        }

        /** One datagram on [socket], for the session its header names. */
        private fun datagram(socket: PhoneSocket, datagram: ByteArray) {
            val head = LiveDatagrams.head(datagram) ?: return dropped()
            val phone = socket.phones[head.sessionId] ?: return dropped()
            val session = phone.session?.takeIf { it.live.sessionId == head.sessionId } ?: return dropped()
            if (head.type == LiveDatagrams.TYPE_RESET) {
                if (!LiveDatagrams.isReset(datagram, session.live.sessionId, session.live.resetToken)) return dropped()
                counters.resets.increment()
                end(phone, session, "reset")
                return
            }
            if (head.type != LiveDatagrams.TYPE_DATA) return dropped()
            val plaintext = LiveDatagrams.open(session.live.down, datagram) ?: return dropped()
            if (!session.window.accept(head.pn)) return dropped()
            val message = session.pieces.accept(plaintext, System.currentTimeMillis()) ?: return
            val down = try {
                Down.ADAPTER.decode(message)
            } catch (e: IOException) {
                return dropped()
            }
            down(phone, session, down)
        }

        private fun dropped() {
            counters.dropped.increment()
        }

        private fun down(phone: UdpPhone, session: PhoneSession, down: Down) {
            val nowNanos = System.nanoTime()
            val nowMillis = System.currentTimeMillis()
            if (down.ack_up > 0) {
                while (phone.fixes.firstOrNull()?.let { it.firstSeq in 1..down.ack_up } ==
                    true
                ) {
                    phone.fixes.removeFirst()
                }
            }
            val challenge = down.path_challenge.size > 0 && down.state_id == 0
            if (down.reply_to == 0) {
                counters.pushes.increment()
            } else {
                val asked = session.pending.firstOrNull { it.seq == down.reply_to }
                if (asked != null) {
                    session.pending.remove(asked)
                    val tookMillis = (nowNanos - asked.sentNanos) / 1_000_000
                    if (challenge) {
                        // The check of the new address: the server synced, the state waits for the echo, which goes
                        // now (spec §5.6). Its own name: a Down without a state is no sync's time.
                        counters.challenges.increment()
                        stats.record(CHALLENGE, asked.sentMillis, tookMillis, ok = true)
                    } else {
                        val error = down.error
                        if (error != null) counters.errors.increment() else counters.answers.increment()
                        stats.record(
                            syncName(asked.phase),
                            asked.sentMillis,
                            tookMillis,
                            ok = error == null,
                            message = error?.let { "sync: ${it.code.name} ${it.reason}".trim() },
                        )
                        watch.record(tookMillis, failed = error != null, nowMillis = nowMillis)
                    }
                }
            }
            if (down.path_challenge.size > 0) session.pathResponse = down.path_challenge
            if (down.state_id != 0 && down.state_id > session.ackState) session.ackState = down.state_id
            down.state?.game?.let { game -> phone.phase = phase(game.phase) }
            if (!down.chat_push) down.chat.maxOfOrNull { it.seq }?.let { phone.chatAfter = maxOf(phone.chatAfter, it) }
            val close = down.close
            if (close != null) {
                counters.closes.increment()
                end(phone, session, "close ${close.reason.name}")
                return
            }
            events.onDown(phone)
            if (session.pathResponse.size > 0 || down.chat_more) schedule(phone, nowNanos)
        }

        private fun end(phone: UdpPhone, session: PhoneSession, why: String) {
            leave(phone, session)
            events.onClosed(phone, why)
        }
    }

    /** The app's pace: the game's sync interval in a round, the longest pause in the lobby and on the results. */
    private fun paceNanos(phase: GamePhase): Long = when (phase) {
        GamePhase.LOBBY, GamePhase.FINISHED -> LiveLimits.IDLE_UP_MILLIS
        else -> UdpPhone.FIX_EVERY_MILLIS
    } * 1_000_000

    private companion object {
        const val RECEIVE_BYTES = 2_048
        const val IDLE_WAIT_MILLIS = 100L
        const val STOP_MILLIS = 5_000L
        const val NO_ANSWER = "sync: no answer"
        const val CHALLENGE = "udp: address check"
        val REPLY_TIMEOUT_NANOS = SocketLimits.REPLY_TIMEOUT_MILLIS * 1_000_000

        fun syncName(phase: GamePhase) = "sync $phase"

        fun phase(wire: WirePhase): GamePhase = when (wire) {
            WirePhase.GAME_PHASE_HIDING -> GamePhase.HIDING
            WirePhase.GAME_PHASE_SEEKING -> GamePhase.SEEKING
            WirePhase.GAME_PHASE_FINISHED -> GamePhase.FINISHED
            else -> GamePhase.LOBBY
        }
    }
}

/** AES-128-GCM with one key on this thread's own [Cipher] (spec §5.2), as the server's. */
private class JceAead(private val key: SecretKeySpec) : LiveAead {
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

    private companion object {
        const val TAG_BITS = 128
        val CIPHERS: ThreadLocal<Cipher> = ThreadLocal.withInitial { Cipher.getInstance("AES/GCM/NoPadding") }
    }
}
