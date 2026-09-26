package app.hovanki.client.tracking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import app.hovanki.client.R

/**
 * Foreground service of type "location" that keeps the process in the foreground while a round runs, so Android
 * keeps delivering location updates with the screen off. It does no work itself: GameSessionManager collects
 * and sends the locations.
 */
class LocationTrackingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
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
