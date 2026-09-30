package app.hovanki.radar.host

import app.hovanki.radar.AdPart
import app.hovanki.radar.AdPlan
import app.hovanki.radar.AirFrame
import app.hovanki.radar.AirTally
import app.hovanki.radar.AppState
import app.hovanki.radar.Broadcast
import app.hovanki.radar.OsRules
import app.hovanki.radar.RadarCaps
import app.hovanki.radar.RadarChannel
import app.hovanki.radar.RadarRole
import app.hovanki.radar.RadarTrace
import app.hovanki.radar.RadioApi
import app.hovanki.radar.RegionEvent
import app.hovanki.radar.mapState
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Carry
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A phone in the [SimulatedAir]: where it is, what it is, where the scenario keeps it. [app] is the app's state, by
 * default the background while the phone is in a pocket (an iPhone then sends only its overflow mask). [bluetooth] is
 * the phone's switch, for the scenario to flip: a phone with Bluetooth off sends and hears nothing.
 */
class SimPhone(
    val id: String,
    val platform: Platform,
    val position: () -> GeoPoint,
    val carry: () -> Carry = { Carry.IN_HAND },
    val app: () -> AppState = { if (carry() == Carry.IN_POCKET) AppState.BACKGROUND else AppState.ON_SCREEN },
    val clock: () -> Long = System::currentTimeMillis,
    bluetooth: BluetoothState = BluetoothState.ON,
) {
    val bluetooth = MutableStateFlow(bluetooth)

    val caps: StateFlow<RadarCaps> = this.bluetooth.mapState { capsOf(platform, it) }

    override fun toString(): String = "SimPhone($id, $platform)"

    companion object {
        /** What the simulator's phones can do: the platforms' as far as the radar knows them. */
        fun capsOf(platform: Platform, bluetooth: BluetoothState): RadarCaps = when (platform) {
            Platform.ANDROID -> RadarCaps(platform, bluetooth, canScanResponse = true, canReadOverflow = true)

            Platform.IOS -> RadarCaps(
                platform,
                bluetooth,
                canScanResponse = false,
                canRangeBeacons = true,
                canReadOverflow = true,
                canAdvertiseOverflow = true,
            )

            Platform.OTHER -> RadarCaps(platform, bluetooth, canScanResponse = false, canReadOverflow = true)
        }
    }
}

/**
 * The air between simulated phones (docs/adr/0017-radar-techniques-and-big-run.md, section 2.2): the bots' radar in
 * e2e, with the operating systems' rules ([OsRules]), so a channel behaves here as on the phones without a rule of
 * its own. Every [TICK_MILLIS] each listening phone hears each sending one: the signal falls with the distance like
 * outdoors ([rssiAt]: about -58 dBm a metre away, -73 at five, -85 at twenty), [noiseDb] of Gaussian noise on top,
 * [POCKET_DAMPING_DB] off for each phone in a pocket, nothing below [FLOOR_DBM]. What goes out and what is heard is
 * [OsRules]'; iOS region monitoring enters when a beacon of the region is heard and exits [REGION_EXIT_MILLIS] after
 * the last one. A phone runs radios through [JvmAirHost].
 *
 * [autoTick]: ticks once a second of real time on its own (the bots); off, the test calls [tick].
 */
class SimulatedAir(private val noiseDb: Double = NOISE_DB, seed: Long = SEED, autoTick: Boolean = true) :
    AutoCloseable {
    private val radios = CopyOnWriteArrayList<SimRadio>()
    private val random = Random(seed)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        if (autoTick) {
            scope.launch {
                while (isActive) {
                    tick()
                    delay(TICK_MILLIS)
                }
            }
        }
    }

    /** One second of the air: every pending advertisement applied, every phone hears the others. */
    fun tick() {
        val live = radios.filter { it.phone.bluetooth.value == BluetoothState.ON }
        live.forEach { it.apply() }
        val sending = live.mapNotNull { radio -> radio.broadcast()?.let { radio to it } }
        for (listener in live) {
            val phone = listener.phone
            val here = phone.position()
            val app = phone.app()
            val now = phone.clock()
            val regions = mutableSetOf<String>()
            for ((sender, broadcast) in sending) {
                if (sender.phone === phone) continue
                var level = rssiAt(here.distanceTo(sender.phone.position())) + gaussian() * noiseDb
                if (phone.carry() == Carry.IN_POCKET) level -= POCKET_DAMPING_DB
                if (sender.phone.carry() == Carry.IN_POCKET) level -= POCKET_DAMPING_DB
                if (level < FLOOR_DBM) continue
                val rssi = level.roundToInt()
                OsRules.hear(broadcast, phone.platform, app, listener.interests, rssi, now, sender.phone.id)
                    .forEach(listener::deliver)
                regions += OsRules.regions(broadcast, phone.platform, listener.interests)
            }
            listener.regions(regions, now)
        }
    }

    internal fun open(radio: SimRadio) {
        radios += radio
    }

    internal fun close(radio: SimRadio) {
        radios -= radio
    }

    /** The radios running on [phone] now. */
    internal fun radiosOf(phone: SimPhone): List<SimRadio> = radios.filter { it.phone === phone }

    private fun gaussian(): Double = synchronized(random) {
        val u1 = 1.0 - random.nextDouble()
        val u2 = random.nextDouble()
        sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
    }

    override fun close() = scope.cancel()

    companion object {
        const val TICK_MILLIS = 1_000L

        /** What a phone in a pocket loses, both ways: the number the server evens out (`ProximityRules`). */
        const val POCKET_DAMPING_DB = 12.0
        const val NOISE_DB = 2.0
        const val FLOOR_DBM = -95.0

        /** iOS says a region is left about half a minute after its last beacon. */
        const val REGION_EXIT_MILLIS = 30_000L
        private const val SEED = 7L

        /** The signal at [meters] in the open: a log-distance model fitted to ADR 0012's numbers. */
        fun rssiAt(meters: Double): Double = -58.0 - 21.0 * log10(max(meters, 0.5))
    }
}

/**
 * One radio a [JvmAirHost] runs on a [SimPhone]: its channels, its role, the token it wants on the air and the one it
 * has there, and where its frames go. Does what the phone's host would ([AdPlan]): assembles the advertisement, drops
 * what the platform can't send, and on an iPhone in the background keeps the old advertisement until the app is on
 * the screen again. Its state is guarded by itself: the token's collector and the air's tick both call in.
 */
internal class SimRadio(
    val phone: SimPhone,
    private val channels: List<RadarChannel>,
    val role: RadarRole,
    private val trace: RadarTrace,
    private val sink: (AirFrame) -> Unit,
) {
    val interests = channels.flatMap { it.interests() }.distinct()
    private val tally = AirTally(trace, channels)
    private var wanted: String? = null
    private var skipped: String? = null
    private var plan: AdPlan? = null
    private var parts: List<AdPart> = emptyList()
    private val inside = mutableMapOf<String, Long>()

    /** The token on the air (or failed to go there); null: none. */
    @Volatile var advertised: String? = null
        private set

    @Synchronized
    fun want(token: String?) {
        wanted = token
        apply()
    }

    /** Puts the wanted token on the air, as far as the platform lets it now. */
    @Synchronized
    fun apply() {
        val token = wanted
        if (token == advertised) return
        if (phone.platform == Platform.IOS && phone.app() == AppState.BACKGROUND && token != null &&
            advertised != null
        ) {
            if (skipped != token) plan?.trace(trace, "skipped_background", token)
            skipped = token
            return
        }
        skipped = null
        if (parts.isNotEmpty()) plan?.trace(trace, "stop", advertised)
        plan = null
        parts = emptyList()
        advertised = token
        if (token == null) return
        val next = AdPlan.of(channels, token, role, phone.platform)
        plan = next
        if (next.isEmpty) {
            next.traceDropped(trace, token)
            return
        }
        val error = OsRules.send(next.adParts, phone.platform, phone.app()).error
        if (error != null) {
            next.trace(trace, "failed", token, error)
        } else {
            next.trace(trace, "start", token)
            parts = next.adParts
        }
    }

    /** What this radio has on the air right now: its advertisement as the OS sends it in the app's state. */
    @Synchronized
    fun broadcast(): Broadcast? {
        if (parts.isEmpty() || phone.bluetooth.value != BluetoothState.ON) return null
        return OsRules.send(parts, phone.platform, phone.app()).broadcast
    }

    @Synchronized
    fun deliver(frame: AirFrame) {
        tally.heard(frame)
        sink(frame)
    }

    /** Region monitoring: [heard] the regions with a beacon heard this tick, at [now] of the phone's clock. */
    @Synchronized
    fun regions(heard: Set<String>, now: Long) {
        for (uuid in heard) {
            if (uuid !in inside) deliver(regionFrame(RegionEvent.ENTER, now))
            inside[uuid] = now
        }
        val left = inside.filter { (uuid, last) -> uuid !in heard && now - last >= SimulatedAir.REGION_EXIT_MILLIS }
        for (uuid in left.keys) {
            inside -= uuid
            deliver(regionFrame(RegionEvent.EXIT, now))
        }
    }

    @Synchronized
    fun stop() {
        if (parts.isNotEmpty()) plan?.trace(trace, "stop", advertised)
        plan = null
        parts = emptyList()
        advertised = null
        wanted = null
        tally.flush()
    }

    private fun regionFrame(event: RegionEvent, now: Long) =
        AirFrame(now, 0, RadioApi.CORELOCATION_REGION, peer = null, regionEvent = event)
}
