@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.hovanki.device

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToFile
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/*
 * What the radio lab's iOS pieces share: the vibration test (`lab.IosLabHaptics`) and the background modes
 * ([IosBackgroundModes]); the file of silence also the field build's round ([IosLiveActivityPlatform]). In the root
 * package, which all may import (ModuleBoundariesTest).
 */

/** The file of silence the notifications and the Live Activity's alerts play, so only the vibration is left. */
internal const val SILENT_SOUND = "hovanki-silent.wav"

/**
 * Half a second of silence as `Library/Sounds/hovanki-silent.wav`, where iOS looks for notification and Live Activity
 * sounds too. True when the file is there (written now or before).
 */
internal fun writeSilentSound(): Boolean {
    val library = NSSearchPathForDirectoriesInDomains(NSLibraryDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: return false
    val folder = "$library/Sounds"
    val path = "$folder/$SILENT_SOUND"
    val files = NSFileManager.defaultManager
    if (files.fileExistsAtPath(path)) return true
    files.createDirectoryAtPath(folder, withIntermediateDirectories = true, attributes = null, error = null)
    val bytes = silentWav()
    val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
    return data.writeToFile(path, atomically = true)
}

/**
 * Asks once for notifications with an alert and a sound (iOS shows the question only the first time; later calls
 * answer at once) and returns when iOS has answered, whatever the answer: a refusal shows up as notifications that
 * never arrive, which the tester sees.
 */
internal suspend fun requestLabNotifications() {
    suspendCancellableCoroutine { continuation ->
        UNUserNotificationCenter.currentNotificationCenter()
            .requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ ->
                if (continuation.isActive) continuation.resume(Unit)
            }
    }
}

/**
 * A WAV file of [seconds] of silence: mono, 16 bits, 8 kHz. The notifications' silent sound and the audio session's
 * loop of `mode.audio`.
 */
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
