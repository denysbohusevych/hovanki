package app.hovanki.client.radio

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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

/** Android 12+: scanning («never for location»: the manifest says so) and advertising; connecting for the name. */
internal val RADAR_PERMISSIONS: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
    )
} else {
    emptyArray()
}
