package app.hovanki.e2e.bot

import app.hovanki.client.device.DeviceInfo
import app.hovanki.client.radio.ProximityRadio
import app.hovanki.client.radio.RadioSighting
import app.hovanki.client.tracking.CarryMonitor
import app.hovanki.client.tracking.PocketPulse
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
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

// The radar on the simulated phones (docs/adr/0012-nearby-radar.md, section 7): the emulators have no Bluetooth,
// so the scenario's world knows where every bot really is and tells each phone what it would hear.

/**
 * The air between the bots' phones. Once a second every listening phone hears every advertising one within range:
 * the signal falls with the distance like it does outdoors (about -58 dBm a metre away, -73 at five, -85 at
 * twenty), a little noise on top, and the body takes [POCKET_DAMPING_DB] off for each phone in a pocket. What the
 * platforms can hear of each other follows the phones: an iPhone in a pocket (the app in the background) sends
 * neither its name nor the iBeacon frame, so nobody reads its token (docs/adr/0016-iphone-overflow-radar.md); an
 * Android in a pocket and any phone on the screen are heard by everybody.
 */
class RadioWorld : AutoCloseable {
    private val phones = CopyOnWriteArrayList<FakeRadio>()
    private val random = Random(SEED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MILLIS)
            }
        }
    }

    internal fun register(radio: FakeRadio) {
        phones += radio
    }

    internal fun unregister(radio: FakeRadio) {
        phones -= radio
    }

    private fun tick() {
        val on = phones.filter { it.state.value == BluetoothState.ON }
        for (listener in on) {
            val here = listener.position()
            for (other in on) {
                val token = other.token ?: continue
                if (other === listener || !other.isHeard) continue
                val meters = here.distanceTo(other.position())
                var level = rssiAt(meters) + gaussian() * NOISE_DB
                if (listener.carry() == Carry.IN_POCKET) level -= POCKET_DAMPING_DB
                if (other.carry() == Carry.IN_POCKET) level -= POCKET_DAMPING_DB
                if (level < FLOOR_DBM) continue
                listener.hears(token, level.roundToInt())
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
 * A bot's Bluetooth LE as the app's [ProximityRadio]: while the app collects it, the phone advertises its token in
 * the [RadioWorld] and hears the others'. [state] is the phone's Bluetooth switch, for the scenario to flip.
 */
class FakeRadio(
    private val world: RadioWorld,
    val platform: Platform,
    internal val position: () -> GeoPoint,
    internal val carry: () -> Carry,
    private val clock: () -> Long,
) : ProximityRadio {
    override val state = MutableStateFlow(BluetoothState.ON)

    /** The token this phone advertises right now; null while the app is not running the radar. */
    @Volatile var token: String? = null
        private set

    /** Advertising as a seeker (the iBeacon frame) rather than as a hider (the service). */
    @Volatile var asSeeker: Boolean = false
        private set

    @Volatile private var sink: SendChannel<RadioSighting>? = null
    private val tokensHeard = Collections.synchronizedSet(LinkedHashSet<String>())

    /** Every token this phone ever heard. */
    val heardTokens: Set<String> get() = synchronized(tokensHeard) { tokensHeard.toSet() }

    override fun run(tokens: kotlinx.coroutines.flow.StateFlow<String?>, asSeeker: Boolean): Flow<RadioSighting> =
        callbackFlow {
            this@FakeRadio.asSeeker = asSeeker
            sink = channel
            val tokenJob = tokens.onEach { token = it }.launchIn(this)
            world.register(this@FakeRadio)
            awaitClose {
                tokenJob.cancel()
                world.unregister(this@FakeRadio)
                sink = null
                token = null
            }
        }

    internal fun hears(token: String, rssi: Int) {
        tokensHeard += token
        sink?.trySend(RadioSighting(token, rssi, clock()))
    }

    /**
     * Whether anybody can read this phone's token at all: not an iPhone in a pocket, whose app in the background sends
     * neither the hider's name nor the seeker's iBeacon frame (the first test on real phones, 2026-09-29;
     * docs/adr/0016-iphone-overflow-radar.md). It still hears the others: a seeker's iBeacon through CoreLocation.
     */
    internal val isHeard: Boolean get() = platform != Platform.IOS || carry() != Carry.IN_POCKET
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
