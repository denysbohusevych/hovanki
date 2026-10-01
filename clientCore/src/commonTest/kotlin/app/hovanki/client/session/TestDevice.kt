package app.hovanki.client.session

import app.hovanki.client.location.LocationProvider
import app.hovanki.client.tracking.AlertKind
import app.hovanki.client.tracking.BackgroundTracker
import app.hovanki.client.tracking.HiderAlert
import app.hovanki.device.CarryMonitor
import app.hovanki.device.DeviceInfo
import app.hovanki.device.PocketPulse
import app.hovanki.radar.ChannelMix
import app.hovanki.radar.ProximityRadio
import app.hovanki.radar.RadioSighting
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Carry
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.RadarBand
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

// The phone around GameSessionManager in tests: GPS that never reports and a background tracker that remembers.

class FakeLocationProvider : LocationProvider {
    var collectors = 0

    override fun hasPermission() = true

    override fun locationUpdates(intervalMillis: Long): Flow<LocationSample> = flow {
        collectors++
        try {
            awaitCancellation()
        } finally {
            collectors--
        }
    }
}

/** Bluetooth LE that hears what the test says ([hears]); remembers the tokens it was told to advertise. */
class FakeRadio(
    state: BluetoothState = BluetoothState.ON,
    /** What the phone finds when it looks ([refresh]); null: the state stays. An iPhone knows nothing before. */
    private val onRefresh: BluetoothState? = null,
) : ProximityRadio {
    override val state = MutableStateFlow(state)

    var refreshes = 0
        private set

    override fun refresh() {
        refreshes++
        onRefresh?.let { state.value = it }
    }

    /** The token flow of the running collection; null while nothing collects. */
    var tokens: StateFlow<String?>? = null
        private set

    /** Whether the running collection advertises as a seeker (the iBeacon frame). */
    var asSeeker: Boolean? = null
        private set
    var collectors = 0
        private set
    private val sightings = MutableSharedFlow<RadioSighting>(extraBufferCapacity = 64)

    /** The channels the running collection was told to run instead of the game's; null: the game's. */
    var mix: ChannelMix? = null
        private set

    override fun run(tokens: StateFlow<String?>, asSeeker: Boolean): Flow<RadioSighting> =
        collect(tokens, asSeeker, null)

    override fun run(tokens: StateFlow<String?>, asSeeker: Boolean, mix: ChannelMix): Flow<RadioSighting> =
        collect(tokens, asSeeker, mix)

    private fun collect(tokens: StateFlow<String?>, asSeeker: Boolean, mix: ChannelMix?): Flow<RadioSighting> =
        sightings
            .onStart {
                this@FakeRadio.tokens = tokens
                this@FakeRadio.asSeeker = asSeeker
                this@FakeRadio.mix = mix
                collectors++
            }
            .onCompletion {
                collectors--
                if (collectors == 0) this@FakeRadio.tokens = null
            }

    /** Another phone's [token] heard at [rssi] dBm, at [atMillis] of the device's clock. */
    fun hears(token: String, rssi: Int, atMillis: Long) {
        check(sightings.tryEmit(RadioSighting(token, rssi, atMillis)))
    }
}

class FakeDeviceInfo(
    override val platform: Platform = Platform.ANDROID,
    override val hasUwb: Boolean = false,
    override val hasActivitySensor: Boolean = true,
    override val model: String? = "Fake 1",
) : DeviceInfo

/** Remembers every band the pulse was set to, in order. */
class FakePocketPulse : PocketPulse {
    val bands = mutableListOf<RadarBand>()

    override fun set(band: RadarBand) {
        bands += band
    }
}

/** Says where the phone is whenever the test sets [state]. */
class FakeCarryMonitor(initial: Carry = Carry.UNKNOWN) : CarryMonitor {
    val state = MutableStateFlow(initial)

    override fun carry(): Flow<Carry> = state
}

class FakeBackgroundTracker : BackgroundTracker {
    var running = false

    /** Every alert vibrated for, in order, and the kinds that ended. */
    val alerts = mutableListOf<HiderAlert>()
    val endedAlerts = mutableListOf<AlertKind>()

    override fun start() {
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun alert(alert: HiderAlert) {
        alerts += alert
    }

    override fun endAlert(kind: AlertKind) {
        endedAlerts += kind
    }
}
