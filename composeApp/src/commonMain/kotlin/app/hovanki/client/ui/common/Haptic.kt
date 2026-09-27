package app.hovanki.client.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * What the phone says with a vibration (docs/design.md, «Вибрация и звук»). Played through Compose's haptic feedback:
 * the system's own effects, which follow the player's vibration settings. Only while the app is on screen; alerts with
 * the screen off belong to the background tracker.
 */
enum class Haptic(internal val type: HapticFeedbackType) {
    /** A main button pressed. */
    TAP(HapticFeedbackType.ContextClick),

    /** A countdown step, a light «soon». */
    TICK(HapticFeedbackType.SegmentTick),

    /** Something done: the zone shrank, the seekers went out, a catch counted. */
    SUCCESS(HapticFeedbackType.Confirm),

    /** Something wrong: a wrong code, an alert. */
    ERROR(HapticFeedbackType.Reject),

    /** Needs attention now: a claim against the hider, being caught. */
    HEAVY(HapticFeedbackType.LongPress),
}

/** Plays a [Haptic]. */
@Composable
fun rememberHaptics(): (Haptic) -> Unit {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { { haptic -> feedback.performHapticFeedback(haptic.type) } }
}
