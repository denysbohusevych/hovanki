package app.hovanki.device.lab

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/*
 * What the radio lab needs from the phone itself (docs/radio-lab.md §5): the app's life, the sensors, the battery,
 * the screen and the vibration. Implemented in this module's androidMain and iosMain, the no-op ones below everywhere
 * else. Debug builds only use them: the lab's screen exists only there. Flows are collected on the main thread and may
 * emit from it only.
 */

/** The app's life, the sensors and the battery. */
interface LabProbes {
    /** The system and its version for the log's header: «Android 16 (API 36)», «iOS 26.0». */
    val os: String? get() = null

    /** The app's state now: `active` / `inactive` / `background` (iOS), `screen_on` / `screen_off` (Android). */
    fun appState(): String

    /**
     * Life and power events while collected: `will_resign`, `did_enter_background`, `will_enter_foreground`,
     * `did_become_active`, `protected_data_off` / `_on`, `screen_on` / `_off`, `doze_on` / `_off`, `low_power_on` /
     * `_off`, `memory_warning`, `terminate`.
     */
    fun lifecycle(): Flow<String>

    /**
     * Motion about 50 times a second (a knock of the touch calibration is short), the proximity sensor and the light
     * as the platform gives them.
     */
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

/** The screen turned off by the proximity sensor while the app stays active (iOS; Android's wake lock to compare). */
interface LabScreen {
    val canTurnOffByProximity: Boolean

    fun setOffByProximity(on: Boolean) = Unit
}

enum class HapticKind {
    CORE_HAPTICS,

    /**
     * Core Haptics on an engine made with the app's audio session (`pulse.core_haptics.audio`): with `mode.audio` on,
     * the session keeps playing on the lock, so the engine may not be stopped for its interruption.
     */
    CORE_HAPTICS_AUDIO,
    IMPACT,
    NOTIFY_SILENT_SOUND,
    NOTIFY_NO_SOUND,
    VIBRATOR,

    /**
     * An alert on the running Live Activity with a silent sound (`pulse.live_activity`): the system plays the sound's
     * haptic on the lock screen, like a notification's, without a notification. Needs `mode.live_activity` on.
     */
    LIVE_ACTIVITY_ALERT,

    /** Two alerts on the Live Activity 300 ms apart: a longer beat, if iOS gives both their haptic. */
    LIVE_ACTIVITY_ALERT_DOUBLE,

    /**
     * A notification whose silent sound is a ringtone (`UNNotificationSound.ringtoneSoundNamed`): iOS vibrates it
     * with the ringtone's pattern, longer than a notification's.
     */
    NOTIFY_SILENT_RINGTONE,
    ;

    val key: String get() = name.lowercase()

    /** A notification: at most one every few seconds, or iOS piles them up. */
    val isNotification: Boolean get() =
        this == NOTIFY_SILENT_SOUND || this == NOTIFY_NO_SOUND || this == NOTIFY_SILENT_RINGTONE ||
            this == LIVE_ACTIVITY_ALERT || this == LIVE_ACTIVITY_ALERT_DOUBLE
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

    /** A notification with [text], to reach the tester off the screen; nothing where there is none. */
    suspend fun notify(text: String) = Unit

    /**
     * What an engine says by itself, e.g. Core Haptics stopped (`engine_stopped`) and why ([HapticStopReason]:
     * `engine_stopped: audio_session_interrupt (1)`).
     */
    fun engineEvents(): Flow<Pair<HapticKind, String>> = emptyFlow()
}

class NoopLabProbes : LabProbes {
    override fun appState(): String = "-"

    override fun lifecycle(): Flow<String> = emptyFlow()

    override fun sensors(): Flow<LabSensorReading> = emptyFlow()

    override fun battery(): Flow<LabBattery> = emptyFlow()
}

class NoopLabScreen : LabScreen {
    override val canTurnOffByProximity: Boolean = false
}

class NoopLabHaptics : LabHaptics {
    override val kinds: List<HapticKind> = emptyList()

    override suspend fun play(kind: HapticKind, strength: Double): HapticResult = HapticResult("skipped", "no haptics")
}
