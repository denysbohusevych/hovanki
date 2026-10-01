package app.hovanki.e2e.bot

import app.hovanki.device.CarryMonitor
import app.hovanki.device.DeviceInfo
import app.hovanki.device.PocketPulse
import app.hovanki.radar.JvmAir
import app.hovanki.radar.JvmAirHost
import app.hovanki.radar.ProximityRadio
import app.hovanki.radar.RadioOptions
import app.hovanki.radar.RadioSighting
import app.hovanki.radar.RadioTrace
import app.hovanki.radar.airPlatformOf
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Carry
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.RadarBand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

// The radar on the simulated phones (docs/adr/0012-nearby-radar.md, section 7): the emulators have no Bluetooth,
// so the scenario's world knows where every bot really is and tells each phone what it would hear. What a phone puts
// on the air and what its APIs make of what comes in is `:radar`'s simulated air (JvmAirHost, AirRules): the same
// channels and decoding as on the phones, with the OS's rules (docs/adr/0017-radar-techniques-and-big-run.md §2.2).

/**
 * The air between the bots' phones. Once a second every listening phone hears every advertising one within range:
 * the signal falls with the distance like it does outdoors (about -58 dBm a metre away, -73 at five, -85 at
 * twenty), a little noise on top, and the body takes [POCKET_DAMPING_DB] off for each phone in a pocket. What the
 * platforms can hear of each other follows the OS's rules ([app.hovanki.radar.AirRules]): an iPhone in a pocket (the
 * app in the background) sends neither its name nor the iBeacon frame, so nobody reads its token for the game
 * (docs/adr/0016-iphone-overflow-radar.md); an Android in a pocket and any phone on the screen are heard by
 * everybody (an Android hider by its layout: without a journal `.scan_response`, which iPhones hear too).
 */
class RadioWorld :
    JvmAir,
    AutoCloseable {
    private val attached = ConcurrentHashMap<JvmAirHost, FakeRadio>()
    private val phones = CopyOnWriteArrayList<FakeRadio>()
    private val random = Random(SEED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val peers = AtomicInteger()

    init {
        scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MILLIS)
            }
        }
    }

    /** A new phone's id on the air, as its OS shows it to the others (an address). */
    internal fun nextPeer(): String = "sim:%02x".format(peers.incrementAndGet())

    internal fun attach(radio: FakeRadio) {
        attached[radio.host] = radio
    }

    override fun register(host: JvmAirHost) {
        attached[host]?.let { phones += it }
    }

    override fun unregister(host: JvmAirHost) {
        attached[host]?.let { phones -= it }
    }

    private fun tick() {
        val on = phones.filter { it.state.value == BluetoothState.ON }
        val sent = on.mapNotNull { phone -> phone.host.broadcast()?.let { phone to it } }
        for (listener in on) {
            val here = listener.position()
            for ((other, broadcast) in sent) {
                if (other === listener) continue
                val meters = here.distanceTo(other.position())
                var level = rssiAt(meters) + gaussian() * NOISE_DB
                if (listener.carry() == Carry.IN_POCKET) level -= POCKET_DAMPING_DB
                if (other.carry() == Carry.IN_POCKET) level -= POCKET_DAMPING_DB
                if (level < FLOOR_DBM) continue
                listener.host.receive(broadcast, level.roundToInt(), other.host.peer)
            }
        }
    }

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
        private const val NOISE_DB = 2.0
        private const val FLOOR_DBM = -95.0
        private const val SEED = 7L

        /** The signal at [meters] in the open: a log-distance model fitted to the ADR's numbers. */
        fun rssiAt(meters: Double): Double = -58.0 - 21.0 * log10(max(meters, 0.5))
    }
}

/**
 * A bot's Bluetooth LE as the app's [ProximityRadio]: a [JvmAirHost] in the [RadioWorld], so while the app collects
 * it the phone advertises its token with its platform's channels and hears the others' through its OS's rules.
 * [state] is the phone's Bluetooth switch, for the scenario to flip. With a [trace] that listens (a journal), the
 * shadow works as on a phone ([RadioTrace.isListening]).
 */
class FakeRadio(
    world: RadioWorld,
    val platform: Platform,
    internal val position: () -> GeoPoint,
    internal val carry: () -> Carry,
    clock: () -> Long,
    trace: RadioTrace = RadioTrace.None,
) : ProximityRadio {
    override val state = MutableStateFlow(BluetoothState.ON)

    /** The phone's Bluetooth in the simulated air; the app on the screen unless the phone is in a pocket. */
    val host = JvmAirHost(
        platform = airPlatformOf(platform),
        air = world,
        onScreen = { carry() != Carry.IN_POCKET },
        clock = clock,
        trace = trace,
        peer = world.nextPeer(),
        state = state,
    )

    init {
        world.attach(this)
    }

    /** The token this phone advertises right now; null while the app is not running the radar. */
    val token: String? get() = host.token

    /** Advertising as a seeker (the iBeacon frame) rather than as a hider (the service). */
    val asSeeker: Boolean get() = host.asSeeker

    /** Every token this phone ever heard (for the game: never the shadow's). */
    val heardTokens: Set<String> get() = host.heardTokens

    override fun run(tokens: StateFlow<String?>, asSeeker: Boolean, options: RadioOptions): Flow<RadioSighting> =
        host.run(tokens, asSeeker, options)
}

/** The bot's phone as the app sees it: its kind, no UWB, no motion sensors, a model for the calibration. */
class BotDeviceInfo(override val platform: Platform) : DeviceInfo {
    override val hasUwb: Boolean = false
    override val hasActivitySensor: Boolean = false
    override val model: String = "Bot ${platform.name.lowercase()}"
}

/** Where the bot's phone is, as the scenario puts it. */
class FakeCarryMonitor(val state: MutableStateFlow<Carry>) : CarryMonitor {
    override fun carry(): Flow<Carry> = state
}

/** The pulse as the bot feels it: the band right now and every band it was ever set to. */
class FakePocketPulse : PocketPulse {
    @Volatile var band: RadarBand = RadarBand.NONE
        private set
    val bands = CopyOnWriteArrayList<RadarBand>()

    override fun set(band: RadarBand) {
        this.band = band
        bands += band
    }
}
