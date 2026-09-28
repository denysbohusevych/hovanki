package app.hovanki.client.device

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import app.hovanki.shared.protocol.Platform

class AndroidDeviceInfo(context: Context) : DeviceInfo {
    override val platform: Platform = Platform.ANDROID

    /** A UWB chip; ranging itself is not implemented yet (`NoopPrecisionRadio`), so the server pairs nobody. */
    override val hasUwb: Boolean = context.packageManager.hasSystemFeature(FEATURE_UWB)

    override val hasActivitySensor: Boolean =
        (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
            ?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    private companion object {
        /** `PackageManager.FEATURE_UWB` is API 31+; the string works everywhere. */
        const val FEATURE_UWB = "android.hardware.uwb"
    }
}
