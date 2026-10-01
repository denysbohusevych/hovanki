package app.hovanki.client.lab

import app.hovanki.device.ActivityClassifier
import app.hovanki.device.CarryMonitor
import app.hovanki.device.lab.CarryClassifier
import app.hovanki.device.lab.CarrySignals
import app.hovanki.device.lab.Gravity
import app.hovanki.device.lab.LabProbes
import app.hovanki.device.lab.LabSensorReading
import app.hovanki.device.lab.MotionWindow
import app.hovanki.device.lab.Orientation
import app.hovanki.shared.lab.CarryTechs
import app.hovanki.shared.protocol.Activity
import app.hovanki.shared.protocol.Carry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The pocket's classifiers in the shadow (docs/adr/0017-radar-techniques-and-big-run.md §2.3 «Конкурируют»,
 * docs/radio-lab.md §7.3): the game's monitor (`carry.v1`) and the candidate [CarryClassifier] (`carry.v2`) side by side, each written
 * to the [log] as a `shadow` event when its state changes, never used by the game. Fed by the phone's sensors
 * ([onSensor]), the game's monitor ([onCarryV1]) and the screen ([second], once a second); [run] does it all by
 * itself from the [LabProbes] and a [CarryMonitor] (the field log), the lab feeds it from its own loops. Writes only
 * while the [log] is written. Main thread.
 */
class CarryShadow(private val log: LabLog, private val classifier: CarryClassifier = CarryClassifier()) {
    private val motion = MotionWindow()
    private val activity = ActivityClassifier()
    private var gravity: Gravity? = null
    private var near: Boolean? = null
    private var lastV1: String? = null
    private var lastV2: String? = null

    fun onSensor(reading: LabSensorReading) {
        when (reading) {
            is LabSensorReading.Motion -> {
                motion.add(reading.atMillis, reading.magnitudeG)
                activity.add(reading.atMillis, reading.magnitudeG * STANDARD_GRAVITY)
                reading.gravity?.let { gravity = it }
            }

            is LabSensorReading.Proximity -> near = reading.near

            is LabSensorReading.Light -> Unit
        }
    }

    /** What the game's monitor says now: `carry.v1`. */
    fun onCarryV1(carry: Carry) {
        val state = carry.key
        if (!log.isWriting || state == lastV1) return
        lastV1 = state
        log.shadowState(CarryTechs.V1, state)
    }

    /**
     * Once a second at [nowMillis] (device clock): the candidate's verdict from the last seconds' sensors and the
     * app's state ([appState]: `active` / `screen_on` the screen on, `inactive` / `background` / `screen_off` not).
     */
    fun second(nowMillis: Long, appState: String) {
        if (!log.isWriting) return
        val moving = when (activity.classify()) {
            Activity.WALKING, Activity.RUNNING -> true
            Activity.STILL -> false
            else -> null
        }
        val verdict = classifier.classify(
            CarrySignals(
                atMillis = nowMillis,
                screenOn = screenOn(appState),
                near = near,
                orientation = gravity?.let(Orientation::of),
                std = motion.std(),
                moving = moving,
            ),
        )
        val state = verdict.state.key
        if (state == lastV2) return
        lastV2 = state
        log.shadowState(CarryTechs.V2, state, verdict.why)
    }

    /** Feeds itself from [probes] and [carryMonitor] until cancelled: the field log's way. */
    suspend fun run(probes: LabProbes, carryMonitor: CarryMonitor) = coroutineScope {
        launch { quietly { probes.sensors().collect(::onSensor) } }
        launch { quietly { carryMonitor.carry().collect(::onCarryV1) } }
        seconds(probes::appState)
    }

    /** The candidate's verdict once a second until cancelled, the sensors fed from outside ([onSensor], [onCarryV1]). */
    suspend fun seconds(appState: () -> String) {
        while (currentCoroutineContext().isActive) {
            second(log.deviceNow(), appState())
            delay(SECOND_MILLIS)
        }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A sensor that fails: the shadow goes on without it.
        }
    }

    companion object {
        const val SECOND_MILLIS = 1_000L
        private const val STANDARD_GRAVITY = 9.81

        private val Carry.key: String get() = name.lowercase()

        /** The screen by the app's state; null: the platform said nothing we know. */
        fun screenOn(appState: String): Boolean? = when (appState) {
            "active", "screen_on" -> true
            "inactive", "background", "screen_off" -> false
            else -> null
        }
    }
}
