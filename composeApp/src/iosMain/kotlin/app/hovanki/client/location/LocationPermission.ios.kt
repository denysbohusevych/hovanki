@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.client.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberLocationPermissionRequester(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    val requester = remember { AuthorizationRequester() }
    DisposableEffect(requester) {
        onDispose { requester.cancel() }
    }
    return remember(requester) { { requester.request { granted -> currentOnResult(granted) } } }
}

@Composable
actual fun rememberLocationConsentNeeded(): () -> Boolean = remember {
    { CLLocationManager().authorizationStatus == kCLAuthorizationStatusNotDetermined }
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
        // Notifications first: the hider's alerts reach the pocket as notifications (IosBackgroundTracker). Asked
        // right before location, while the player is deciding on permissions anyway; without a sound option, so the
        // alerts never make a sound. The answer does not matter for the game.
        val notifications = UNUserNotificationCenter.currentNotificationCenter()
        notifications.requestAuthorizationWithOptions(UNAuthorizationOptionAlert) { _, _ ->
            dispatch_async(dispatch_get_main_queue()) { requestLocation() }
        }
    }

    private fun requestLocation() {
        // Cancelled meanwhile: the screen that asked is gone.
        if (pending == null) return
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
