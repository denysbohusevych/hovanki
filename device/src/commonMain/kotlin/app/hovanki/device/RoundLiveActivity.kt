package app.hovanki.device

import app.hovanki.shared.protocol.RadarBand
import kotlinx.coroutines.delay

/**
 * What the pocket's pulse, the round's Live Activity and the screen by the proximity sensor did, for the field log of
 * the field build (docs/adr/0018-field-test-build.md, wave 4): `mode` and `haptic` events as the radio lab writes
 * them, so the report counts the pulses that went out against the ones refused. Nobody writes anything elsewhere
 * ([None]). Main thread.
 */
interface PocketTrace {
    /** [mode]: `mode.live_activity`, `mode.proximity_screen`; [event] what happened, [reason] why. */
    fun mode(mode: String, event: String, reason: String? = null) = Unit

    /**
     * One beat of the pulse off the screen: [kind] as the lab's `HapticKind.key` (`live_activity_alert_double`,
     * `notify_no_sound`), [result] `played` / `skipped` / `error`.
     */
    fun haptic(kind: String, result: String, error: String? = null, reason: String? = null) = Unit

    object None : PocketTrace
}

/** What the round's Live Activity needs from the platform besides its [LiveActivityHost]. */
interface LiveActivityPlatform {
    /**
     * Calls [block] when the app is about to leave the screen (iOS `willResignActive`: the last moment iOS still grants
     * an activity), synchronously, until the returned function is called.
     */
    fun onWillResignActive(block: () -> Unit): () -> Unit

    /**
     * Makes sure the alert's file of silence is where iOS looks for it (`Library/Sounds/hovanki-silent.wav`); false:
     * it isn't, and an alert would play the default sound, giving the hiding place away.
     */
    fun prepareSilentSound(): Boolean

    /** No platform: Android, the JVM bots. */
    object None : LiveActivityPlatform {
        override fun onWillResignActive(block: () -> Unit): () -> Unit = {}

        override fun prepareSilentSound(): Boolean = false
    }
}

/** What the round's Live Activity shows: [text] the role, [band] the radar's band as a colour, [detail] its word. */
data class LiveCard(val text: String, val band: RadarBand = RadarBand.NONE, val detail: String = "")

/**
 * The round's Live Activity of the field build on iOS (docs/adr/0018-field-test-build.md, wave 4; ADR 0017 §2.3
 * `mode.live_activity`, `pulse.live_activity.double`): a card on the lock screen with the role and the radar's band,
 * and the one way a locked iPhone vibrates in the pocket (docs/radio-lab.md §12: two alerts 300 ms apart read clearly,
 * one is felt). iOS grants an activity only to an app on the screen: it is started when the round begins on the screen
 * ([want]) and again when the app is about to leave it ([LiveActivityPlatform.onWillResignActive]) if none runs. The
 * card is updated only when it changes (iOS budgets the updates); [end] takes it off when the round is over or the
 * phone leaves the game. Without the Swift host ([LiveActivityHost.isAvailable] false: the widget extension isn't in
 * the build) nothing starts and the pulse keeps its notification. The game's pulse ([PocketPulse]) asks [canAlert]
 * and beats by [pulse]; nothing ever starts one outside the field build, so release and debug games are as before.
 * Every attempt and result goes to [trace]. Main thread.
 */
class RoundLiveActivity(
    private val host: LiveActivityHost,
    private val platform: LiveActivityPlatform = LiveActivityPlatform.None,
) {
    /** Where the attempts go: the field log while it writes, nobody otherwise. */
    var trace: PocketTrace = PocketTrace.None

    /** The round wants a card: from [want] until [end]. */
    var isWanted: Boolean = false
        private set

    /** iOS took the card and it still runs (an alert refused by the host means it is gone). */
    var isRunning: Boolean = false
        private set

    /**
     * Alerts may go out: a card runs and the file of silence is there. The pulse beats by the Live Activity's way and
     * gaps only then; otherwise by the notification's ([PocketPulseRules.way]).
     */
    val canAlert: Boolean get() = isRunning && silentSound

    /** The card's updates and alerts since it started: iOS budgets them, the log counts them. */
    var updates: Int = 0
        private set
    var alerts: Int = 0
        private set

    private var title = ""
    private var card = LiveCard("")
    private var shown: LiveCard? = null
    private var silentSound = false
    private var unobserve: (() -> Unit)? = null

    /**
     * The round wants its card with [title] and [card]: the first call sets it up and starts it at once when the app
     * is [onScreen] (else when it comes to the screen and leaves it again); later calls change the card ([show]).
     */
    fun want(title: String, card: LiveCard, onScreen: Boolean) {
        if (isWanted) {
            show(card)
            return
        }
        isWanted = true
        this.title = title
        this.card = card
        if (!host.isAvailable) {
            trace.mode(MODE, UNAVAILABLE, "no live activity host (Android, or no widget extension in the build)")
            return
        }
        silentSound = platform.prepareSilentSound()
        if (!silentSound) trace.mode(MODE, "silent_sound_missing", "the pulse keeps its notification")
        unobserve = platform.onWillResignActive { start(WILL_RESIGN) }
        if (onScreen) start(ROUND)
    }

    /** A new [card]: sent only when it differs from the one shown (iOS budgets the updates). */
    fun show(card: LiveCard) {
        this.card = card
        if (!isRunning || card == shown) return
        send(card, "status")
    }

    /**
     * One beat of the pulse off the screen: two alerts [ALERT_GAP_MILLIS] apart with the file of silence, so only the
     * vibration is left (`pulse.live_activity.double`). `skipped` when no card runs or the silence is missing: the
     * caller falls back to its notification. [alertTitle] and [alertText]: what the lock screen shows with it.
     */
    suspend fun pulse(alertTitle: String, alertText: String): Beat {
        val result = when {
            !isRunning -> Beat(SKIPPED, "no live activity running")
            !silentSound -> Beat(SKIPPED, "no silent sound")
            else -> alertTwice(alertTitle, alertText)
        }
        trace.haptic(KIND, result.result, result.error, reason = "pulse #$alerts")
        return result
    }

    private suspend fun alertTwice(alertTitle: String, alertText: String): Beat {
        repeat(2) { index ->
            if (index > 0) delay(ALERT_GAP_MILLIS)
            // The round may have ended between the two.
            if (!isRunning) return Beat(SKIPPED, "the round ended between the alerts")
            if (!host.alert(alertTitle, alertText, silent = true)) {
                // The player swiped the card away, turned Live Activities off, or iOS ended it: the card is gone, the
                // pulse falls back to its notification until a new one starts when the app leaves the screen again.
                lost()
                return Beat(SKIPPED, if (index == 0) "the host refused the alert" else "the second alert refused")
            }
            alerts++
        }
        return Beat(PLAYED)
    }

    /** The round is over for this phone ([reason]): the card goes off the lock screen. */
    fun end(reason: String) {
        if (!isWanted) return
        isWanted = false
        unobserve?.invoke()
        unobserve = null
        if (isRunning) {
            host.end()
            trace.mode(MODE, "live_activity_ended", "$reason; updates $updates, alerts $alerts")
        }
        isRunning = false
        shown = null
        updates = 0
        alerts = 0
    }

    private fun lost() {
        isRunning = false
        shown = null
        trace.mode(MODE, "live_activity_lost", "the host refused an alert; updates $updates, alerts $alerts")
    }

    private fun start(why: String) {
        if (!isWanted || isRunning || !host.isAvailable) return
        if (!host.start(title, card.text)) {
            // Live Activities off in the settings, or iOS thinks the app has left the screen already.
            trace.mode(MODE, "live_activity_refused", why)
            return
        }
        isRunning = true
        trace.mode(MODE, "live_activity_started", why)
        send(card, why)
    }

    private fun send(card: LiveCard, why: String) {
        host.update(card.text, band = card.band.ordinal, detail = card.detail)
        shown = card
        updates++
        trace.mode(MODE, "live_activity_updated", "$why #$updates")
    }

    companion object {
        const val MODE = ModeIds.LIVE_ACTIVITY

        /** The pulse's kind in the log: the lab's `HapticKind.LIVE_ACTIVITY_ALERT_DOUBLE`. */
        const val KIND = "live_activity_alert_double"

        /** Between the two alerts of a beat (docs/radio-lab.md §12). */
        const val ALERT_GAP_MILLIS = 300L
        const val UNAVAILABLE = "unavailable"
        const val ROUND = "round"
        const val WILL_RESIGN = "will_resign"
        const val PLAYED = "played"
        const val SKIPPED = "skipped"
    }
}

/** What became of one beat: [result] `played` / `skipped` / `error`, with the [error]. */
data class Beat(val result: String, val error: String? = null) {
    val played: Boolean get() = result == RoundLiveActivity.PLAYED
}

/** How the pulse beats now: taps on the screen, the Live Activity's alerts or a notification off it. */
enum class PulseWay { TAPS, LIVE_ACTIVITY, NOTIFICATION }

/**
 * Which way the pulse beats ([PocketPulse]) and how often off the screen. On the screen the Taptic Engine taps the
 * heartbeat; off it a third-party app can't vibrate on its own: the round's Live Activity alerts while one runs (the
 * field build), else a notification (every other build, as before).
 */
object PocketPulseRules {
    /** The notification's least gap, as before the Live Activity. */
    const val NOTIFICATION_GAP_MILLIS = 4_000L

    /**
     * The Live Activity's gap by band: the closer, the more often, never more than a double alert every 4 s. iOS
     * budgets an activity's updates; the field log counts how many a long round gets through. Guesses.
     */
    const val LIVE_WARM_GAP_MILLIS = 8_000L
    const val LIVE_HOT_GAP_MILLIS = 6_000L
    const val LIVE_BURNING_GAP_MILLIS = 4_000L

    /** [liveActivityCanAlert]: [RoundLiveActivity.canAlert], a card that runs and has its file of silence. */
    fun way(onScreen: Boolean, liveActivityCanAlert: Boolean): PulseWay = when {
        onScreen -> PulseWay.TAPS
        liveActivityCanAlert -> PulseWay.LIVE_ACTIVITY
        else -> PulseWay.NOTIFICATION
    }

    /**
     * A beat off the screen is due: none went out yet ([sinceLastMillis] null) or the gap of [way] in [band] is over
     * since the last one. The last beat's time outlives a band change, so a band flipping at its edge never beats
     * faster than the gap.
     */
    fun isDue(sinceLastMillis: Long?, way: PulseWay, band: RadarBand): Boolean =
        sinceLastMillis == null || sinceLastMillis >= gapMillis(way, band)

    /** The least time between two beats off the screen by [way] in [band]; 0 on the screen (every heartbeat). */
    fun gapMillis(way: PulseWay, band: RadarBand): Long = when (way) {
        PulseWay.TAPS -> 0L

        PulseWay.NOTIFICATION -> NOTIFICATION_GAP_MILLIS

        PulseWay.LIVE_ACTIVITY -> when (band) {
            RadarBand.BURNING -> LIVE_BURNING_GAP_MILLIS
            RadarBand.HOT -> LIVE_HOT_GAP_MILLIS
            else -> LIVE_WARM_GAP_MILLIS
        }
    }
}
