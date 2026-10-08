package app.hovanki.e2e.bot

import app.hovanki.client.location.LocationProvider
import app.hovanki.client.network.GameSocket
import app.hovanki.client.network.GameSocketOpener
import app.hovanki.client.storage.SecureStore
import app.hovanki.client.tracking.BackgroundTracker
import app.hovanki.client.tracking.HiderAlert
import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.route.Route
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.ClientFrame
import app.hovanki.shared.protocol.GeoPoint
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
    /** The chat messages a socket pushed ([ServerFrame.Chat]), as they reach the phone. */
    private val onPushedChat: (List<ChatMessage>) -> Unit = {},
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

    private fun takeLostResponse(path: String): Boolean = synchronized(lostResponses) {
        val rule = lostResponses.firstOrNull { path.endsWith(it.pathSuffix) } ?: return false
        if (--rule.left == 0) lostResponses -= rule
        true
    }

    private fun elapsedMillis(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

    companion object {
        /** The method of an [Exchange] that was a sync over the live channel. */
        const val SOCKET_METHOD = "WS"

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
