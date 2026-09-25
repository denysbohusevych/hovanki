package app.hovanki.client.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class AndroidLocationProvider(private val context: Context) : LocationProvider {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // Callers check hasPermission() first; if it is revoked meanwhile, the SecurityException ends the flow.
    @SuppressLint("MissingPermission")
    override fun locationUpdates(intervalMillis: Long): Flow<LocationSample> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis / 2)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { trySend(it.toSample()) }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { close(it) }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    private fun Location.toSample(): LocationSample {
        // The fix time in device-clock terms, from the monotonic clock: Location.time may come from GPS time,
        // which would not match the device clock that ServerClock corrects.
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000
        return LocationSample(
            point = GeoPoint(latitude, longitude),
            accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else UNKNOWN_ACCURACY_METERS,
            timestampMillis = System.currentTimeMillis() - ageMillis.coerceAtLeast(0),
            isMock = isMocked(),
        )
    }

    @Suppress("DEPRECATION")
    private fun Location.isMocked(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else isFromMockProvider

    private companion object {
        /** Worse than any threshold: the server ignores such fixes in rule checks. */
        const val UNKNOWN_ACCURACY_METERS = 9999.0
    }
}
