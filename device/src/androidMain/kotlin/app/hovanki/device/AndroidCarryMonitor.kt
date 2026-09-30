package app.hovanki.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import app.hovanki.shared.protocol.Activity
import app.hovanki.shared.protocol.Carry
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.sqrt

/**
 * Where the phone is (docs/adr/0012-nearby-radar.md, «Карман») by the screen, the proximity sensor, the light and
 * the accelerometer: in the pocket only with the screen off, something right in front of the proximity sensor or no
 * light, and the phone being carried (a phone lying in the dark is unknown); in the hand with the screen on. A new
 * state has to hold for [SETTLE_MILLIS] before it is told. Nothing but the state leaves the phone.
 */
class AndroidCarryMonitor(private val context: Context) : CarryMonitor {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    override fun carry(): Flow<Carry> = callbackFlow {
        val manager = sensors
        if (manager == null) {
            close()
            return@callbackFlow
        }
        var screenOn = power?.isInteractive ?: true
        var near: Boolean? = null
        var dark: Boolean? = null
        val motion = ActivityClassifier()
        var candidate = Carry.UNKNOWN
        var candidateSinceMillis = 0L

        fun evaluate(nowMillis: Long) {
            val carried = motion.classify().let { it != Activity.STILL && it != Activity.UNKNOWN }
            val state = when {
                screenOn -> Carry.IN_HAND
                (near == true || dark == true) && carried -> Carry.IN_POCKET
                else -> Carry.UNKNOWN
            }
            if (state != candidate) {
                candidate = state
                candidateSinceMillis = nowMillis
            }
            if (nowMillis - candidateSinceMillis >= SETTLE_MILLIS) trySend(state)
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val nowMillis = event.timestamp / 1_000_000
                when (event.sensor.type) {
                    Sensor.TYPE_PROXIMITY -> near = event.values[0] < event.sensor.maximumRange.coerceAtMost(NEAR_CM)

                    Sensor.TYPE_LIGHT -> dark = event.values[0] < DARK_LUX

                    Sensor.TYPE_ACCELEROMETER -> {
                        val (x, y, z) = event.values
                        motion.add(nowMillis, sqrt((x * x + y * y + z * z).toDouble()))
                    }
                }
                evaluate(nowMillis)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                screenOn = intent.action == Intent.ACTION_SCREEN_ON
                evaluate(SystemClock.elapsedRealtime())
            }
        }
        context.registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        for (type in listOf(Sensor.TYPE_PROXIMITY, Sensor.TYPE_LIGHT, Sensor.TYPE_ACCELEROMETER)) {
            manager.getDefaultSensor(type)?.let { manager.registerListener(listener, it, SAMPLING_PERIOD_MICROS) }
        }
        awaitClose {
            manager.unregisterListener(listener)
            context.unregisterReceiver(screenReceiver)
        }
    }.distinctUntilChanged()

    private companion object {
        const val SAMPLING_PERIOD_MICROS = 100_000
        const val SETTLE_MILLIS = 1_500L
        const val NEAR_CM = 3f
        const val DARK_LUX = 5f
    }
}
