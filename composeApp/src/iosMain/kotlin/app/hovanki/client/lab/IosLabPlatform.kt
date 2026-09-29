@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.hovanki.client.lab

import app.hovanki.client.share.presentShareSheet
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreHaptics.CHHapticEngine
import platform.CoreHaptics.CHHapticEvent
import platform.CoreHaptics.CHHapticEventParameter
import platform.CoreHaptics.CHHapticEventParameterIDHapticIntensity
import platform.CoreHaptics.CHHapticEventParameterIDHapticSharpness
import platform.CoreHaptics.CHHapticEventTypeHapticTransient
import platform.CoreHaptics.CHHapticPattern
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UIKit.UIDevice
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/**
 * The screen turned off by the proximity sensor while the app stays active (docs/radio-lab.md, H3): the sensor on, the
 * idle timer off, so the phone doesn't lock by itself. Debug builds only.
 */
class IosLabScreen : LabScreen {
    override val canTurnOffByProximity: Boolean = true

    override fun setOffByProximity(on: Boolean) {
        UIDevice.currentDevice.proximityMonitoringEnabled = on
        UIApplication.sharedApplication.idleTimerDisabled = on
    }
}

/**
 * Every way an iPhone app may vibrate, for the vibration test (docs/radio-lab.md, H2): Core Haptics (an engine the
 * system stops when it suspends the app, it says why), the impact generator the game's pulse uses on screen, and two
 * notifications: with a silent sound (a file of silence the lab writes into `Library/Sounds`: a notification with a
 * sound vibrates as the ringer's settings say) and without one. Debug builds only; written without an iOS build.
 */
class IosLabHaptics : LabHaptics {
    override val kinds: List<HapticKind> = listOf(
        HapticKind.CORE_HAPTICS,
        HapticKind.IMPACT,
        HapticKind.NOTIFY_SILENT_SOUND,
        HapticKind.NOTIFY_NO_SOUND,
    )

    private val events = MutableSharedFlow<Pair<HapticKind, String>>(extraBufferCapacity = 16)
    private var engine: CHHapticEngine? = null
    private val impact by lazy { UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy) }
    private var notifications = 0

    override fun engineEvents(): Flow<Pair<HapticKind, String>> = events.asSharedFlow()

    override suspend fun prepare() {
        writeSilentSound()
        suspendCancellableCoroutine { continuation ->
            UNUserNotificationCenter.currentNotificationCenter()
                .requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ ->
                    if (continuation.isActive) continuation.resume(Unit)
                }
        }
    }

    override suspend fun play(kind: HapticKind, strength: Double): HapticResult = when (kind) {
        HapticKind.CORE_HAPTICS -> playCoreHaptics(strength)

        HapticKind.IMPACT -> {
            impact.prepare()
            impact.impactOccurredWithIntensity(strength)
            val active =
                UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive
            // UIKit says nothing when it drops a tap off screen: the log's app state tells.
            HapticResult("played", if (active) null else "not active: UIKit may drop it")
        }

        HapticKind.NOTIFY_SILENT_SOUND -> notify(withSound = true)

        HapticKind.NOTIFY_NO_SOUND -> notify(withSound = false)

        HapticKind.VIBRATOR -> HapticResult("skipped", "not on iOS")
    }

    private fun playCoreHaptics(strength: Double): HapticResult {
        memScoped {
            return playCoreHaptics(strength, alloc<ObjCObjectVar<NSError?>>())
        }
    }

    private fun playCoreHaptics(strength: Double, error: ObjCObjectVar<NSError?>): HapticResult {
        val running = engine ?: run {
            val made = CHHapticEngine(andReturnError = error.ptr)
                ?: return HapticResult("error", error.value?.localizedDescription ?: "no engine")
            made.stoppedHandler = { reason ->
                engine = null
                events.tryEmit(HapticKind.CORE_HAPTICS to "engine_stopped: $reason")
            }
            made.resetHandler = {
                engine = null
                events.tryEmit(HapticKind.CORE_HAPTICS to "engine_reset")
            }
            if (!made.startAndReturnError(error.ptr)) {
                return HapticResult("error", error.value?.localizedDescription ?: "start failed")
            }
            engine = made
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
            engine = null
            return HapticResult("error", error.value?.localizedDescription ?: "play failed")
        }
        return HapticResult("played")
    }

    private fun notify(withSound: Boolean): HapticResult {
        val content = UNMutableNotificationContent()
        content.setTitle("Hovanki lab")
        content.setBody(if (withSound) "vibration test: silent sound" else "vibration test: no sound")
        if (withSound) content.setSound(UNNotificationSound.soundNamed(SILENT_SOUND))
        // A new identifier every time: a replaced notification may not vibrate again.
        val request = UNNotificationRequest.requestWithIdentifier("hovanki.lab.${notifications++}", content, null)
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) { error ->
            if (error != null) {
                val kind = if (withSound) HapticKind.NOTIFY_SILENT_SOUND else HapticKind.NOTIFY_NO_SOUND
                events.tryEmit(kind to "notification failed: ${error.localizedDescription}")
            }
        }
        return HapticResult("played")
    }

    /** Half a second of silence as `Library/Sounds/hovanki-silent.wav`, where iOS looks for notification sounds too. */
    private fun writeSilentSound() {
        val library = NSSearchPathForDirectoriesInDomains(NSLibraryDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: return
        val folder = "$library/Sounds"
        val path = "$folder/$SILENT_SOUND"
        val files = NSFileManager.defaultManager
        if (files.fileExistsAtPath(path)) return
        files.createDirectoryAtPath(folder, withIntermediateDirectories = true, attributes = null, error = null)
        val bytes = silentWav()
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        data.writeToFile(path, atomically = true)
    }

    private companion object {
        const val SILENT_SOUND = "hovanki-silent.wav"
        const val SHARPNESS = 0.8f
    }
}

/** A WAV file of [seconds] of silence: mono, 16 bits, 8 kHz. */
internal fun silentWav(seconds: Double = 0.5): ByteArray {
    val rate = 8_000
    val samples = (rate * seconds).toInt()
    val dataSize = samples * 2
    val out = ArrayList<Byte>(44 + dataSize)
    fun text(value: String) = value.forEach { out += it.code.toByte() }
    fun int32(value: Int) = repeat(4) { out += (value shr (8 * it) and 0xff).toByte() }
    fun int16(value: Int) = repeat(2) { out += (value shr (8 * it) and 0xff).toByte() }
    text("RIFF")
    int32(36 + dataSize)
    text("WAVE")
    text("fmt ")
    int32(16)
    int16(1) // PCM
    int16(1) // mono
    int32(rate)
    int32(rate * 2) // bytes a second
    int16(2) // bytes a frame
    int16(16) // bits a sample
    text("data")
    int32(dataSize)
    repeat(dataSize) { out += 0 }
    return out.toByteArray()
}

/**
 * The lab's files into the system «Share» (docs/radio-lab.md §4.2): written into the temporary folder only for that,
 * deleted when the sheet closes.
 */
class IosLabFiles : LabFiles {
    override fun share(files: List<LabFile>) {
        val folder = "${NSTemporaryDirectory()}lab"
        val manager = NSFileManager.defaultManager
        manager.removeItemAtPath(folder, error = null)
        manager.createDirectoryAtPath(folder, withIntermediateDirectories = true, attributes = null, error = null)
        val urls = files.map { file ->
            val path = "$folder/${file.name}"
            NSString.create(string = file.text)
                .writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
            NSURL.fileURLWithPath(path)
        }
        presentShareSheet(urls) { manager.removeItemAtPath(folder, error = null) }
    }
}
