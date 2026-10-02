@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.hovanki.device.lab

import app.hovanki.device.IosIdleTimer
import app.hovanki.device.LiveActivityHost
import app.hovanki.device.NoopLiveActivityHost
import app.hovanki.device.SILENT_SOUND
import app.hovanki.device.requestLabNotifications
import app.hovanki.device.writeSilentSound
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.AVFAudio.AVAudioSession
import platform.CoreHaptics.CHHapticEngine
import platform.CoreHaptics.CHHapticEvent
import platform.CoreHaptics.CHHapticEventParameter
import platform.CoreHaptics.CHHapticEventParameterIDHapticIntensity
import platform.CoreHaptics.CHHapticEventParameterIDHapticSharpness
import platform.CoreHaptics.CHHapticEventTypeHapticTransient
import platform.CoreHaptics.CHHapticPattern
import platform.Foundation.NSError
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UIKit.UIDevice
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * The screen turned off by the proximity sensor while the app stays active (docs/radio-lab.md, H3): the sensor on, the
 * idle timer off, so the phone doesn't lock by itself. Debug builds only.
 */
class IosLabScreen : LabScreen {
    override val canTurnOffByProximity: Boolean = true

    override fun setOffByProximity(on: Boolean) {
        UIDevice.currentDevice.proximityMonitoringEnabled = on
        // Shared with the catch code's bright screen: letting go here never re-enables auto-lock under it.
        IosIdleTimer.hold(this, on)
    }

    /** iOS leaves the sensor off on a device that has none (an iPad): read back after [setOffByProximity]. */
    override fun isOffByProximity(): Boolean = UIDevice.currentDevice.proximityMonitoringEnabled
}

/**
 * Every way an iPhone app may vibrate, for the vibration test (docs/radio-lab.md, H2): Core Haptics (an engine the
 * system stops, e.g. when the audio session is interrupted on the lock; it says why, written by name through
 * [HapticStopReason]), Core Haptics on the app's audio session (`pulse.core_haptics.audio`, docs/radar-run.md §5.1:
 * with `mode.audio` playing silence, the lock may not interrupt it), the impact generator the game's pulse uses on
 * screen, and two notifications: with a silent sound (a file of silence the lab writes into `Library/Sounds`: a
 * notification with a sound vibrates as the ringer's settings say) and without one. Debug builds only; written
 * without an iOS build.
 */
class IosLabHaptics(private val liveActivity: LiveActivityHost = NoopLiveActivityHost()) : LabHaptics {
    override val kinds: List<HapticKind> = listOf(
        // Only what a locked iPhone feels, the best first (docs/radio-lab.md §12, 2026-09-30): two Live Activity
        // alerts 300 ms apart read clearly in the pocket, one alert is felt, a silent-sound notification is the
        // fallback when no activity runs; Core Haptics, impact, the soundless notification and the silent ringtone
        // never reached the pocket (their `play` stays for the screen). The lab's pulse takes the first that plays.
        HapticKind.LIVE_ACTIVITY_ALERT_DOUBLE,
        HapticKind.LIVE_ACTIVITY_ALERT,
        HapticKind.NOTIFY_SILENT_SOUND,
    )

    private val events = MutableSharedFlow<Pair<HapticKind, String>>(extraBufferCapacity = 16)

    /** The running engine of each Core Haptics kind: the plain one and the one on the app's audio session. */
    private val engines = mutableMapOf<HapticKind, CHHapticEngine>()
    private val impact by lazy { UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy) }
    private var notifications = 0

    override fun engineEvents(): Flow<Pair<HapticKind, String>> = events.asSharedFlow()

    override suspend fun prepare() {
        writeSilentSound()
        requestLabNotifications()
    }

    override suspend fun play(kind: HapticKind, strength: Double): HapticResult = when (kind) {
        HapticKind.CORE_HAPTICS, HapticKind.CORE_HAPTICS_AUDIO -> playCoreHaptics(kind, strength)

        HapticKind.IMPACT -> {
            impact.prepare()
            impact.impactOccurredWithIntensity(strength)
            val active =
                UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive
            // UIKit says nothing when it drops a tap off screen: the log's app state tells.
            HapticResult("played", if (active) null else "not active: UIKit may drop it")
        }

        HapticKind.NOTIFY_SILENT_SOUND ->
            notify(kind, "vibration test: silent sound", UNNotificationSound.soundNamed(SILENT_SOUND))

        HapticKind.NOTIFY_NO_SOUND -> notify(kind, "vibration test: no sound", null)

        HapticKind.NOTIFY_SILENT_RINGTONE ->
            notify(kind, "vibration test: silent ringtone", UNNotificationSound.ringtoneSoundNamed(SILENT_SOUND))

        HapticKind.VIBRATOR -> HapticResult("skipped", "not on iOS")

        HapticKind.LIVE_ACTIVITY_ALERT -> liveActivityAlert(times = 1)

        HapticKind.LIVE_ACTIVITY_ALERT_DOUBLE -> liveActivityAlert(times = 2)
    }

    /** [times] alerts on the running Live Activity, [ALERT_GAP_MILLIS] apart: one beat, or a longer one. */
    private suspend fun liveActivityAlert(times: Int): HapticResult {
        if (!liveActivity.isAvailable) return HapticResult("skipped", "no live activity host: add HovankiLive in Xcode")
        repeat(times) { index ->
            if (index > 0) delay(ALERT_GAP_MILLIS)
            if (!liveActivity.alert("Hovanki lab", "vibration test: live activity", silent = true)) {
                return HapticResult("skipped", "no live activity running: switch mode.live_activity on and lock")
            }
        }
        return HapticResult("played")
    }

    private fun playCoreHaptics(kind: HapticKind, strength: Double): HapticResult {
        memScoped {
            return playCoreHaptics(kind, strength, alloc<ObjCObjectVar<NSError?>>())
        }
    }

    private fun playCoreHaptics(kind: HapticKind, strength: Double, error: ObjCObjectVar<NSError?>): HapticResult {
        val running = engines[kind] ?: run {
            val made = newEngine(kind, error)
                ?: return HapticResult("error", error.value?.localizedDescription ?: "no engine")
            // The same handlers for both engines: the kind in the event tells them apart.
            made.stoppedHandler = { reason ->
                engines.remove(kind)
                events.tryEmit(kind to "engine_stopped: ${HapticStopReason.describe(reason)}")
            }
            made.resetHandler = {
                engines.remove(kind)
                events.tryEmit(kind to "engine_reset")
            }
            if (!made.startAndReturnError(error.ptr)) {
                return HapticResult("error", error.value?.localizedDescription ?: "start failed")
            }
            engines[kind] = made
            made
        }
        val event = CHHapticEvent(
            eventType = CHHapticEventTypeHapticTransient,
            parameters = listOf(
                CHHapticEventParameter(
                    parameterID = CHHapticEventParameterIDHapticIntensity,
                    value = strength.toFloat(),
                ),
                CHHapticEventParameter(parameterID = CHHapticEventParameterIDHapticSharpness, value = SHARPNESS),
            ),
            relativeTime = 0.0,
        )
        val pattern = CHHapticPattern(events = listOf(event), parameters = emptyList<Any>(), error = error.ptr)
            ?: return HapticResult("error", error.value?.localizedDescription ?: "no pattern")
        val player = running.createPlayerWithPattern(pattern, error = error.ptr)
            ?: return HapticResult("error", error.value?.localizedDescription ?: "no player")
        if (!player.startAtTime(0.0, error = error.ptr)) {
            engines.remove(kind)
            return HapticResult("error", error.value?.localizedDescription ?: "play failed")
        }
        return HapticResult("played")
    }

    /**
     * A new engine for [kind]: [HapticKind.CORE_HAPTICS_AUDIO]'s is made on the app's shared audio session, the one
     * `mode.audio` sets to play in the background (without the mode on it plays on the default session); the plain
     * one gets a session of its own from Core Haptics, which iOS interrupts on the lock.
     */
    private fun newEngine(kind: HapticKind, error: ObjCObjectVar<NSError?>): CHHapticEngine? =
        if (kind == HapticKind.CORE_HAPTICS_AUDIO) {
            // The initializer is declared with a forward declaration of AVAudioSession: the cast only renames it.
            @Suppress("CAST_NEVER_SUCCEEDS", "UNCHECKED_CAST_TO_FORWARD_DECLARATION")
            val session = AVAudioSession.sharedInstance() as objcnames.classes.AVAudioSession
            CHHapticEngine(audioSession = session, error = error.ptr)
        } else {
            CHHapticEngine(andReturnError = error.ptr)
        }

    override suspend fun notify(text: String) {
        val content = UNMutableNotificationContent()
        content.setTitle("Hovanki lab")
        content.setBody(text)
        val request = UNNotificationRequest.requestWithIdentifier(
            "hovanki.lab.signal.${notifications++}",
            content,
            null,
        )
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
    }

    private fun notify(kind: HapticKind, body: String, sound: UNNotificationSound?): HapticResult {
        val content = UNMutableNotificationContent()
        content.setTitle("Hovanki lab")
        content.setBody(body)
        if (sound != null) content.setSound(sound)
        // A new identifier every time: a replaced notification may not vibrate again.
        val request = UNNotificationRequest.requestWithIdentifier("hovanki.lab.${notifications++}", content, null)
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) { error ->
            if (error != null) events.tryEmit(kind to "notification failed: ${error.localizedDescription}")
        }
        return HapticResult("played")
    }

    private companion object {
        /** Between the two alerts of a double beat. */
        const val ALERT_GAP_MILLIS = 300L
        const val SHARPNESS = 0.8f
    }
}
