package app.hovanki.e2e.bot

import app.hovanki.device.CarryMonitor
import app.hovanki.device.DeviceInfo
import app.hovanki.device.PocketPulse
import app.hovanki.radar.AdPlan
import app.hovanki.radar.AppState
import app.hovanki.radar.Broadcast
import app.hovanki.radar.HostProximityRadio
import app.hovanki.radar.OsRules
import app.hovanki.radar.ProximityRadio
import app.hovanki.radar.RadarCatalog
import app.hovanki.radar.RadarChannel
import app.hovanki.radar.RadarRole
import app.hovanki.radar.RadarTrace
import app.hovanki.radar.RadioSighting
import app.hovanki.radar.decode
import app.hovanki.radar.host.JvmAirHost
import app.hovanki.radar.host.SimPhone
import app.hovanki.radar.host.SimulatedAir
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Carry
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.rules.RadarToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

// The radar on the simulated phones (docs/adr/0012-nearby-radar.md, section 7): the emulators have no Bluetooth,
// so the scenario's world knows where every bot really is and tells each phone what it would hear.

/**
 * The air between the bots' phones: the radar's simulator ([SimulatedAir], ADR 0017 section 2.2) with the operating
 * systems' rules ([OsRules]). Once a second every listening phone hears every advertising one within range, the
 * signal falling with the distance like outdoors ([rssiAt]) and [POCKET_DAMPING_DB] off for each phone in a pocket.
 * What the platforms hear of each other is the channels' and the OS's business, not a rule of the bots: an iPhone in
 * a pocket (the app in the background) sends neither its name nor the iBeacon frame, so nobody reads its token; an
 * Android hider's token is in its scan response, heard by everybody.
 */
class RadioWorld : AutoCloseable {
    val air = SimulatedAir()

    override fun close() = air.close()

    companion object {
        const val TICK_MILLIS = SimulatedAir.TICK_MILLIS

        /** What a phone in a pocket loses, both ways: the number the server evens out (`ProximityRules`). */
        const val POCKET_DAMPING_DB = SimulatedAir.POCKET_DAMPING_DB

        /** The signal at [meters] in the open: [SimulatedAir.rssiAt]. */
        fun rssiAt(meters: Double): Double = SimulatedAir.rssiAt(meters)

        /**
         * Whether a [listener] phone (its app in [listenerApp]) reads the token of a [sender] phone (in [senderApp])
         * advertising as [role] on [channels] (the game's), the signal aside: what the simulator lets through
         * ([AdPlan], [OsRules]). For a scenario's expectations of who hears whom.
         */
        fun reads(
            sender: Platform,
            senderApp: AppState,
            listener: Platform,
            listenerApp: AppState,
            role: RadarRole = RadarRole.HIDER,
            channels: List<RadarChannel> = RadarCatalog.game,
        ): Boolean {
            val plan = AdPlan.of(channels, PROBE_TOKEN, role, sender)
            if (plan.isEmpty) return false
            val broadcast = OsRules.send(plan.adParts, sender, senderApp).broadcast ?: return false
            return readsToken(broadcast, PROBE_TOKEN, listener, listenerApp, channels)
        }

        internal fun readsToken(
            broadcast: Broadcast,
            token: String,
            listener: Platform,
            listenerApp: AppState,
            channels: List<RadarChannel>,
        ): Boolean {
            val interests = channels.flatMap { it.interests() }.distinct()
            return OsRules.hear(broadcast, listener, listenerApp, interests, rssi = -60, atMillis = 0, peer = null)
                .any { frame -> channels.decode(frame).any { (_, decoded) -> decoded.token == token } }
        }

        private const val PROBE_TOKEN = "0badcafe"
    }
}

/**
 * A bot's Bluetooth LE as the app's [ProximityRadio]: the app's own radio ([HostProximityRadio] on the game's channels)
 * on the simulator's host of this phone ([JvmAirHost], [SimPhone] [id]). While the app collects it, the phone
 * advertises its token in the [RadioWorld] and hears the others'. [state] is the phone's Bluetooth switch, for the
 * scenario to flip. [trace]: the lab's, on a lab phone.
 */
class FakeRadio(
    world: RadioWorld,
    id: String,
    val platform: Platform,
    position: () -> GeoPoint,
    carry: () -> Carry,
    clock: () -> Long,
    trace: RadarTrace = RadarTrace.None,
) : ProximityRadio {
    val phone = SimPhone(id, platform, position, carry = carry, clock = clock)
    val host = JvmAirHost(world.air, phone)
    private val radio = HostProximityRadio(host, trace = trace)
    private val tokensHeard = Collections.synchronizedSet(LinkedHashSet<String>())

    /** The phone's Bluetooth switch. */
    override val state: MutableStateFlow<BluetoothState> get() = phone.bluetooth

    /** The token this phone has on the air right now; null while the app is not running the radar. */
    val token: String? get() = host.advertisedToken

    /** Advertising as a seeker (the iBeacon frame) rather than as a hider (the service). */
    val asSeeker: Boolean get() = host.role == RadarRole.SEEKER

    /** Every token this phone ever heard. */
    val heardTokens: Set<String> get() = synchronized(tokensHeard) { tokensHeard.toSet() }

    /**
     * Whether anybody can read this phone's token right now, by the simulator's rules: not an iPhone in a pocket,
     * whose app in the background sends neither the hider's name nor the seeker's iBeacon frame (the first test on
     * real phones, 2026-09-29; docs/adr/0016-iphone-overflow-radar.md). It still hears the others: a seeker's iBeacon
     * through CoreLocation.
     */
    val isHeard: Boolean
        get() {
            val token = token ?: return false
            val broadcast = host.onAir() ?: return false
            return LISTENERS.any { platform ->
                RadioWorld.readsToken(broadcast, token, platform, AppState.ON_SCREEN, RadarCatalog.game)
            }
        }

    override fun refresh() = radio.refresh()

    override fun run(tokens: StateFlow<String?>, asSeeker: Boolean): Flow<RadioSighting> =
        radio.run(tokens, asSeeker).onEach(::heard)

    /** The lab's bench: [techniques] are the catalog's channels to run (`HostProximityRadio`'s). */
    override fun run(tokens: StateFlow<String?>, asSeeker: Boolean, techniques: Set<String>): Flow<RadioSighting> =
        radio.run(tokens, asSeeker, techniques).onEach(::heard)

    private fun heard(sighting: RadioSighting) {
        if (RadarToken.isWellFormed(sighting.token)) tokensHeard += sighting.token
    }

    private companion object {
        val LISTENERS = listOf(Platform.ANDROID, Platform.IOS, Platform.OTHER)
    }
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
