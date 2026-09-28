package app.hovanki.client.tracking

import app.hovanki.client.resources.Res
import app.hovanki.client.resources.alert_seeker_near_text
import app.hovanki.client.resources.alert_seeker_near_title
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.rules.HeartbeatRules
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
 * The pulse (docs/adr/0010-nearby-radar.md, «Пульс») on iOS. On the screen the system vibration beats at the band's
 * pace. In the background a third-party app can't vibrate on its own; the phone gets a notification without a sound
 * (a sound would give the hiding place away) every [NOTIFICATION_MIN_PERIOD_MILLIS] at least, replaced in place, so
 * the lock screen shows «a seeker is near» and vibrates for each one where the player allows. The spike on real
 * phones decides whether an audio session lets the vibration itself through in the background.
 */
class IosPocketPulse : PocketPulse {
    private val scope = MainScope()
    private var beating: Job? = null

    override fun set(band: RadarBand) {
        beating?.cancel()
        beating = null
        val period = HeartbeatRules.periodMillis(band)
        if (period == null) {
            UNUserNotificationCenter.currentNotificationCenter()
                .removeDeliveredNotificationsWithIdentifiers(listOf(IDENTIFIER))
            return
        }
        beating = scope.launch {
            var lastNotifiedMillis = 0L
            var elapsed = 0L
            while (isActive) {
                val onScreen =
                    UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive
                if (onScreen) {
                    AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
                } else if (elapsed - lastNotifiedMillis >= NOTIFICATION_MIN_PERIOD_MILLIS || lastNotifiedMillis == 0L) {
                    lastNotifiedMillis = elapsed.coerceAtLeast(1L)
                    notify()
                }
                delay(period)
                elapsed += period
            }
        }
    }

    private suspend fun notify() {
        val content = UNMutableNotificationContent()
        content.setTitle(getString(Res.string.alert_seeker_near_title))
        content.setBody(getString(Res.string.alert_seeker_near_text))
        val request = UNNotificationRequest.requestWithIdentifier(IDENTIFIER, content, null)
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
    }

    private companion object {
        const val IDENTIFIER = "hovanki.pulse"
        const val NOTIFICATION_MIN_PERIOD_MILLIS = 4_000L
    }
}
