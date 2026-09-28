package app.hovanki.client.tracking

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.rules.HeartbeatRules

/**
 * The pulse (docs/adr/0010-nearby-radar.md, «Пульс») on Android: a heartbeat on the vibration motor at the band's
 * pace, repeated until the band is [RadarBand.NONE]. Works with the screen off, since the round's foreground service
 * keeps the process alive. As an alarm, so a silent ringer doesn't mute it: the hider chose to play.
 */
class AndroidPocketPulse(context: Context) : PocketPulse {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    override fun set(band: RadarBand) {
        val vibrator = vibrator?.takeIf { it.hasVibrator() } ?: return
        val period = HeartbeatRules.periodMillis(band)
        if (period == null) {
            vibrator.cancel()
            return
        }
        // Lub-dub, then rest until the period is over.
        val rest = (period - BEAT_MILLIS - GAP_MILLIS - SECOND_BEAT_MILLIS).coerceAtLeast(MIN_REST_MILLIS)
        val timings = longArrayOf(0, BEAT_MILLIS, GAP_MILLIS, SECOND_BEAT_MILLIS, rest)
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings, intArrayOf(0, STRONG, 0, SOFT, 0), 0)
        } else {
            VibrationEffect.createWaveform(timings, 0)
        }
        vibrator.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        }
    }

    private companion object {
        const val BEAT_MILLIS = 60L
        const val GAP_MILLIS = 90L
        const val SECOND_BEAT_MILLIS = 50L
        const val MIN_REST_MILLIS = 120L
        const val STRONG = 255
        const val SOFT = 160
    }
}
