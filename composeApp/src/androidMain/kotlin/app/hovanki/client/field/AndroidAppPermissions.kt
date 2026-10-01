package app.hovanki.client.field

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import app.hovanki.client.lab.AppPermissions
import app.hovanki.shared.lab.PermFields

/** The permissions the field log writes on Android: location (precise, background), notifications, camera, battery. */
class AndroidAppPermissions(private val context: Context) : AppPermissions {
    override fun states(): Map<String, String> = buildMap {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        put(
            PermFields.LOCATION,
            when {
                !fine && !coarse -> "denied"
                background -> "always"
                else -> "when_in_use"
            },
        )
        put(PermFields.PRECISE, if (fine) "on" else "off")
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (notifications != null) {
            put(PermFields.NOTIFICATIONS, if (notifications.areNotificationsEnabled()) "on" else "off")
        }
        put(PermFields.CAMERA, if (granted(Manifest.permission.CAMERA)) "on" else "denied")
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power != null) {
            put(PermFields.POWER_SAVER, if (power.isPowerSaveMode) "on" else "off")
            // «on»: the system may put the app to sleep to save the battery; the game wants «off».
            val ignored = power.isIgnoringBatteryOptimizations(context.packageName)
            put(PermFields.BATTERY_OPTIMIZATION, if (ignored) "off" else "on")
        }
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
