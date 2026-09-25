package app.hovanki.client.location

import app.hovanki.shared.protocol.LocationSample
import kotlinx.coroutines.flow.Flow

/** Device GPS. Android: FusedLocationProviderClient, iOS: CLLocationManager. */
interface LocationProvider {
    /** Precise location permission is granted (coarse location is useless for the game). */
    fun hasPermission(): Boolean

    /**
     * Fixes roughly every [intervalMillis] while collected; collection must stop to save the battery.
     * Timestamps are device time (convert with [app.hovanki.client.session.ServerClock]), `isMock` is filled.
     * The flow fails when location access is lost.
     */
    fun locationUpdates(intervalMillis: Long): Flow<LocationSample>
}
