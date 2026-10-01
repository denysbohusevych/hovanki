package app.hovanki.e2e.bot

import app.hovanki.device.lab.ImpactDetector
import app.hovanki.device.lab.LabBattery
import app.hovanki.device.lab.LabProbes
import app.hovanki.device.lab.LabSensorReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A bot's phone itself as the field log reads it (`LabProbes`): the screen always on, no life events, no battery, and
 * the accelerometer silent but for the knocks the scenario makes ([knock]: the field log's touch, ADR 0018 §5). The
 * knock is two readings on the device's [clock], the peak and a quiet one after it, as the lab's [ImpactDetector]
 * wants them.
 */
class BotProbes(private val clock: () -> Long) : LabProbes {
    private val sensors = MutableSharedFlow<LabSensorReading>(extraBufferCapacity = 16)

    /** A knock of [peakG] beyond gravity now. */
    fun knock(peakG: Double = KNOCK_G) {
        val at = clock()
        sensors.tryEmit(LabSensorReading.Motion(at, 1.0 + peakG, null))
        sensors.tryEmit(LabSensorReading.Motion(at + ImpactDetector.PEAK_MILLIS + QUIET_AFTER_MILLIS, 1.0, null))
    }

    override fun appState(): String = "active"

    override fun lifecycle(): Flow<String> = emptyFlow()

    override fun sensors(): Flow<LabSensorReading> = sensors

    override fun battery(): Flow<LabBattery> = emptyFlow()

    companion object {
        const val KNOCK_G = 1.6
        private const val QUIET_AFTER_MILLIS = 50L
    }
}
