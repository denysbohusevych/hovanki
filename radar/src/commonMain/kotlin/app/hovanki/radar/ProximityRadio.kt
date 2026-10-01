package app.hovanki.radar

import app.hovanki.shared.protocol.BluetoothState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The phone's Bluetooth LE for the radar (docs/adr/0012-nearby-radar.md, section 2): while [run] is collected, the
 * phone advertises the token in [tokens] (`RadarToken`, which changes every few minutes: the collector keeps it
 * current) and scans for the tokens of the other phones, reporting every one it hears with the signal strength.
 * A hider's phone advertises the game's service with the token as its data (Android, in the scan response) or as its
 * name (an iPhone); a seeker's ([asSeeker]) advertises an iBeacon frame with the token as major and minor instead,
 * which an iPhone in a pocket hears through CoreLocation («Пульс»). Every phone scans for all of them. Implemented
 * on a platform's [AirHost] with the game's channels ([HostProximityRadio], [RadarCatalog.game]). Nothing here knows
 * whose token is whose: the server does.
 */
interface ProximityRadio {
    /** Whether the phone can take part right now: on, switched off in the system, refused, or no Bluetooth LE. */
    val state: StateFlow<BluetoothState>

    /**
     * Looks at the adapter and the permissions again: with every snapshot of a game with the radar, and after the
     * player answered a permission dialog. On iOS the first call starts watching the adapter (and asks the player):
     * until then [state] knows nothing.
     */
    fun refresh() = Unit

    fun run(tokens: StateFlow<String?>, asSeeker: Boolean = false): Flow<RadioSighting>

    /**
     * [run] with the channels of [techniques] (their [RadarChannel.id]s) instead of the game's: the radio lab's
     * choice for a step of its run (docs/radio-lab.md §5). Unknown ids are left out; none left: the game's. The game
     * never passes it; a radio without channels runs as [run].
     */
    fun run(tokens: StateFlow<String?>, asSeeker: Boolean, techniques: Set<String>): Flow<RadioSighting> =
        run(tokens, asSeeker)

    /**
     * [run] with [mix] instead of the game's channels: the field build's journal (docs/adr/0018-field-test-build.md
     * §4 B, [RadarCatalog.field]). Sightings come only from [ChannelMix.game]; what [ChannelMix.shadow] reads goes to
     * the radio's trace alone. A radio without channels runs as [run].
     */
    fun run(tokens: StateFlow<String?>, asSeeker: Boolean, mix: ChannelMix): Flow<RadioSighting> = run(tokens, asSeeker)
}

/**
 * One phone heard: its [token] at [rssi] dBm, at [atMillis] of the device's clock. [api], [via], [peer] and [tech] say
 * how, for the debug build's diagnostics and radio lab (docs/radio-lab.md §4.1) only: [peer] is the OS's id of the
 * sender (a CoreBluetooth identifier, an address), never sent anywhere; the lab hashes it. [tech] is the channel that
 * decoded it ([RadarChannel.id]); empty from a radio without channels.
 */
data class RadioSighting(
    val token: String,
    val rssi: Int,
    val atMillis: Long,
    val api: RadioApi = RadioApi.UNKNOWN,
    val via: SightingVia = SightingVia.UNKNOWN,
    val peer: String? = null,
    val tech: String = "",
)

/** Which of the platform's APIs heard a reading. */
enum class RadioApi {
    UNKNOWN,
    COREBLUETOOTH,
    CORELOCATION_RANGING,
    ANDROID_LE,
    MAC_COREBLUETOOTH,

    /** iOS region monitoring: the enter and exit of the game's beacon region. */
    CORELOCATION_REGION,
    ;

    /** The name in the lab's log. */
    val key: String get() = name.lowercase()
}

/** What in the other phone's advertisement carried its token. */
enum class SightingVia {
    UNKNOWN,

    /** The local name: an iPhone hider on screen. */
    NAME,

    /** The game service's data: an Android hider. */
    SERVICE_DATA,

    /** The iBeacon frame's major and minor: a seeker. */
    IBEACON,

    /** The overflow area's bits as CoreBluetooth lists them: a locked iPhone, heard by an iPhone (ADR 0016). */
    OVERFLOW_UUIDS,

    /** The overflow area's raw mask in Apple's manufacturer data: a locked iPhone, heard by Android or a Mac. */
    OVERFLOW_RAW,
    ;

    val key: String get() = name.lowercase()
}

/** A phone without the radar (the JVM bots, a platform without an implementation yet). */
class NoopProximityRadio(state: BluetoothState = BluetoothState.UNSUPPORTED) : ProximityRadio {
    override val state: StateFlow<BluetoothState> = MutableStateFlow(state)

    override fun run(tokens: StateFlow<String?>, asSeeker: Boolean): Flow<RadioSighting> = emptyFlow()
}
