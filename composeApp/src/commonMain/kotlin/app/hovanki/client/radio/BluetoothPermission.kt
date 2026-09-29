package app.hovanki.client.radio

import androidx.compose.runtime.Composable

/**
 * Returns a function that asks for what the radar needs (Android 12+: the Bluetooth scan and advertise permissions;
 * iOS: the system asks by itself on the first use) and reports whether it was granted. Remembered in composition:
 * Android permission requests are bound to the Activity.
 */
@Composable
expect fun rememberBluetoothPermissionRequester(onResult: (granted: Boolean) -> Unit): () -> Unit
