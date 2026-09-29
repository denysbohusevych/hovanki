package app.hovanki.client.lab

import app.hovanki.client.radio.RadioApi
import app.hovanki.client.radio.RadioTrace
import app.hovanki.client.radio.SightingVia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/*
 * What the radio lab needs from the platform (docs/radio-lab.md §5), besides the game's own services. Implemented in
 * `composeApp`'s androidMain and iosMain, the no-op ones below everywhere else. Debug builds only use them: the lab's
 * screen exists only there. Flows are collected on the main thread and may emit from it only.
 */

/** The app's life, the sensors and the battery. */
interface LabProbes {
    /** The app's state now: `active` / `inactive` / `background` (iOS), `screen_on` / `screen_off` (Android). */
    fun appState(): String

    /**
     * Life and power events while collected: `will_resign`, `did_enter_background`, `will_enter_foreground`,
     * `did_become_active`, `protected_data_off` / `_on`, `screen_on` / `_off`, `doze_on` / `_off`, `low_power_on` /
     * `_off`, `memory_warning`, `terminate`.
     */
    fun lifecycle(): Flow<String>

    /** Motion about ten times a second, the proximity sensor and the light as the platform gives them. */
    fun sensors(): Flow<LabSensorReading>

    /** The battery now, then on changes and at least once a minute. */
    fun battery(): Flow<LabBattery>
}

sealed interface LabSensorReading {
    /** The acceleration's magnitude, gravity included, in g; [gravity] in the lab's frame ([Gravity]). */
    data class Motion(val atMillis: Long, val magnitudeG: Double, val gravity: Gravity?) : LabSensorReading

    /** [rawCm] and [maxCm]: Android's distance; [monitoring]: iOS, whether the sensor is on at all. */
    data class Proximity(
        val near: Boolean?,
        val rawCm: Double? = null,
        val maxCm: Double? = null,
        val monitoring: Boolean? = null,
    ) : LabSensorReading

    /** Android only: iOS has no public light sensor. */
    data class Light(val lux: Double) : LabSensorReading
}

data class LabBattery(val level: Double?, val state: String?, val lowPower: Boolean?)

/**
 * The lab's own radio beside the game's (docs/radio-lab.md §5): «listen to everything» and the overflow probe. The
 * game's [app.hovanki.client.radio.ProximityRadio] stays as it is; the lab's «as a hider / as a seeker» goes through
 * it.
 */
interface LabAir {
    val canListen: Boolean

    /**
     * While collected: Android and the Mac hear every Apple frame (the overflow mask `0x01`, iBeacon `0x02 0x15`); an
     * iPhone scans for the table's 128 UUIDs and the game's service and reads the overflow bits CoreBluetooth lists.
     */
    fun listen(): Flow<AirFrame> = emptyFlow()

    val canProbe: Boolean

    /**
     * While collected: advertises the game's service and the table's UUIDs for [bits] (iOS). A change of [bits] in the
     * background is skipped, as the game's advertiser does (iOS can't start an advertisement there), and applied when
     * the app is active again.
     */
    fun probe(bits: StateFlow<Set<Int>>): Flow<ProbeEvent> = emptyFlow()
}

sealed interface AirFrame {
    val rssi: Int
    val peer: String?
    val atMillis: Long
    val api: RadioApi

    /** An overflow mask: its [bits] (Young's order), and [hex], the raw 16 bytes, where the platform has them. */
    data class Mask(
        val bits: Set<Int>,
        val hex: String?,
        override val rssi: Int,
        override val peer: String?,
        override val atMillis: Long,
        override val api: RadioApi,
    ) : AirFrame

    /** A token read another way: an iBeacon frame of the game, a hider's name or service data. */
    data class Token(
        val token: String,
        val via: SightingVia,
        override val rssi: Int,
        override val peer: String?,
        override val atMillis: Long,
        override val api: RadioApi,
    ) : AirFrame
}

/** [action]: `start`, `stop`, `failed` ([error]), `skipped_background`. */
data class ProbeEvent(val action: String, val error: String? = null)

/** The screen turned off by the proximity sensor while the app stays active (iOS; Android's wake lock to compare). */
interface LabScreen {
    val canTurnOffByProximity: Boolean

    fun setOffByProximity(on: Boolean) = Unit
}

enum class HapticKind {
    CORE_HAPTICS,
    IMPACT,
    NOTIFY_SILENT_SOUND,
    NOTIFY_NO_SOUND,
    VIBRATOR,
    ;

    val key: String get() = name.lowercase()

    /** A notification: at most one every few seconds, or iOS piles them up. */
    val isNotification: Boolean get() = this == NOTIFY_SILENT_SOUND || this == NOTIFY_NO_SOUND
}

/** [result]: `played`, `error` ([error]), `skipped` (the platform won't in this state, [error] says why). */
data class HapticResult(val result: String, val error: String? = null)

interface LabHaptics {
    /** What this phone can try, in the order of the vibration test. */
    val kinds: List<HapticKind>

    /** Before the test: asks for what it needs (notifications with a sound). */
    suspend fun prepare() = Unit

    /** One beat of [kind] at [strength] (0..1). */
    suspend fun play(kind: HapticKind, strength: Double = 1.0): HapticResult

    /** What an engine says by itself, e.g. Core Haptics stopped (`engine_stopped`) and why. */
    fun engineEvents(): Flow<Pair<HapticKind, String>> = emptyFlow()
}

/** Hands files to the system «Share»; they live in a temporary folder only for that. */
interface LabFiles {
    fun share(files: List<LabFile>)
}

data class LabFile(val name: String, val mimeType: String, val text: String)

class NoopLabProbes : LabProbes {
    override fun appState(): String = "-"

    override fun lifecycle(): Flow<String> = emptyFlow()

    override fun sensors(): Flow<LabSensorReading> = emptyFlow()

    override fun battery(): Flow<LabBattery> = emptyFlow()
}

class NoopLabAir : LabAir {
    override val canListen: Boolean = false
    override val canProbe: Boolean = false
}

class NoopLabScreen : LabScreen {
    override val canTurnOffByProximity: Boolean = false
}

class NoopLabHaptics : LabHaptics {
    override val kinds: List<HapticKind> = emptyList()

    override suspend fun play(kind: HapticKind, strength: Double): HapticResult = HapticResult("skipped", "no haptics")
}

class NoopLabFiles : LabFiles {
    override fun share(files: List<LabFile>) = Unit
}

/** The game's radios tell the lab's log what they advertise and scan ([RadioTrace]). */
class LabRadioTrace(private val log: LabLog) : RadioTrace {
    override fun advertise(action: String, mode: String, token: String?, error: String?) =
        log.adv(action, mode, token, error = error)

    override fun scan(action: String, api: RadioApi, filters: String?, error: String?) =
        log.scan(action, api, filters, error)
}
