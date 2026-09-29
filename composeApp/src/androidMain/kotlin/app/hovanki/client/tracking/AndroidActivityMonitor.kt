package app.hovanki.client.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import app.hovanki.shared.protocol.Activity
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.sqrt

/**
 * The accelerometer into an [ActivityClassifier] (docs/adr/0013-quests-sparks-and-sensors.md, section 4): no
 * permission, nothing but the activity leaves the phone. About 50 readings a second while collected.
 */
class AndroidActivityMonitor(context: Context) : ActivityMonitor {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    override fun activity(): Flow<Activity> = callbackFlow {
        val manager = sensors
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) {
            close()
            return@callbackFlow
        }
        val classifier = ActivityClassifier()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = event.values
                val magnitude = sqrt((x * x + y * y + z * z).toDouble())
                trySend(classifier.add(event.timestamp / 1_000_000, magnitude))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SAMPLING_PERIOD_MICROS)
        awaitClose { manager.unregisterListener(listener) }
    }.distinctUntilChanged()

    private companion object {
        const val SAMPLING_PERIOD_MICROS = 20_000
    }
}
