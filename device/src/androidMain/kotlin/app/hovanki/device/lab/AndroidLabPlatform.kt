package app.hovanki.device.lab

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.roundToInt

/**
 * The screen off by the proximity sensor on Android (`PROXIMITY_SCREEN_OFF_WAKE_LOCK`), to compare with the iPhone's
 * (docs/radio-lab.md §5). Debug builds only.
 */
class AndroidLabScreen(context: Context) : LabScreen {
    private val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var lock: PowerManager.WakeLock? = null

    override val canTurnOffByProximity: Boolean =
        power?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true

    override fun setOffByProximity(on: Boolean) {
        if (on) {
            if (lock != null || !canTurnOffByProximity) return
            lock = power?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "hovanki:lab")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } else {
            lock?.takeIf { it.isHeld }?.release()
            lock = null
        }
    }

    override fun isOffByProximity(): Boolean = lock?.isHeld == true
}

/** The motor, as the game's pulse uses it (the alarm's usage: a silent ringer doesn't mute it). */
class AndroidLabHaptics(context: Context) : LabHaptics {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    override val kinds: List<HapticKind> =
        if (vibrator?.hasVibrator() == true) listOf(HapticKind.VIBRATOR) else emptyList()

    override suspend fun play(kind: HapticKind, strength: Double): HapticResult {
        val vibrator = vibrator?.takeIf { it.hasVibrator() } ?: return HapticResult("skipped", "no vibrator")
        if (kind != HapticKind.VIBRATOR) return HapticResult("skipped", "not on Android")
        val amplitude = (strength * MAX_AMPLITUDE).roundToInt().coerceIn(1, MAX_AMPLITUDE)
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createOneShot(BEAT_MILLIS, amplitude)
        } else {
            VibrationEffect.createOneShot(BEAT_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
            HapticResult("played")
        } catch (e: RuntimeException) {
            HapticResult("error", e.message ?: e::class.simpleName)
        }
    }

    private companion object {
        const val MAX_AMPLITUDE = 255
        const val BEAT_MILLIS = 80L
    }
}
