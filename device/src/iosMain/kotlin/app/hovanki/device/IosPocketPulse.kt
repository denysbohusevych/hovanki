package app.hovanki.device

import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.rules.HeartbeatRules
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

/**
 * The pulse (docs/adr/0012-nearby-radar.md, «Пульс») on iOS. On the screen the Taptic Engine beats a heartbeat
 * ([HeartbeatRules]): a light tap at the soft beat's strength, then a heavy one at the strong beat's, at the band's
 * pace. In the background a third-party app can't vibrate on its own; the phone gets a notification without a sound
 * (a sound would give the hiding place away) every [NOTIFICATION_MIN_PERIOD_MILLIS] at least, replaced in place, so
 * the lock screen shows «a seeker is near» and vibrates for each one where the player allows. The spike on real
 * phones decides whether an audio session lets the vibration itself through in the background.
 *
 * [notificationText] gives the notification's title and body in the player's language, read when it is posted: this
 * module has no string resources, the app passes its own («a seeker is near»).
 */
class IosPocketPulse(private val notificationText: suspend () -> Pair<String, String>) : PocketPulse {
    private val scope = MainScope()
    private var beating: Job? = null

    // Made on the main thread, where they are used, the first time the pulse beats.
    private val soft by lazy { UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight) }
    private val strong by lazy { UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy) }

    override fun set(band: RadarBand) {
        beating?.cancel()
        beating = null
        val beat = HeartbeatRules.beat(band)
        if (beat == null) {
            UNUserNotificationCenter.currentNotificationCenter()
                .removeDeliveredNotificationsWithIdentifiers(listOf(IDENTIFIER))
            return
        }
        beating = scope.launch {
            var lastNotifiedMillis = 0L
            var elapsed = 0L
            soft.prepare()
            strong.prepare()
            while (isActive) {
                val onScreen =
                    UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive
                if (onScreen) {
                    // Lub-DUB: the soft tap, the pause, the strong tap, then quiet till the period is over.
                    soft.impactOccurredWithIntensity(beat.softAmplitude)
                    delay(beat.softMillis + beat.gapMillis)
                    strong.impactOccurredWithIntensity(beat.strongAmplitude)
                    soft.prepare()
                    strong.prepare()
                    delay(beat.periodMillis - beat.softMillis - beat.gapMillis)
                } else {
                    if (elapsed - lastNotifiedMillis >= NOTIFICATION_MIN_PERIOD_MILLIS || lastNotifiedMillis == 0L) {
                        lastNotifiedMillis = elapsed.coerceAtLeast(1L)
                        notify()
                    }
                    delay(beat.periodMillis)
                }
                elapsed += beat.periodMillis
            }
        }
    }

    private suspend fun notify() {
        val (title, body) = notificationText()
        val content = UNMutableNotificationContent()
        content.setTitle(title)
        content.setBody(body)
        val request = UNNotificationRequest.requestWithIdentifier(IDENTIFIER, content, null)
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
    }

    private companion object {
        const val IDENTIFIER = "hovanki.pulse"
        const val NOTIFICATION_MIN_PERIOD_MILLIS = 4_000L
    }
}
