package app.hovanki.client.ui.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * What system bars, cutouts and the keyboard leave free: `WindowInsets.safeDrawing`, and on iOS never less than the
 * window's own safe area. There Compose can lose the safe area after the app comes back from the background (the
 * greeting under the clock, the tab bar on the home indicator: docs/design.md, «Отступы экрана»). Every screen keeps
 * clear of the system bars through this, never through `safeDrawing` directly.
 */
val WindowInsets.Companion.appSafeDrawing: WindowInsets
    @Composable get() = platformSafeDrawing()

/** Padding by [appSafeDrawing]: in place of `safeDrawingPadding()`. */
fun Modifier.appSafeDrawingPadding(): Modifier = composed { windowInsetsPadding(WindowInsets.appSafeDrawing) }

@Composable
internal expect fun platformSafeDrawing(): WindowInsets
