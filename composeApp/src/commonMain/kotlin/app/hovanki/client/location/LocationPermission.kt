package app.hovanki.client.location

import androidx.compose.runtime.Composable

/**
 * Returns a function that asks for the location permission (shows the system dialog only when needed)
 * and reports whether precise location is granted. Must be remembered in composition because
 * Android permission requests are bound to the Activity.
 */
@Composable
expect fun rememberLocationPermissionRequester(onResult: (granted: Boolean) -> Unit): () -> Unit
