package app.hovanki.e2e.bot

import app.hovanki.client.location.LocationProvider
import app.hovanki.client.network.GameSocket
import app.hovanki.client.network.GameSocketOpener
import app.hovanki.client.network.live.JdkLiveAeads
import app.hovanki.client.network.live.LiveDatagramOpener
import app.hovanki.client.network.live.LiveDatagramSocket
import app.hovanki.client.network.live.LiveSocket
import app.hovanki.client.network.live.LiveSocketOpener
import app.hovanki.client.storage.SecureStore
import app.hovanki.client.tracking.BackgroundTracker
import app.hovanki.client.tracking.HiderAlert
import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.route.Route
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.live.LiveAead
import app.hovanki.shared.live.LiveClientStates
import app.hovanki.shared.live.LiveDatagrams
import app.hovanki.shared.live.LiveDownlink
import app.hovanki.shared.live.LiveReassembler
import app.hovanki.shared.live.LiveReplayWindow
import app.hovanki.shared.live.LiveSnapshots
import app.hovanki.shared.live.v2.Down
import app.hovanki.shared.live.v2.Up
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.ClientFrame
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LiveOpenResponse
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.ServerFrame
import app.hovanki.shared.protocol.protocolJson
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import okhttp3.Interceptor
import okhttp3.Response
import okio.ByteString.Companion.decodeBase64
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration

// The simulated phone around the real client code: clock, GPS, network, the OS background service and storage.
// Everything here survives an app restart (BotPlayer.killApp), like the hardware does.

/** The phone's own clock; [skewMillis] makes it wrong, like a manually set or drifting clock. */
class DeviceClock(@Volatile var skewMillis: Long = 0) {
    fun now(): Long = System.currentTimeMillis() + skewMillis
}

/**
 * The phone's GPS as the app's [LocationProvider]: GameSessionManager consumes it exactly like
 * FusedLocationProvider or CLLocationManager. Reports the position on the current [Route] with [GpsNoise];
 * timestamps are device-clock time, as on a real phone. Movement itself happens in real time.
 */
class FakeGps(start: GeoPoint, private val noise: GpsNoise, private val clock: DeviceClock) : LocationProvider {
    private class Movement(val route: Route, val startedAtMillis: Long)

    @Volatile private var movement = Movement(Route.stay(start), System.currentTimeMillis())

    @Volatile private var pendingJump: Pair<Double, Double>? = null

    /** GPS switched off (or no sky): no fixes at all, while the app keeps syncing. */
    @Volatile var isEnabled: Boolean = true

    /** A mock-location app is active: fixes carry `isMock`. */
    @Volatile var isMocked: Boolean = false

    @Volatile var hasPermission: Boolean = true

    private val emitted = AtomicInteger()

    /** Fixes handed to the app so far. */
    val fixesEmitted: Int get() = emitted.get()

    val truePosition: GeoPoint
        get() = movement.let { it.route.positionAt(System.currentTimeMillis() - it.startedAtMillis) }

    /** Real time when the current route ends. */
    val arrivesAtMillis: Long get() = movement.let { it.startedAtMillis + it.route.durationMillis }

    fun follow(route: Route) {
        movement = Movement(route, System.currentTimeMillis())
    }

    fun walkTo(target: GeoPoint, speedMetersPerSecond: Double): Route =
        Route(listOf(truePosition, target), speedMetersPerSecond).also(::follow)

    /** Instantly somewhere else: what a GPS spoofing app does. */
    fun teleport(to: GeoPoint) = follow(Route.stay(to))

    /** The next single fix is off by the given offset (multipath jump), accuracy looks normal. */
    fun jumpOnce(eastMeters: Double, northMeters: Double) {
        pendingJump = eastMeters to northMeters
    }

    override fun hasPermission(): Boolean = hasPermission

    override fun locationUpdates(intervalMillis: Long): Flow<LocationSample> = flow {
        while (true) {
            if (isEnabled) {
                emit(nextFix())
                emitted.incrementAndGet()
            }
            delay(intervalMillis)
        }
    }

    private fun nextFix(): LocationSample {
        val jump = pendingJump
        pendingJump = null
        val truth = truePosition
        val fix = synchronized(noise) { noise.fix(truth, clock.now(), isMocked) }
        return if (jump == null) fix else fix.copy(point = truth.moveBy(jump.first, jump.second))
    }
}

/**
 * One HTTP exchange as seen by the phone. [status] is null when the request failed with an I/O error; with
 * [isResponseLost] the server answered [status] but the answer never reached the app.
 */
class Exchange(
    val method: String,
    val path: String,
    val status: Int?,
    val durationMillis: Long,
    val body: String?,
    val isResponseLost: Boolean = false,
)

/**
 * The phone's network, as an OkHttp interceptor under the app's real Ktor/OkHttp client, and around its sockets of the
 * live channel ([sockets], docs/adr/0015-websockets.md), whose frames no interceptor sees. Reports every exchange (a
 * socket's answered sync is one too, method `WS`) and breaks like a mobile network does:
 * - [isOnline] off: requests fail with an [IOException] before reaching the server, like in a tunnel, and open sockets
 *   break;
 * - [loseResponseTo]: the server gets the request and answers, the answer is lost on the way back;
 * - [latency]: every request and every frame the phone sends waits this long before it goes out;
 * - [failRequests]: a share of requests fails before reaching the server; a socket frame that fails breaks its socket.
 */
class FakeNetwork(
    private val onExchange: (Exchange) -> Unit,
    /** The chat messages a socket pushed ([ServerFrame.Chat], v2's chat push), as they reach the phone. */
    private val onPushedChat: (List<ChatMessage>) -> Unit = {},
    /**
     * Every snapshot the live channel v2 brings, answer or push, put together from its Downs as the app does
     * ([liveSockets]): v2's Downs are binary, so the audit reads them here.
     */
    private val onLiveSnapshot: (GameSnapshot) -> Unit = {},
) : Interceptor {
    private val online = MutableStateFlow(true)

    var isOnline: Boolean
        get() = online.value
        set(value) {
            online.value = value
        }

    @Volatile var latency: Duration = Duration.ZERO

    private class LostResponses(val pathSuffix: String, var left: Int)

    private val lostResponses = mutableListOf<LostResponses>()

    private class Flakiness(val rate: Double, val random: Random)

    @Volatile private var flakiness: Flakiness? = null

    /** No UDP gets through (a network that lets only TCP out); HTTP and WebSockets work. */
    @Volatile var blocksUdp: Boolean = false

    @Volatile private var datagramLoss: Flakiness? = null

    /** Bumped by [changeAddress]: the phone's next datagram goes from a new port. */
    @Volatile private var address = 0

    /** From now on, [rate] of the datagrams both ways (0..1, drawn from [seed]) are lost; 0 stops it. */
    fun loseDatagrams(rate: Double, seed: Long = 0) {
        require(rate in 0.0..1.0)
        datagramLoss = if (rate == 0.0) null else Flakiness(rate, Random(seed))
    }

    /**
     * The phone's address changes as a NAT's mapping does, or Wi-Fi → mobile without the phone noticing: its next
     * datagram reaches the server from a new port, and the old port is gone.
     */
    fun changeAddress() {
        address++
    }

    /** The next [times] responses to requests whose path ends with [pathSuffix] are lost after the server answered. */
    fun loseResponseTo(pathSuffix: String, times: Int = 1) {
        require(times > 0)
        synchronized(lostResponses) { lostResponses += LostResponses(pathSuffix, times) }
    }

    /** From now on, [rate] of the requests (0..1, drawn from [seed]) fail before reaching the server; 0 stops it. */
    fun failRequests(rate: Double, seed: Long = 0) {
        require(rate in 0.0..1.0)
        flakiness = if (rate == 0.0) null else Flakiness(rate, Random(seed))
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!isOnline) throw IOException("No network (simulated outage)")
        val request = chain.request()
        val started = System.nanoTime()
        val wait = latency
        if (wait.isPositive()) Thread.sleep(wait.inWholeMilliseconds)
        if (isFlaky()) {
            onExchange(Exchange(request.method, request.url.encodedPath, null, elapsedMillis(started), null))
            throw IOException("Request failed (simulated bad network)")
        }
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            // Cancelled by the app itself (it was closed, or left the game mid-poll): not a network failure.
            if (!chain.call().isCanceled()) {
                onExchange(Exchange(request.method, request.url.encodedPath, null, elapsedMillis(started), null))
            }
            throw e
        }
        if (takeLostResponse(request.url.encodedPath)) {
            response.close()
            onExchange(
                Exchange(
                    request.method,
                    request.url.encodedPath,
                    response.code,
                    elapsedMillis(started),
                    body = null,
                    isResponseLost = true,
                ),
            )
            throw IOException("Response lost (simulated)")
        }
        // A socket's upgrade has no body to read: what follows are the socket's frames.
        val body = if (response.code == SWITCHING_PROTOCOLS) null else response.peekBody(MAX_BODY_BYTES).string()
        onExchange(Exchange(request.method, request.url.encodedPath, response.code, elapsedMillis(started), body))
        return response
    }

    private fun isFlaky(): Boolean {
        val flaky = flakiness ?: return false
        return synchronized(flaky) { flaky.random.nextDouble() } < flaky.rate
    }

    /** The app's sockets of the live channel over this network: [opener] opens them for real, through [intercept]. */
    fun sockets(opener: GameSocketOpener): GameSocketOpener = GameSocketOpener { session ->
        SimulatedSocket(opener.open(session), ApiRoutes.socket(session.gameId))
    }

    /**
     * A socket on this network: it breaks when the network goes, its frames wait out the [latency] and may fail, and
     * the answer to every sync is reported like an HTTP exchange (with its time since the sync went out).
     */
    private inner class SimulatedSocket(private val socket: GameSocket, private val path: String) : GameSocket {
        private val sentAt = ConcurrentHashMap<Long, Long>()

        @Volatile private var brokenByNetwork = false

        override suspend fun receive(): String? {
            val text = coroutineScope {
                val outage = launch {
                    online.first { !it }
                    breakDown()
                }
                try {
                    socket.receive()
                } finally {
                    outage.cancel()
                }
            } ?: return null
            report(text)
            return text
        }

        override suspend fun send(text: String) {
            val wait = latency
            if (wait.isPositive()) delay(wait)
            if (!isOnline || isFlaky()) {
                breakDown()
                throw IOException("Frame failed (simulated bad network)")
            }
            seqOf(text)?.let { sentAt[it] = System.nanoTime() }
            socket.send(text)
        }

        /** No close frame comes through a network that is gone. */
        override suspend fun closeCode(): Int? = if (brokenByNetwork) null else socket.closeCode()

        override suspend fun close() = socket.close()

        private suspend fun breakDown() {
            brokenByNetwork = true
            socket.close()
        }

        private fun report(text: String) {
            val frame = try {
                protocolJson.decodeFromString(ServerFrame.serializer(), text)
            } catch (e: SerializationException) {
                return
            }
            val (seq, status) = when (frame) {
                is ServerFrame.Snapshot -> frame.seq to 200

                is ServerFrame.Error -> frame.seq to 400

                ServerFrame.Poke -> return

                is ServerFrame.Chat -> {
                    onPushedChat(frame.messages)
                    return
                }
            }
            val started = sentAt.remove(seq) ?: return
            onExchange(Exchange(SOCKET_METHOD, path, status, elapsedMillis(started), text.takeIf { status == 200 }))
        }

        private fun seqOf(text: String): Long? = try {
            (protocolJson.decodeFromString(ClientFrame.serializer(), text) as? ClientFrame.Sync)?.seq
        } catch (e: SerializationException) {
            null
        }
    }

    /**
     * The app's WebSockets of the live channel v2 over this network (docs/live-v2/spec.md §6), like [sockets]: an
     * answered Up is reported as an exchange (method [LIVE_METHOD]), every snapshot the Downs make as [onLiveSnapshot].
     */
    fun liveSockets(opener: LiveSocketOpener): LiveSocketOpener = LiveSocketOpener { session, live ->
        SimulatedLiveSocket(opener.open(session, live), taps.computeIfAbsent(live.sessionId) { LiveTap() })
    }

    /** The phone's view of each live session, by its id: the WebSockets and the UDP of one session share it. */
    private val taps = ConcurrentHashMap<String, LiveTap>()

    /** What the phone shows of a live session, put together from the Downs as they pass (spec §8.6, §11). */
    private class LiveTap {
        private val states = LiveClientStates()
        private val snapshots = LiveSnapshots()

        /** The Ups' send times, by their numbers: the answers' durations. */
        val sentAt = ConcurrentHashMap<Int, Long>()

        @Synchronized
        fun apply(down: Down): GameSnapshot? {
            // The check of a new UDP address carries no state, and the phone shows nothing (spec §5.6).
            if (down.path_challenge.size > 0 && down.state_id == 0) return null
            if (!states.apply(down.state_id, down.base_state_id, down.state)) return null
            if (down.chat_push && down.state_id == 0) return null
            val shown = states.shown ?: return null
            val chat = if (down.chat_push) emptyList() else down.chat.map(LiveDownlink::chatMessage)
            return snapshots.toGameSnapshot(shown, down.server_time_ms, chat)
        }
    }

    /** A WebSocket v2 on this network: breaks, waits and fails like [SimulatedSocket]. */
    private inner class SimulatedLiveSocket(private val socket: LiveSocket, private val tap: LiveTap) : LiveSocket {
        @Volatile private var brokenByNetwork = false

        override suspend fun receive(): ByteArray? {
            val bytes = coroutineScope {
                val outage = launch {
                    online.first { !it }
                    breakDown()
                }
                try {
                    socket.receive()
                } finally {
                    outage.cancel()
                }
            } ?: return null
            report(bytes)
            return bytes
        }

        override suspend fun send(bytes: ByteArray) {
            val wait = latency
            if (wait.isPositive()) delay(wait)
            if (!isOnline || isFlaky()) {
                breakDown()
                throw IOException("Frame failed (simulated bad network)")
            }
            runCatching { Up.ADAPTER.decode(bytes).seq }.getOrNull()?.let { tap.sentAt[it] = System.nanoTime() }
            socket.send(bytes)
        }

        override suspend fun closeCode(): Int? = if (brokenByNetwork) null else socket.closeCode()

        override suspend fun close() = socket.close()

        private suspend fun breakDown() {
            brokenByNetwork = true
            socket.close()
        }

        private fun report(bytes: ByteArray) {
            val down = try {
                Down.ADAPTER.decode(bytes)
            } catch (e: IOException) {
                return
            }
            reportDown(down, tap, LIVE_METHOD, ApiRoutes.LIVE_WS)
        }
    }

    /** What a Down brings the phone, for the scenario: the pushed chat, the snapshot for the audit, an answer's time. */
    private fun reportDown(down: Down, tap: LiveTap, method: String, path: String) {
        if (down.chat_push && down.chat.isNotEmpty()) onPushedChat(down.chat.map(LiveDownlink::chatMessage))
        tap.apply(down)?.let(onLiveSnapshot)
        val started = (if (down.reply_to != 0) tap.sentAt.remove(down.reply_to) else null) ?: return
        // The check of a new address answers the Up before the state does: the exchange is the state's.
        if (down.path_challenge.size > 0 && down.state_id == 0) return
        val status = if (down.error != null) 400 else 200
        onExchange(Exchange(method, path, status, elapsedMillis(started), body = null))
    }

    /**
     * The app's UDP of the live channel v2 over this network (docs/live-v2/spec.md §5): [opener] opens real sockets to
     * the server; a datagram is lost while the network is down or [blocksUdp], or to [loseDatagrams], and waits out
     * the [latency]; [changeAddress] moves the phone to a new port behind its socket. The Downs are read with the
     * session's key, as the app reads them, for [onLiveSnapshot]'s audit; an answered Up is an exchange ([UDP_METHOD]).
     */
    fun liveDatagrams(opener: LiveDatagramOpener): LiveDatagramOpener = LiveDatagramOpener { live ->
        SimulatedDatagramSocket(opener, live, taps.computeIfAbsent(live.sessionId) { LiveTap() }, udpReader(live))
    }

    private val udpReaders = ConcurrentHashMap<String, UdpReader>()

    private fun udpReader(live: LiveOpenResponse): UdpReader = udpReaders.computeIfAbsent(live.sessionId) {
        UdpReader(
            up = JdkLiveAeads.forKey(checkNotNull(live.upKey.decodeBase64()).toByteArray()),
            down = JdkLiveAeads.forKey(checkNotNull(live.downKey.decodeBase64()).toByteArray()),
        )
    }

    /** One session's datagrams as the app reads them: decrypted, once each, put together. */
    private class UdpReader(private val up: LiveAead, private val down: LiveAead) {
        private val upPieces = LiveReassembler()
        private val downWindow = LiveReplayWindow()
        private val downPieces = LiveReassembler()

        @Synchronized
        fun up(datagram: ByteArray): Up? {
            val plaintext = LiveDatagrams.open(up, datagram) ?: return null
            val message = upPieces.accept(plaintext, System.currentTimeMillis()) ?: return null
            return runCatching { Up.ADAPTER.decode(message) }.getOrNull()
        }

        @Synchronized
        fun down(datagram: ByteArray): Down? {
            val head = LiveDatagrams.head(datagram)?.takeIf { it.type == LiveDatagrams.TYPE_DATA } ?: return null
            val plaintext = LiveDatagrams.open(down, datagram) ?: return null
            if (!downWindow.accept(head.pn)) return null
            val message = downPieces.accept(plaintext, System.currentTimeMillis()) ?: return null
            return runCatching { Down.ADAPTER.decode(message) }.getOrNull()
        }
    }

    /** A UDP socket of the app on this network. Its sends and receives take turns, as the app's do. */
    private inner class SimulatedDatagramSocket(
        private val opener: LiveDatagramOpener,
        private val live: LiveOpenResponse,
        private val tap: LiveTap,
        private val reader: UdpReader,
    ) : LiveDatagramSocket {
        @Volatile private var socket: LiveDatagramSocket? = null
        private var openedOn = address

        @Volatile private var isClosed = false

        private suspend fun current(): LiveDatagramSocket {
            socket?.takeIf { openedOn == address }?.let { return it }
            socket?.close()
            openedOn = address
            return opener.open(live).also { socket = it }
        }

        override suspend fun receive(): ByteArray? {
            while (true) {
                if (isClosed) return null
                val datagram = current().receive() ?: return null
                if (!isOnline || blocksUdp || isLost()) continue
                reader.down(datagram)?.let { reportDown(it, tap, UDP_METHOD, UDP_PATH) }
                return datagram
            }
        }

        override suspend fun send(datagram: ByteArray) {
            if (isClosed) throw IOException("Closed")
            val wait = latency
            if (wait.isPositive()) delay(wait)
            val socket = current()
            reader.up(datagram)?.let { tap.sentAt[it.seq] = System.nanoTime() }
            if (!isOnline || blocksUdp || isLost()) return
            socket.send(datagram)
        }

        override fun close() {
            isClosed = true
            socket?.close()
        }

        private fun isLost(): Boolean {
            val loss = datagramLoss ?: return false
            return synchronized(loss) { loss.random.nextDouble() } < loss.rate
        }
    }

    private fun takeLostResponse(path: String): Boolean = synchronized(lostResponses) {
        val rule = lostResponses.firstOrNull { path.endsWith(it.pathSuffix) } ?: return false
        if (--rule.left == 0) lostResponses -= rule
        true
    }

    private fun elapsedMillis(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

    companion object {
        /** The method of an [Exchange] that was a sync over the live channel. */
        const val SOCKET_METHOD = "WS"

        /** The method of an [Exchange] that was an Up of the live channel v2 and its answer. */
        const val LIVE_METHOD = "WS2"

        /** The same over the live channel v2's UDP, with the path [UDP_PATH]. */
        const val UDP_METHOD = "UDP"
        const val UDP_PATH = "udp"

        private const val MAX_BODY_BYTES = 1L shl 20
        private const val SWITCHING_PROTOCOLS = 101
    }
}

/** The foreground service / background mode: only records whether the app asked for it. */
class FakeBackgroundTracker(private val onChange: (Boolean) -> Unit = {}) : BackgroundTracker {
    @Volatile var isRunning: Boolean = false
        private set

    /** Every alert the phone vibrated for, in order (a notification while the app is in the pocket). */
    val alerts = CopyOnWriteArrayList<HiderAlert>()

    override fun alert(alert: HiderAlert) {
        alerts += alert
    }

    override fun start() {
        isRunning = true
        onChange(true)
    }

    override fun stop() {
        isRunning = false
        onChange(false)
    }
}

/**
 * The phone's storage for the app's [SecureStore] (Keystore/Keychain on a real phone): survives [BotPlayer.killApp],
 * so the relaunched app finds its saved session.
 */
class PhoneStorage : SecureStore {
    private val values = ConcurrentHashMap<String, String>()

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}
