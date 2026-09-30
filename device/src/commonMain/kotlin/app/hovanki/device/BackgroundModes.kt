package app.hovanki.device

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The ids of the ways to keep a locked phone doing something (docs/adr/0017-radar-techniques-and-big-run.md, §2.3,
 * the rows `mode.*`), as the radio lab's `PhoneSetup.techniques` names them. `mode.proximity_screen` is not here: it
 * is the lab's screen switch (`LabScreen`), not a mode of its own.
 */
object ModeIds {
    /** A background audio session playing silence, so the app keeps running and Core Haptics may survive the lock. */
    const val AUDIO = "mode.audio"

    /** A local notification every few seconds: does the lit lock screen let the radio hear more? */
    const val NOTIFICATION_WAKE = "mode.notification_wake"

    /** The round's Live Activity: Nearby Interaction keeps ranging in the background only with one. */
    const val LIVE_ACTIVITY = "mode.live_activity"

    val ALL: List<String> = listOf(AUDIO, NOTIFICATION_WAKE, LIVE_ACTIVITY)
}

/** What [BackgroundModes.set] did: [ok] false with the [error] when the mode can't be switched here or failed. */
data class ModeResult(val ok: Boolean, val error: String? = null)

/**
 * What a mode reports while on, for the lab's log: `audio` interruptions (`interruption_began` /
 * `interruption_ended` with the reason or the options), `route_change` (the reason), `media_services_reset`,
 * `notification_sent` («wake N»), `live_activity_started` / `_updated` / `_ended` / `_refused` (the error).
 */
data class ModeEvent(val mode: String, val event: String, val reason: String? = null)

/**
 * The phone's background modes (docs/radar-run.md, §5.1 and §5.3), switched by the radio lab only: the game never
 * turns one on. Each is a technique of the big run with a kill criterion (ADR 0017, §2.3); the lab's log says what
 * each did and when the system took it away. Called on the main thread.
 */
interface BackgroundModes {
    /** The [ModeIds] this phone can switch on. */
    val available: Set<String>

    /** Turns [id] on or off; switching a mode to the state it is in does nothing and succeeds. */
    fun set(id: String, on: Boolean): ModeResult

    /** The modes' events while collected (a hot flow: nothing is kept for a late collector). */
    fun events(): Flow<ModeEvent>

    /** Every mode off: the lab's run or test is over. */
    fun stopAll()
}

/** A phone without background modes: Android (its foreground service keeps the app alive anyway) and the JVM bots. */
class NoopBackgroundModes : BackgroundModes {
    override val available: Set<String> = emptySet()

    override fun set(id: String, on: Boolean): ModeResult =
        if (on) ModeResult(false, "no modes here") else ModeResult(true)

    override fun events(): Flow<ModeEvent> = emptyFlow()

    override fun stopAll() = Unit
}
