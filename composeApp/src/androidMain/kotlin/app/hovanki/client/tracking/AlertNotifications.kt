package app.hovanki.client.tracking

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.alert_channel
import app.hovanki.client.resources.alert_claim_text
import app.hovanki.client.resources.alert_in_building_text
import app.hovanki.client.resources.alert_in_building_title
import app.hovanki.client.resources.alert_out_of_zone_text
import app.hovanki.client.resources.alert_out_of_zone_title
import app.hovanki.client.resources.hider_claim_title
import org.jetbrains.compose.resources.getString

/**
 * The hider's alerts as notifications that vibrate without a sound (docs/design.md, «Вибрация и звук»: a sound
 * would give the hiding place away), while the app is not on screen: the phone is in the pocket.
 */
internal class AlertNotifications(private val context: Context) {
    /** On screen the round's own UI alerts (vignette, vibration through Compose). */
    fun isAppOnScreen(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    suspend fun show(alert: HiderAlert) {
        if (!canNotify()) return
        val (title, text) = when (alert.kind) {
            AlertKind.OUT_OF_ZONE -> getString(Res.string.alert_out_of_zone_title) to
                getString(Res.string.alert_out_of_zone_text)

            AlertKind.IN_BUILDING -> getString(Res.string.alert_in_building_title) to
                getString(Res.string.alert_in_building_text)

            AlertKind.CATCH_CLAIM -> getString(Res.string.hider_claim_title, alert.seekerName.orEmpty()) to
                getString(Res.string.alert_claim_text)
        }
        val notification = NotificationCompat.Builder(context, channel())
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId(alert.kind), notification)
        } catch (e: SecurityException) {
            // The notification permission was revoked in between: the round goes on without it.
        }
    }

    fun cancel(kind: AlertKind) {
        NotificationManagerCompat.from(context).cancel(notificationId(kind))
    }

    private fun canNotify(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    /** High importance to vibrate and show over the lock screen; no sound, a strong vibration pattern. */
    private suspend fun channel(): String {
        val name = getString(Res.string.alert_channel)
        val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
        return CHANNEL_ID
    }

    private fun openAppIntent(): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun notificationId(kind: AlertKind): Int = FIRST_NOTIFICATION_ID + kind.ordinal

    private companion object {
        const val CHANNEL_ID = "game_alerts"

        /** After the tracking service's notification (1). */
        const val FIRST_NOTIFICATION_ID = 10
        val VIBRATION_PATTERN = longArrayOf(0, 500, 150, 500, 150, 500)
    }
}
