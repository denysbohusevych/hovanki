package app.hovanki.client.tracking

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.hovanki.client.R

/**
 * Foreground service of type "location" (and "connectedDevice" once the Bluetooth permissions are granted, for the
 * radar's scanning and the pulse with the screen off, docs/adr/0010-nearby-radar.md) that keeps the process in the
 * foreground while a round runs, so Android keeps delivering location updates with the screen off. It does no work
 * itself: GameSessionManager collects and sends the locations.
 */
class LocationTrackingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or connectedDeviceType(),
            )
        } catch (e: RuntimeException) {
            // E.g. the location permission was revoked in between (SecurityException).
            Log.w(TAG, "Could not enter the foreground", e)
            stopSelf()
        }
        // If Android kills the process, the app resumes the saved game when it is opened again; the service alone
        // could not send anything.
        return START_NOT_STICKY
    }

    /**
     * Android 14+ wants the type declared for Bluetooth in the background, and refuses it without one of its
     * permissions granted; older versions have no such type.
     */
    private fun connectedDeviceType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return 0
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
    }

    private fun buildNotification() = NotificationCompat.Builder(this, createChannel())
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle(getString(R.string.tracking_notification_title))
        .setContentText(getString(R.string.tracking_notification_text))
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(openAppIntent())
        .build()

    private fun createChannel(): String {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tracking_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
        return CHANNEL_ID
    }

    private fun openAppIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val TAG = "LocationTracking"
        const val CHANNEL_ID = "game_in_progress"
        const val NOTIFICATION_ID = 1
    }
}
