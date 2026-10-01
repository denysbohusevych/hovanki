package app.hovanki.client.lab

import app.hovanki.device.ActivityClassifier
import app.hovanki.device.CarryClassifier
import app.hovanki.device.CarryInputs
import app.hovanki.device.Gravity
import app.hovanki.device.Orientation
import app.hovanki.device.lab.LabProbes
import app.hovanki.device.lab.LabSensorReading
import app.hovanki.device.lab.MotionWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * `carry.v2` in the shadow of the game's carry monitor during a field game's round (docs/adr/0018-field-test-build.md
 * §4, the lab's [CarryClassifier], docs/adr/0017-radar-techniques-and-big-run.md §2.3): fed by the phone's sensors
 * ([onSensor]) and the screen ([second], once a second), as the lab's controller feeds it, and written to the [log]
 * as a `shadow` event (`tech`, `state`, `reason`) when its state changes, not every second: the field log is thinned.
 * The game's own monitor (`carry.v1`) is the `carry` events. Never used by the game. Writes only while the [log] is
 * written. Main thread.
 */
class CarryShadow(private val log: LabLog, private val classifier: CarryClassifier = CarryClassifier()) {
    private val motion = MotionWindow()
    private val activity = ActivityClassifier()
    private var gravity: Gravity? = null
    private var near: Boolean? = null
    private var lux: Double? = null
    private var lastMotionMono: Long? = null
    private var last: String? = null

    fun onSensor(reading: LabSensorReading) {
        when (reading) {
            is LabSensorReading.Motion -> {
                motion.add(reading.atMillis, reading.magnitudeG)
                activity.add(reading.atMillis, reading.magnitudeG * STANDARD_GRAVITY)
                reading.gravity?.let { gravity = it }
                lastMotionMono = log.monoNow()
            }

            is LabSensorReading.Proximity -> near = reading.near

            is LabSensorReading.Light -> lux = reading.lux
        }
    }

    /**
     * Once a second: the classifier's verdict from the last seconds' sensors (none when they said nothing for
     * [MOTION_STALE_MILLIS]: it keeps its state) and the app's state ([appState]: `active` / `screen_on` the screen
     * on, `inactive` / `background` / `screen_off` not). [screenOffByProximity]: the field build turned the screen off
     * by the proximity sensor while the app stays active (iOS; docs/adr/0018-field-test-build.md §4).
     */
    fun second(appState: String, screenOffByProximity: Boolean = false) {
        if (!log.isWriting) return
        val motionMono = lastMotionMono
        val fresh = motionMono != null && log.monoNow() - motionMono <= MOTION_STALE_MILLIS
        val screenOn = screenOn(appState)
        if (screenOn == null && !fresh) return
        val gravity = gravity.takeIf { fresh }
        val verdict = classifier.add(
            CarryInputs(
                atMillis = log.monoNow(),
                screenOn = screenOn,
                screenOffByProximity = screenOffByProximity,
                near = near,
                lux = lux,
                orientation = gravity?.let(Orientation::of),
                std = if (fresh) motion.std() else null,
                activity = if (fresh) activity.classify() else null,
            ),
        )
        val state = verdict.carry.name.lowercase()
        if (state == last) return
        last = state
        log.shadow(CARRY_V2, state, verdict.reason)
    }

    /** The verdict once a second until cancelled, the sensors fed from outside ([onSensor]). */
    suspend fun seconds(probes: LabProbes, screenOffByProximity: () -> Boolean = { false }) {
        while (currentCoroutineContext().isActive) {
            try {
                second(probes.appState(), screenOffByProximity())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A probe that fails: the shadow goes on without it.
            }
            delay(SECOND_MILLIS)
        }
    }

    companion object {
        const val SECOND_MILLIS = 1_000L

        /** The classifier's id in the log, as the lab's ([LabController.CARRY_V2]). */
        const val CARRY_V2 = LabController.CARRY_V2

        /** The motion this old is no motion: the classifier keeps its state. */
        const val MOTION_STALE_MILLIS = LabController.MOTION_STALE_MILLIS

        private const val STANDARD_GRAVITY = 9.81

        /** The screen by the app's state; null: the platform said nothing we know. */
        fun screenOn(appState: String): Boolean? = LabController.screenOnOf(appState)
    }
}
