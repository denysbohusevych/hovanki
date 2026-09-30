package app.hovanki.device

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/*
 * What the radio lab's iOS pieces share: the vibration test (`lab.IosLabHaptics`) and the background modes
 * ([IosBackgroundModes]). In the root package, which both may import (ModuleBoundariesTest).
 */

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
