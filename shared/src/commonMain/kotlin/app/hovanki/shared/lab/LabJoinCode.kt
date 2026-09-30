package app.hovanki.shared.lab

import kotlin.random.Random

/**
 * The code a phone joins a run of the radio lab with: [LENGTH] characters of [ALPHABET] (no 0/O, 1/I), typed in or
 * scanned from the admin's QR ([qrPayload]). Short-lived and only for test phones: the run closes after a day.
 */
object LabJoinCode {
    const val LENGTH = 6
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val QR_PREFIX = "hovanki-lab:"

    /** The code in [text] (typed or scanned): upper case, without spaces, dashes or the QR's prefix; null: not one. */
    fun normalize(text: String): String? {
        val trimmed = text.trim()
        val bare = if (trimmed.startsWith(QR_PREFIX, ignoreCase = true)) trimmed.drop(QR_PREFIX.length) else trimmed
        val code = bare.uppercase().filterNot { it.isWhitespace() || it == '-' }
        return code.takeIf { it.length == LENGTH && it.all { char -> char in ALPHABET } }
    }

    /** What the admin's QR carries: `hovanki-lab:ABC234`. */
    fun qrPayload(code: String): String = QR_PREFIX + code

    fun random(random: Random): String = buildString {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
}
