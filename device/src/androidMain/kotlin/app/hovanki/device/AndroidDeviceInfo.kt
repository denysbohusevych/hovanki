package app.hovanki.device

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import app.hovanki.shared.protocol.Platform

class AndroidDeviceInfo(context: Context) : DeviceInfo {
    override val platform: Platform = Platform.ANDROID

    /** A UWB chip; ranging itself is not implemented yet (`NoopPrecisionRadio`), so the server pairs nobody. */
    override val hasUwb: Boolean = context.packageManager.hasSystemFeature(FEATURE_UWB)

    override val hasActivitySensor: Boolean =
        (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
            ?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    /** «Google Pixel 8»: the maker and the model, for the radar's readings by model. */
    override val model: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private companion object {
        /** `PackageManager.FEATURE_UWB` is API 31+; the string works everywhere. */
        const val FEATURE_UWB = "android.hardware.uwb"
    }
}
