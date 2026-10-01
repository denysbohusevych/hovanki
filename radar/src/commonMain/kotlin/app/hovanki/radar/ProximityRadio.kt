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
 * A hider's phone advertises the game's service with the token as its data; a seeker's ([asSeeker]) advertises an
 * iBeacon frame with the token as major and minor instead, which an iPhone in a pocket hears through CoreLocation
 * («Пульс»). Every phone scans for both. Android: `BluetoothLeAdvertiser` and `BluetoothLeScanner`; iOS: CoreBluetooth
 * and CoreLocation. Nothing here knows whose token is whose: the server does.
 *
 * Underneath, a host of channels (docs/adr/0017-radar-techniques-and-big-run.md, section 2.2): every way of carrying a
 * token is a [RadarChannel] of the [RadarCatalog], and the platform's radio joins their parts into one advertisement
 * and reads every frame with all of them. The game still sees only [run].
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

    /** [options]: what else the game knows that the radio uses (the player's number for the Android hider's layout). */
    fun run(
        tokens: StateFlow<String?>,
        asSeeker: Boolean = false,
        options: RadioOptions = RadioOptions(),
    ): Flow<RadioSighting>
}

/**
 * One phone heard: its [token] at [rssi] dBm, at [atMillis] of the device's clock. [api], [via], [peer] and [tech] say
 * how, for the debug build's diagnostics and the journal (docs/radio-lab.md §4.1) only: [peer] is the OS's id of the
 * sender (a CoreBluetooth identifier, an address), never sent anywhere; the journal hashes it. [tech]: the channel
 * that read it (`ble.name`, `ble.service_data.bare`…).
 */
data class RadioSighting(
    val token: String,
    val rssi: Int,
    val atMillis: Long,
    val api: RadioApi = RadioApi.UNKNOWN,
    val via: SightingVia = SightingVia.UNKNOWN,
    val peer: String? = null,
    val tech: String? = null,
)

/** Which of the platform's APIs heard a reading. */
enum class RadioApi {
    UNKNOWN,
    COREBLUETOOTH,
    CORELOCATION_RANGING,
    ANDROID_LE,
    MAC_COREBLUETOOTH,
    ;

    /** The name in the lab's log. */
    val key: String get() = name.lowercase()
}

/** What in the other phone's advertisement carried its token. */
enum class SightingVia {
    UNKNOWN,

    /** The local name: an iPhone hider on screen. */
    NAME,

    /** The game service's data: an Android hider (`.scan_response`, `.bare`). */
    SERVICE_DATA,

    /** The game's UUID and the token in manufacturer data: an Android hider (`.mfr`). */
    MANUFACTURER_DATA,

    /** The iBeacon frame's major and minor: a seeker. */
    IBEACON,

    /** The overflow area's bits as CoreBluetooth lists them: a locked iPhone, heard by an iPhone (ADR 0016). */
    OVERFLOW_UUIDS,

    /** The overflow area's raw mask in Apple's manufacturer data: a locked iPhone, heard by Android or a Mac. */
    OVERFLOW_RAW,
    ;

    val key: String get() = name.lowercase()
}

/**
 * What a radio does with its advertisement and its scan, for the journal (docs/radio-lab.md §4.1, ADR 0017 §4): the
 * debug build's radio lab, the field build's journal (ADR 0018); [None] otherwise. Called on the main thread.
 *
 * While [isListening], the radio also runs the shadow's channels (`ble.overflow`, `ble.ibeacon.region`), puts a
 * locked iPhone's mask on the air and gives an Android hider its layout by the player's number
 * ([RadarCatalog.hiderLayout]); what the shadow reads comes here ([shadow], [region]), never to the game. Without a
 * journal the radio does only what the game needs.
 */
interface RadioTrace {
    /** Whether a journal is written now: the shadow works only then. */
    val isListening: Boolean get() = false

    /**
     * [action]: `start`, `stop`, `failed` ([error]) or `skipped_background` (iOS keeps the old advertisement: it can't
     * start another one in the background); [mode]: `hider_name`, `hider_service_data`, `ibeacon` or
     * `background_overflow` (a locked iPhone's mask). [report]: the advertisement's channels, layout and bytes.
     */
    fun advertise(action: String, mode: String, token: String?, error: String? = null, report: AdvertReport? = null) =
        Unit

    /** [action]: `start`, `stop` or `failed` ([error]) of a scan by [api], [filters] in words. */
    fun scan(action: String, api: RadioApi, filters: String? = null, error: String? = null) = Unit

    /** A frame of ours, whole (`frame`): [tech] read it. Only while [isListening]. */
    fun frame(frame: HeardFrame, tech: String) = Unit

    /** Everybody else's frames the scan let through, once a second (`air`). Only while [isListening]. */
    fun air(summary: AirSummary) = Unit

    /** What a shadow channel ([tech]) read: [tokens] (an overflow mask may give 2 or 4), never the game's. */
    fun shadow(tech: String, tokens: List<String>, frame: HeardFrame, via: SightingVia) = Unit

    /**
     * The seekers' iBeacon region (`ble.ibeacon.region`): [event] `enter`, `exit` or `state` ([state]: `inside`,
     * `outside`, `unknown`), or `failed` ([error]).
     */
    fun region(event: String, state: String? = null, error: String? = null) = Unit

    companion object {
        val None: RadioTrace = object : RadioTrace {}
    }
}

/**
 * Everybody else's frames a scan let through in [millis] from [fromMillis] (device clock): iBeacons that are not the
 * game's, overflow masks that read as no token ([masks], their bits in [maskBits]: bit → frames), Apple's other
 * frames, anything else; and how many frames of ours came meanwhile ([ours]).
 */
data class AirSummary(
    val fromMillis: Long,
    val millis: Long,
    val ours: Int,
    val ibeacons: Int,
    val masks: Int,
    val apple: Int,
    val other: Int,
    val maskBits: Map<Int, Int>,
)

/** A phone without the radar (the JVM bots, a platform without an implementation yet). */
class NoopProximityRadio(state: BluetoothState = BluetoothState.UNSUPPORTED) : ProximityRadio {
    override val state: StateFlow<BluetoothState> = MutableStateFlow(state)

    override fun run(tokens: StateFlow<String?>, asSeeker: Boolean, options: RadioOptions): Flow<RadioSighting> =
        emptyFlow()
}
