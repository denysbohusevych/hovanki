package app.hovanki.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.sqrt

/**
 * The accelerometer about a hundred times a second while collected, its lone jolts found by [ImpactDetector]. The
 * sensor's time (since boot) is turned into the device's clock, so a jolt keeps the moment it happened however late
 * it is told.
 */
class AndroidImpactMonitor(context: Context) : ImpactMonitor {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    override fun impacts(): Flow<Impact> = callbackFlow {
        val manager = sensors
        val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || accelerometer == null) {
            close()
            return@callbackFlow
        }
        val detector = ImpactDetector()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = event.values
                val magnitude = sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH
                val sinceMillis = (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
                val atMillis = System.currentTimeMillis() - sinceMillis.coerceAtLeast(0)
                detector.add(atMillis, magnitude)?.let { trySend(it) }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, accelerometer, (ImpactDetector.SAMPLING_MILLIS * 1_000).toInt())
        awaitClose { manager.unregisterListener(listener) }
    }
}
