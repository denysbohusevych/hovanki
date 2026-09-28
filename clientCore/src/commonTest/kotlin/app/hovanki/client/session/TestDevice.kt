package app.hovanki.client.session

import app.hovanki.client.device.DeviceInfo
import app.hovanki.client.location.LocationProvider
import app.hovanki.client.radio.ProximityRadio
import app.hovanki.client.radio.RadioSighting
import app.hovanki.client.tracking.AlertKind
import app.hovanki.client.tracking.BackgroundTracker
import app.hovanki.client.tracking.HiderAlert
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.Platform
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
class FakeRadio(state: BluetoothState = BluetoothState.ON) : ProximityRadio {
    override val state = MutableStateFlow(state)

    /** The token flow of the running collection; null while nothing collects. */
    var tokens: StateFlow<String?>? = null
        private set
    var collectors = 0
        private set
    private val sightings = MutableSharedFlow<RadioSighting>(extraBufferCapacity = 16)

    override fun run(tokens: StateFlow<String?>): Flow<RadioSighting> = sightings
        .onStart {
            this@FakeRadio.tokens = tokens
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
) : DeviceInfo

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
