package app.hovanki.client.radio

import app.hovanki.shared.protocol.BluetoothState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The phone's Bluetooth LE for the radar (docs/adr/0012-nearby-radar.md, section 2): while [run] is collected, the
 * phone advertises the token in [tokens] (`RadarToken`, which changes every few minutes: the collector keeps it
 * current) and scans for the tokens of the other phones, reporting every one it hears with the signal strength.
 * A hider's phone advertises the game's service with the token as its data; a seeker's ([asSeeker]) advertises an
 * iBeacon frame with the token as major and minor instead, which an iPhone in a pocket hears through CoreLocation
 * («Пульс»). Every phone scans for both. Android: `BluetoothLeAdvertiser` and `BluetoothLeScanner`; iOS: CoreBluetooth
 * and CoreLocation. Nothing here knows whose token is whose: the server does.
 */
interface ProximityRadio {
    /** Whether the phone can take part right now: on, switched off in the system, refused, or no Bluetooth LE. */
    val state: StateFlow<BluetoothState>

    /** Looks at the adapter and the permissions again, after the player answered a permission dialog. */
    fun refresh() = Unit

    fun run(tokens: StateFlow<String?>, asSeeker: Boolean = false): Flow<RadioSighting>
}

/** One phone heard: its [token] at [rssi] dBm, at [atMillis] of the device's clock. */
data class RadioSighting(val token: String, val rssi: Int, val atMillis: Long)

/** A phone without the radar (the JVM bots, a platform without an implementation yet). */
class NoopProximityRadio(state: BluetoothState = BluetoothState.UNSUPPORTED) : ProximityRadio {
    override val state: StateFlow<BluetoothState> = MutableStateFlow(state)

    override fun run(tokens: StateFlow<String?>, asSeeker: Boolean): Flow<RadioSighting> = emptyFlow()
}
