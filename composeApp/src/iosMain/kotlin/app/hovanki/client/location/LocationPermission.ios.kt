package app.hovanki.client.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.darwin.NSObject

@Composable
actual fun rememberLocationPermissionRequester(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    val requester = remember { AuthorizationRequester() }
    DisposableEffect(requester) {
        onDispose { requester.cancel() }
    }
    return remember(requester) { { requester.request { granted -> currentOnResult(granted) } } }
}

/**
 * iOS shows the permission dialog only once; afterwards the status is final until the user changes it in Settings.
 * The answer arrives asynchronously through the delegate, which is why this is an object with state.
 */
private class AuthorizationRequester :
    NSObject(),
    CLLocationManagerDelegateProtocol {
    private val manager = CLLocationManager()
    private var pending: ((Boolean) -> Unit)? = null

    fun request(onResult: (Boolean) -> Unit) {
        val status = manager.authorizationStatus
        if (status != kCLAuthorizationStatusNotDetermined) {
            onResult(isLocationAuthorized(status))
            return
        }
        pending = onResult
        manager.delegate = this
        manager.requestWhenInUseAuthorization()
    }

    fun cancel() {
        pending = null
        manager.delegate = null
    }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        val status = manager.authorizationStatus
        // Also called right after the delegate is set, before the user has answered.
        if (status == kCLAuthorizationStatusNotDetermined) return
        pending?.invoke(isLocationAuthorized(status))
        pending = null
    }
}
