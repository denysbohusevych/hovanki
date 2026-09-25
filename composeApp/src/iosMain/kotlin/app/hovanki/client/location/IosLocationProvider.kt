@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.client.location

import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLDistanceFilterNone
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.darwin.NSObject

class IosLocationProvider : LocationProvider {
    override fun hasPermission(): Boolean = isLocationAuthorized(CLLocationManager().authorizationStatus)

    // CLLocationManager delivers delegate callbacks on the run loop of the thread that created it: use the main one.
    override fun locationUpdates(intervalMillis: Long): Flow<LocationSample> = callbackFlow {
        val updates = LocationUpdates(
            onFix = { trySend(it) },
            onAccessLost = { close(IllegalStateException("Location access was revoked")) },
        )
        updates.start()
        // Also keeps `updates` (the manager's delegate, which the manager holds only weakly) alive while collected.
        awaitClose { updates.stop() }
    }.flowOn(Dispatchers.Main)
}

/** Delegate that owns its CLLocationManager. iOS decides the update rate itself, so there's no interval to set. */
private class LocationUpdates(private val onFix: (LocationSample) -> Unit, private val onAccessLost: () -> Unit) :
    NSObject(),
    CLLocationManagerDelegateProtocol {
    private val manager = CLLocationManager()

    fun start() {
        manager.delegate = this
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        // Keep tracking while the phone is locked during a round (Info.plist: UIBackgroundModes = location).
        manager.allowsBackgroundLocationUpdates = true
        manager.pausesLocationUpdatesAutomatically = false
        manager.showsBackgroundLocationIndicator = true
        manager.startUpdatingLocation()
    }

    fun stop() {
        manager.stopUpdatingLocation()
        manager.delegate = null
    }

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        didUpdateLocations.filterIsInstance<CLLocation>().forEach { onFix(it.toSample()) }
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        // Most errors are transient (no fix yet); only a lost permission ends the updates.
        if (!isLocationAuthorized(manager.authorizationStatus)) onAccessLost()
    }
}

private fun CLLocation.toSample(): LocationSample {
    val point = coordinate.useContents { GeoPoint(latitude, longitude) }
    return LocationSample(
        point = point,
        // Negative accuracy means the coordinate is invalid.
        accuracyMeters = if (horizontalAccuracy >= 0) horizontalAccuracy else UNKNOWN_ACCURACY_METERS,
        timestampMillis = (timestamp.timeIntervalSince1970 * 1000).toLong(),
        isMock = sourceInformation?.isSimulatedBySoftware == true,
    )
}

internal fun isLocationAuthorized(status: CLAuthorizationStatus): Boolean =
    status == kCLAuthorizationStatusAuthorizedWhenInUse || status == kCLAuthorizationStatusAuthorizedAlways

/** Worse than any threshold: the server ignores such fixes in rule checks. */
private const val UNKNOWN_ACCURACY_METERS = 9999.0
