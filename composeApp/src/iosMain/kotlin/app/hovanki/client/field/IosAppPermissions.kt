@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.client.field

import app.hovanki.client.lab.AppPermissions
import app.hovanki.shared.lab.PermFields
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.CoreLocation.CLAccuracyAuthorization
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.Foundation.NSProcessInfo
import platform.Foundation.lowPowerModeEnabled
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.concurrent.Volatile

/**
 * The permissions the field log writes on iOS: location (precise, always), notifications, camera, low power mode. The
 * notification settings come asynchronously: the answer of the last asking is the one given now.
 */
class IosAppPermissions : AppPermissions {
    @Volatile
    private var notifications: String? = null
    private val manager = CLLocationManager()

    override fun states(): Map<String, String> = buildMap {
        put(
            PermFields.LOCATION,
            when (manager.authorizationStatus) {
                kCLAuthorizationStatusAuthorizedAlways -> "always"
                kCLAuthorizationStatusAuthorizedWhenInUse -> "when_in_use"
                else -> "denied"
            },
        )
        val full = manager.accuracyAuthorization == CLAccuracyAuthorization.CLAccuracyAuthorizationFullAccuracy
        put(PermFields.PRECISE, if (full) "on" else "off")
        refreshNotifications()
        notifications?.let { put(PermFields.NOTIFICATIONS, it) }
        val camera = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
        put(PermFields.CAMERA, if (camera == AVAuthorizationStatusAuthorized) "on" else "denied")
        put(PermFields.POWER_SAVER, if (NSProcessInfo.processInfo.lowPowerModeEnabled) "on" else "off")
    }

    private fun refreshNotifications() {
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            val allowed = status == UNAuthorizationStatusAuthorized || status == UNAuthorizationStatusProvisional
            notifications = if (allowed) "on" else "off"
        }
    }
}
