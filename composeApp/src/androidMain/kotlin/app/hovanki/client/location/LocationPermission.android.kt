package app.hovanki.client.location

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

@Composable
actual fun rememberLocationPermissionRequester(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        currentOnResult(results[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }
    return remember(launcher) { { launcher.launch(REQUESTED_PERMISSIONS) } }
}

// Android 12+ requires asking for coarse together with fine location. Notifications (13+) are for
// the ongoing notification of the tracking service; the game works without them.
private val REQUESTED_PERMISSIONS: Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()
