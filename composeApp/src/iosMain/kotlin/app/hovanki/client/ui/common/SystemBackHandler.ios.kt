package app.hovanki.client.ui.common

import androidx.compose.runtime.Composable

/** No system back action on iOS; screens show their own back button. */
@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
