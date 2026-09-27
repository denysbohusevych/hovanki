package app.hovanki.client.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/** Motion tokens (docs/design.md, «Токены движения»). */
object Motion {
    /** A button going down under the finger. */
    const val PRESS_MILLIS = 80

    /** Color changes, a badge appearing. */
    const val FAST_MILLIS = 150

    /** A change of state, collapsing. */
    const val BASE_MILLIS = 250

    /** Full-screen layers and sheets. */
    const val SCREEN_MILLIS = 320

    fun <T> fast(): FiniteAnimationSpec<T> = tween(FAST_MILLIS, easing = FastOutSlowInEasing)

    fun <T> base(): FiniteAnimationSpec<T> = tween(BASE_MILLIS, easing = FastOutSlowInEasing)

    /** Something «pops»: an award, the zone done shrinking, a new player. */
    fun <T> pop(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)

    /** A pressed element coming back up. */
    fun <T> release(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)
}
