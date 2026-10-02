package app.hovanki.client.lab

import app.hovanki.device.LiveCard
import app.hovanki.device.NoopLiveActivityHost
import app.hovanki.device.PocketTrace
import app.hovanki.device.RoundLiveActivity
import app.hovanki.device.lab.LabScreen
import app.hovanki.device.lab.NoopLabScreen
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.protocol.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The round's Live Activity's words in the player's language (the app's resources: `:clientCore` has none); English
 * until the app's are loaded.
 */
data class PocketTexts(
    val title: String = "Hovanki",
    val hider: String = "You hide",
    val seeker: String = "You seek",
    val none: String = "Radar: nothing",
    val warm: String = "Warm",
    val hot: String = "Hot!",
    val burning: String = "Burning!!",
) {
    /** The card: the role, and the pulse's band as a colour and a word. */
    fun card(role: Role, band: RadarBand): LiveCard = LiveCard(
        text = if (role == Role.HIDER) hider else seeker,
        band = band,
        detail = when (band) {
            RadarBand.NONE -> none
            RadarBand.WARM -> warm
            RadarBand.HOT -> hot
            RadarBand.BURNING -> burning
        },
    )
}

/** What the round is for the phone in the pocket: from the game's snapshot ([of]). */
data class PocketRound(val inRound: Boolean, val hasRadar: Boolean, val playing: Boolean, val role: Role) {
    companion object {
        fun of(snapshot: GameSnapshot): PocketRound = PocketRound(
            inRound = snapshot.phase == GamePhase.HIDING || snapshot.phase == GamePhase.SEEKING,
            hasRadar = snapshot.settings.features.hasRadar,
            playing = snapshot.me.role == Role.SEEKER || snapshot.me.status == PlayerStatus.ACTIVE,
            role = snapshot.me.role,
        )
    }
}

/**
 * When the field build's round turns the phone's pocket techniques on (docs/adr/0018-field-test-build.md, wave 4):
 * pure, so the decisions are tested without a phone.
 */
object PocketRules {
    /**
     * The round's pocket techniques (the Live Activity, the screen by the proximity sensor) are for a player of a
     * round with the radar who still plays (a seeker, a hider not caught), while the field log writes ([fieldOn]).
     */
    fun wanted(fieldOn: Boolean, round: PocketRound?): Boolean =
        fieldOn && round != null && round.inRound && round.hasRadar && round.playing

    /**
     * The screen by the proximity sensor (`mode.proximity_screen`, ADR 0017 §2.3, H3): on while [wanted] and the app is
     * [onScreen], so a phone put in the pocket unlocked goes dark while the app stays in the foreground. Off when the
     * app leaves the screen, except while the sensor says [near] and it [isOn] already: a screen the sensor itself
     * turned off may pause the app (Android), and letting go of the sensor then would light the screen in the pocket.
     * Never on a phone that [canTurnOff] not.
     */
    fun proximity(wanted: Boolean, onScreen: Boolean, near: Boolean?, isOn: Boolean, canTurnOff: Boolean): Boolean =
        canTurnOff && wanted && (onScreen || (isOn && near == true))
}

/**
 * The field build's round in the pocket (docs/adr/0018-field-test-build.md, wave 4): the round's Live Activity on an
 * iPhone ([live]: the role and the radar's band on the lock screen, and the hider's pulse by its alerts while the phone
 * is locked) and the screen turned off by the proximity sensor ([screen]: an unlocked phone in the pocket goes dark,
 * the app stays in the foreground and its radio unthrottled). Both only while [PocketRules.wanted] — the field log
 * writes ([FieldSession] tells it), the round has the radar and the player plays — and off at the round's end, when
 * the phone leaves the game; the sensor also when the app leaves the screen. A hint tells the player once a round
 * ([hint]). Every switch and what the phone said goes to [trace] (the field log's `mode` events), so the report can
 * answer «15 minutes in the pocket without a lock, the advertisement without gaps». Nothing of it runs outside the
 * field build: release and debug games are as before. Main thread.
 */
class FieldPocket(
    private val live: RoundLiveActivity = RoundLiveActivity(NoopLiveActivityHost()),
    private val screen: LabScreen = NoopLabScreen(),
    /** Where the app's words are loaded; null: English ([PocketTexts]'s defaults). */
    private val scope: CoroutineScope? = null,
    private val texts: suspend () -> PocketTexts = { PocketTexts() },
) {
    /** Where the attempts go: the field log while it writes. The Live Activity's and its pulse's too. */
    var trace: PocketTrace = PocketTrace.None
        set(value) {
            field = value
            live.trace = value
        }

    private val mutableHint = MutableStateFlow(false)

    /** «Put the phone in your pocket without locking it: the screen goes dark by itself»: once a round, dismissible. */
    val hint: StateFlow<Boolean> = mutableHint.asStateFlow()

    /** Whether the screen by the proximity sensor is on now. */
    var isProximityOn: Boolean = false
        private set

    private var fieldOn = false
    private var round: PocketRound? = null
    private var onScreen = true
    private var band = RadarBand.NONE
    private var near: Boolean? = null
    private var words = PocketTexts()
    private var wordsLoaded = false

    /** This round: the hint was shown, the sensor's absence or failure noted (not tried again). */
    private var hintShown = false
    private var proximityNoted = false

    /** The field log writes ([fieldOn]) and the game is at [round] (null: no game). */
    fun update(fieldOn: Boolean, round: PocketRound?) {
        this.fieldOn = fieldOn
        this.round = round
        apply(if (round?.inRound == true) "round" else "round_over")
    }

    /** The app came to the screen or left it. */
    fun onScreenChanged(onScreen: Boolean) {
        if (this.onScreen == onScreen) return
        this.onScreen = onScreen
        if (isWanted()) trace.mode(PROXIMITY_SCREEN, "app", if (onScreen) "on_screen" else "off_screen")
        apply(if (onScreen) "on_screen" else "off_screen")
    }

    /** The pulse's band changed: the card shows it. */
    fun onPulse(band: RadarBand) {
        this.band = band
        apply("pulse")
    }

    /** The proximity sensor: [near] true, false, or null (unknown). */
    fun onProximity(near: Boolean?) {
        if (near == this.near) return
        this.near = near
        if (isProximityOn) trace.mode(PROXIMITY_SCREEN, if (near == true) "near" else "far")
        apply("sensor")
    }

    /**
     * The app's life as the lab's probes say it (`will_resign`, `did_enter_background`, `protected_data_off`,
     * `screen_off`…): written while the round wants the sensor, with what it means for a phone in the pocket — the
     * sensor turned the screen off (`screen_dark`), or the phone locked anyway (`locked`).
     */
    fun onLifecycle(event: String) {
        if (!isWanted() || event !in APP_EVENTS) return
        trace.mode(PROXIMITY_SCREEN, "app", event)
        if (!isProximityOn) return
        when {
            event == SCREEN_OFF && near == true -> trace.mode(PROXIMITY_SCREEN, "screen_dark", "sensor")
            event == SCREEN_OFF -> trace.mode(PROXIMITY_SCREEN, "locked", "screen_off_far")
            event == PROTECTED_DATA_OFF -> trace.mode(PROXIMITY_SCREEN, "locked", "passcode")
            event == DID_ENTER_BACKGROUND -> trace.mode(PROXIMITY_SCREEN, "locked", if (near == true) "near" else "far")
        }
    }

    /** «Got it» on the hint: not again this round. */
    fun dismissHint() {
        mutableHint.value = false
    }

    /** The phone left the game ([reason]): everything off. */
    fun stop(reason: String) {
        fieldOn = false
        round = null
        apply(reason)
    }

    private fun isWanted(): Boolean = PocketRules.wanted(fieldOn, round)

    private fun apply(reason: String) {
        val wanted = isWanted()
        val round = round
        if (wanted && round != null) {
            loadWords()
            live.want(words.title, words.card(round.role, band), onScreen)
        } else {
            live.end(reason)
        }
        applyProximity(wanted, reason)
        if (!wanted) {
            // The next round shows the hint again and tries the sensor again.
            hintShown = false
            proximityNoted = false
            mutableHint.value = false
        }
    }

    private fun applyProximity(wanted: Boolean, reason: String) {
        if (wanted && !screen.canTurnOffByProximity && !proximityNoted) {
            proximityNoted = true
            trace.mode(PROXIMITY_SCREEN, UNAVAILABLE, "no proximity screen off on this phone")
        }
        val target = !proximityNoted || isProximityOn
        val on = target && PocketRules.proximity(wanted, onScreen, near, isProximityOn, screen.canTurnOffByProximity)
        if (on == isProximityOn) return
        screen.setOffByProximity(on)
        if (on && screen.isOffByProximity() == false) {
            // iOS leaves the sensor off on a device without one: noted once, not tried again this round.
            screen.setOffByProximity(false)
            proximityNoted = true
            trace.mode(PROXIMITY_SCREEN, "failed", "the platform left the sensor off")
            return
        }
        isProximityOn = on
        trace.mode(PROXIMITY_SCREEN, if (on) "on" else "off", reason)
        if (on && !hintShown) {
            hintShown = true
            mutableHint.value = true
        }
    }

    /** The app's words for the card, once: the card is sent again with them. */
    private fun loadWords() {
        if (wordsLoaded) return
        wordsLoaded = true
        val scope = scope ?: return
        scope.launch {
            val loaded = try {
                texts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            words = loaded
            val round = round
            if (isWanted() && round != null) live.show(words.card(round.role, band))
        }
    }

    companion object {
        /** The screen by the proximity sensor (ADR 0017 §2.3): the lab's `PhoneSetup.screenOff`. */
        const val PROXIMITY_SCREEN = "mode.proximity_screen"
        const val UNAVAILABLE = "unavailable"
        private const val SCREEN_OFF = "screen_off"
        private const val PROTECTED_DATA_OFF = "protected_data_off"
        private const val DID_ENTER_BACKGROUND = "did_enter_background"

        /** The lab probes' life events that say something about the phone in the pocket. */
        val APP_EVENTS = setOf(
            "will_resign",
            DID_ENTER_BACKGROUND,
            "will_enter_foreground",
            "did_become_active",
            PROTECTED_DATA_OFF,
            "protected_data_on",
            SCREEN_OFF,
            "screen_on",
            "user_present",
        )
    }
}
