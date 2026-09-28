package app.hovanki.client.ui.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable

@Composable
internal actual fun platformSafeDrawing(): WindowInsets = WindowInsets.safeDrawing
