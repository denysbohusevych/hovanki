package app.hovanki.client.ui.common

import androidx.compose.runtime.Composable

/**
 * The system back action (Android back button or gesture) runs [onBack] while [enabled], e.g. to close a panel or a
 * form instead of leaving the app. iOS has no system back action: screens there always show their own back button.
 */
@Composable
expect fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit)
