package app.hovanki.client.tracking

import app.hovanki.client.resources.Res
import app.hovanki.client.resources.alert_claim_text
import app.hovanki.client.resources.alert_glow_soon_text
import app.hovanki.client.resources.alert_glow_soon_title
import app.hovanki.client.resources.alert_glowing_text
import app.hovanki.client.resources.alert_glowing_title
import app.hovanki.client.resources.alert_in_building_text
import app.hovanki.client.resources.alert_in_building_title
import app.hovanki.client.resources.alert_out_of_zone_text
import app.hovanki.client.resources.alert_out_of_zone_title
import app.hovanki.client.resources.hider_claim_title
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.AudioToolbox.kSystemSoundID_Vibrate
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

/**
 * Tracking itself needs nothing on iOS: CLLocationManager with `allowsBackgroundLocationUpdates`
 * (UIBackgroundModes = location) keeps the app running in the background while location updates are active.
 *
 * The hider's alerts, while the app is not on screen: a vibration and a notification without a sound (a sound would
 * give the hiding place away), if the player allowed notifications (asked together with location).
 */
class IosBackgroundTracker : BackgroundTracker {
    private val scope = MainScope()
    private val shows = mutableMapOf<AlertKind, Job>()

    override fun start() = Unit

    override fun stop() = Unit

    override fun alert(alert: HiderAlert) {
        shows.remove(alert.kind)?.cancel()
        shows[alert.kind] = scope.launch {
            if (UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive) {
                return@launch
            }
            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
            val (title, text) = when (alert.kind) {
                AlertKind.OUT_OF_ZONE -> getString(Res.string.alert_out_of_zone_title) to
                    getString(Res.string.alert_out_of_zone_text)

                AlertKind.IN_BUILDING -> getString(Res.string.alert_in_building_title) to
                    getString(Res.string.alert_in_building_text)

                AlertKind.CATCH_CLAIM -> getString(Res.string.hider_claim_title, alert.seekerName.orEmpty()) to
                    getString(Res.string.alert_claim_text)

                AlertKind.GLOW_SOON -> getString(Res.string.alert_glow_soon_title) to
                    getString(Res.string.alert_glow_soon_text)

                AlertKind.GLOWING -> getString(Res.string.alert_glowing_title) to
                    getString(Res.string.alert_glowing_text)
            }
            val content = UNMutableNotificationContent()
            content.setTitle(title)
            content.setBody(text)
            val request = UNNotificationRequest.requestWithIdentifier(identifier(alert.kind), content, null)
            UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
        }
    }

    override fun endAlert(kind: AlertKind) {
        shows.remove(kind)?.cancel()
        UNUserNotificationCenter.currentNotificationCenter()
            .removeDeliveredNotificationsWithIdentifiers(listOf(identifier(kind)))
    }

    private fun identifier(kind: AlertKind) = "hovanki.alert.${kind.name}"
}
