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
import kotlin.time.TimeSource

/**
 * The pulse (docs/adr/0012-nearby-radar.md, «Пульс») on iOS. On the screen the Taptic Engine beats a heartbeat
 * ([HeartbeatRules]): a light tap at the soft beat's strength, then a heavy one at the strong beat's, at the band's
 * pace. In the background a third-party app can't vibrate on its own ([PocketPulseRules]):
 * - while the round's Live Activity runs ([round], only the field build starts one), two alerts on it 300 ms apart
 *   with a silent sound — what a locked iPhone feels in the pocket (docs/radio-lab.md §12) — every
 *   [PocketPulseRules.gapMillis] by band, kept across band changes; an alert refused gives way to the notification for that beat;
 * - otherwise (every other build, as before) a notification without a sound (a sound would give the hiding place away)
 *   every [PocketPulseRules.NOTIFICATION_GAP_MILLIS] at least, replaced in place, so the lock screen shows «a seeker
 *   is near» and vibrates for each one where the player allows.
 * Every beat off the screen goes to [RoundLiveActivity.trace] (the field log; nobody elsewhere).
 *
 * [notificationText] gives the notification's title and body in the player's language, read when it is posted: this
 * module has no string resources, the app passes its own («a seeker is near»). The alerts show the same.
 */
class IosPocketPulse(
    private val round: RoundLiveActivity = RoundLiveActivity(NoopLiveActivityHost()),
    private val notificationText: suspend () -> Pair<String, String>,
) : PocketPulse {
    private val scope = MainScope()
    private var beating: Job? = null

    /** When the last beat off the screen went out, across band changes (a new band restarts the loop, not the gap). */
    private var lastOffScreen: TimeSource.Monotonic.ValueTimeMark? = null

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
            soft.prepare()
            strong.prepare()
            while (isActive) {
                val onScreen =
                    UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive
                val way = PocketPulseRules.way(onScreen, round.canAlert)
                if (way == PulseWay.TAPS) {
                    // Lub-DUB: the soft tap, the pause, the strong tap, then quiet till the period is over.
                    soft.impactOccurredWithIntensity(beat.softAmplitude)
                    delay(beat.softMillis + beat.gapMillis)
                    strong.impactOccurredWithIntensity(beat.strongAmplitude)
                    soft.prepare()
                    strong.prepare()
                    delay(beat.periodMillis - beat.softMillis - beat.gapMillis)
                } else {
                    val since = lastOffScreen?.elapsedNow()?.inWholeMilliseconds
                    if (PocketPulseRules.isDue(since, way, band)) {
                        lastOffScreen = TimeSource.Monotonic.markNow()
                        offScreen(way)
                    }
                    delay(beat.periodMillis)
                }
            }
        }
    }

    /** One beat off the screen: the Live Activity's double alert, or the notification when it is refused or none. */
    private suspend fun offScreen(way: PulseWay) {
        val (title, body) = notificationText()
        if (way == PulseWay.LIVE_ACTIVITY && round.pulse(title, body).played) return
        notify(title, body)
    }

    private fun notify(title: String, body: String) {
        val content = UNMutableNotificationContent()
        content.setTitle(title)
        content.setBody(body)
        val request = UNNotificationRequest.requestWithIdentifier(IDENTIFIER, content, null)
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) { error ->
            // The completion comes on a queue of its own: the trace is the main thread's.
            if (error != null) scope.launch { round.trace.haptic(KIND, "error", error.localizedDescription, PULSE) }
        }
        round.trace.haptic(KIND, "played", reason = PULSE)
    }

    private companion object {
        const val IDENTIFIER = "hovanki.pulse"

        /** The notification's kind in the log: the lab's `HapticKind.NOTIFY_NO_SOUND`. */
        const val KIND = "notify_no_sound"
        const val PULSE = "pulse"
    }
}
