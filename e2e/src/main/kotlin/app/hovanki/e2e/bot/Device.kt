package app.hovanki.e2e.bot

import app.hovanki.client.location.LocationProvider
import app.hovanki.client.storage.SecureStore
import app.hovanki.client.tracking.BackgroundTracker
import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.route.Route
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

/** One HTTP exchange as seen by the phone. [status] is null when the request failed with an I/O error. */
class Exchange(val method: String, val path: String, val status: Int?, val durationMillis: Long, val body: String?)

/**
 * The phone's network, as an OkHttp interceptor under the app's real Ktor/OkHttp client:
 * can be switched off (requests fail with an [IOException] like in a tunnel) and reports every exchange.
 */
class FakeNetwork(private val onExchange: (Exchange) -> Unit) : Interceptor {
    @Volatile var isOnline: Boolean = true

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!isOnline) throw IOException("No network (simulated outage)")
        val request = chain.request()
        val started = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            onExchange(Exchange(request.method, request.url.encodedPath, null, elapsedMillis(started), null))
            throw e
        }
        val body = response.peekBody(MAX_BODY_BYTES).string()
        onExchange(Exchange(request.method, request.url.encodedPath, response.code, elapsedMillis(started), body))
        return response
    }

    private fun elapsedMillis(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

    private companion object {
        const val MAX_BODY_BYTES = 1L shl 20
    }
}

/** The foreground service / background mode: only records whether the app asked for it. */
class FakeBackgroundTracker(private val onChange: (Boolean) -> Unit = {}) : BackgroundTracker {
    @Volatile var isRunning: Boolean = false
        private set

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
