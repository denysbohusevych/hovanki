package app.hovanki.device.lab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * The radio lab's view of an Android phone (docs/radio-lab.md §4.1): the screen, Doze and power saving, the sensors
 * the carry monitor uses (the accelerometer, gravity, the proximity sensor's raw distance, the light) and the battery.
 * Debug builds only.
 */
class AndroidLabProbes(private val context: Context) : LabProbes {
    private val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    override val os: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    override fun appState(): String = if (power?.isInteractive != false) "screen_on" else "screen_off"

    override fun lifecycle(): Flow<String> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val event = when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> "screen_on"

                    Intent.ACTION_SCREEN_OFF -> "screen_off"

                    Intent.ACTION_USER_PRESENT -> "user_present"

                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED ->
                        if (power?.isDeviceIdleMode == true) "doze_on" else "doze_off"

                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED ->
                        if (power?.isPowerSaveMode == true) "low_power_on" else "low_power_off"

                    else -> return
                }
                trySend(event)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    override fun sensors(): Flow<LabSensorReading> = callbackFlow {
        val manager = sensors
        if (manager == null) {
            close()
            return@callbackFlow
        }
        var gravity: Gravity? = null
        var proximity: LabSensorReading.Proximity? = null
        var lux: Double? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val values = event.values
                when (event.sensor.type) {
                    // Android's gravity points up, away from the earth: the lab's frame points down.
                    Sensor.TYPE_GRAVITY -> gravity = Gravity(
                        -values[0] / SensorManager.GRAVITY_EARTH.toDouble(),
                        -values[1] / SensorManager.GRAVITY_EARTH.toDouble(),
                        -values[2] / SensorManager.GRAVITY_EARTH.toDouble(),
                    )

                    Sensor.TYPE_ACCELEROMETER -> {
                        val (x, y, z) = values
                        val magnitude = sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH
                        trySend(LabSensorReading.Motion(event.timestamp / 1_000_000, magnitude, gravity))
                    }

                    Sensor.TYPE_PROXIMITY -> {
                        val max = event.sensor.maximumRange.toDouble()
                        val raw = values[0].toDouble()
                        val reading = LabSensorReading.Proximity(raw < minOf(max, NEAR_CM), raw, max)
                        if (reading.near != proximity?.near) trySend(reading)
                        proximity = reading
                    }

                    Sensor.TYPE_LIGHT -> lux = values[0].toDouble()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        for (type in listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GRAVITY, Sensor.TYPE_PROXIMITY, Sensor.TYPE_LIGHT)) {
            manager.getDefaultSensor(type)?.let { manager.registerListener(listener, it, SAMPLING_PERIOD_MICROS) }
        }
        // The proximity sensor and the light say only changes, and with the screen off some phones say nothing at all:
        // the last values once a second show both.
        val everySecond = launch {
            while (true) {
                delay(SECOND_MILLIS)
                proximity?.let { trySend(it) }
                lux?.let { trySend(LabSensorReading.Light(it)) }
            }
        }
        awaitClose {
            everySecond.cancel()
            manager.unregisterListener(listener)
        }
    }

    override fun battery(): Flow<LabBattery> = callbackFlow {
        var last: LabBattery? = null
        fun read(intent: Intent?): LabBattery? {
            intent ?: return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val state = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                else -> "unknown"
            }
            val share = if (level >= 0 && scale > 0) level.toDouble() / scale else null
            return LabBattery(share, state, power?.isPowerSaveMode)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val battery = read(intent) ?: return
                if (battery != last) trySend(battery)
                last = battery
            }
        }
        // The battery's broadcast is sticky: registering hands over the current state right away.
        read(context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))?.let {
            last = it
            trySend(it)
        }
        val everyMinute = launch {
            while (true) {
                delay(MINUTE_MILLIS)
                last?.let { trySend(it) }
            }
        }
        awaitClose {
            everyMinute.cancel()
            context.unregisterReceiver(receiver)
        }
    }

    override fun thermal(): Flow<String> = callbackFlow {
        val manager = power
        if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            close()
            return@callbackFlow
        }
        trySend(thermalName(manager.currentThermalStatus))
        val listener = PowerManager.OnThermalStatusChangedListener { status -> trySend(thermalName(status)) }
        manager.addThermalStatusListener(context.mainExecutor, listener)
        awaitClose { manager.removeThermalStatusListener(listener) }
    }

    private fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }

    private companion object {
        const val SAMPLING_PERIOD_MICROS = 100_000
        const val NEAR_CM = 3.0
        const val SECOND_MILLIS = 1_000L
        const val MINUTE_MILLIS = 60_000L
    }
}
