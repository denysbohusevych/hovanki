package app.hovanki.client.radio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.hovanki.radar.ProximityRadio
import app.hovanki.radar.RADAR_PERMISSIONS
import org.koin.compose.koinInject

@Composable
actual fun rememberBluetoothPermissionRequester(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    val radio = koinInject<ProximityRadio>()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        radio.refresh()
        currentOnResult(results.values.all { it })
    }
    return remember(launcher) {
        {
            // Before Android 12 Bluetooth LE needs only the location permission, which the game has anyway.
            if (RADAR_PERMISSIONS.isEmpty()) currentOnResult(true) else launcher.launch(RADAR_PERMISSIONS)
        }
    }
}
