package app.hovanki.client.radio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.hovanki.radar.ProximityRadio
import org.koin.compose.koinInject
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways

/**
 * iOS asks for Bluetooth by itself the first time the app touches CoreBluetooth (`IosProximityRadio.refresh` in
 * `:radar`); the answer shows in the radio's state, and here as whether the app is allowed right now.
 */
@Composable
actual fun rememberBluetoothPermissionRequester(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    val radio = koinInject<ProximityRadio>()
    return remember(radio) {
        {
            radio.refresh()
            currentOnResult(CBManager.authorization == CBManagerAuthorizationAllowedAlways)
        }
    }
}
