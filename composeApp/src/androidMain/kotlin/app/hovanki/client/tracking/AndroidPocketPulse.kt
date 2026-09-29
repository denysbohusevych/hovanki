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
import kotlin.math.roundToInt

/**
 * The pulse (docs/adr/0012-nearby-radar.md, «Пульс») on Android: a heartbeat on the vibration motor, a soft beat and a
 * strong one ([HeartbeatRules]), at the band's pace and strength, repeated until the band is [RadarBand.NONE]. A motor
 * of one strength plays the soft beat shorter instead. Works with the screen off, since the round's foreground service
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
        val beat = HeartbeatRules.beat(band)
        if (beat == null) {
            vibrator.cancel()
            return
        }
        // Soft «lub», strong «dub», then quiet until the period is over; the waveform repeats from its start.
        val effect = if (vibrator.hasAmplitudeControl()) {
            val timings = longArrayOf(0, beat.softMillis, beat.gapMillis, beat.strongMillis, beat.restMillis)
            val amplitudes = intArrayOf(0, amplitude(beat.softAmplitude), 0, amplitude(beat.strongAmplitude), 0)
            VibrationEffect.createWaveform(timings, amplitudes, 0)
        } else {
            val soft = HeartbeatRules.SOFT_MILLIS_WITHOUT_AMPLITUDE
            val gap = beat.gapMillis + beat.softMillis - soft
            VibrationEffect.createWaveform(longArrayOf(0, soft, gap, beat.strongMillis, beat.restMillis), 0)
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

    /** 0..1 as the motor's 1..255. */
    private fun amplitude(share: Double): Int = (share * MAX_AMPLITUDE).roundToInt().coerceIn(1, MAX_AMPLITUDE)

    private companion object {
        const val MAX_AMPLITUDE = 255
    }
}
