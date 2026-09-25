package app.hovanki.client.tracking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

/** Runs [LocationTrackingService] while a round is in progress. */
class AndroidBackgroundTracker(private val context: Context) : BackgroundTracker {
    override fun start() {
        // A location foreground service without the location permission is refused by Android 14+.
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        try {
            ContextCompat.startForegroundService(context, serviceIntent())
        } catch (e: IllegalStateException) {
            // Android 12+ refuses to start foreground services while the app is in the background
            // (ForegroundServiceStartNotAllowedException). The round goes on; updates may pause until the app is opened.
            Log.w(TAG, "Could not start location tracking", e)
        }
    }

    override fun stop() {
        context.stopService(serviceIntent())
    }

    private fun serviceIntent() = Intent(context, LocationTrackingService::class.java)

    private companion object {
        const val TAG = "BackgroundTracker"
    }
}
