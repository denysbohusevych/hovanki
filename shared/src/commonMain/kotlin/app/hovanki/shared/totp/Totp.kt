package app.hovanki.shared.totp

/**
 * RFC 6238 TOTP with HMAC-SHA1. The hider's phone shows the current code (works offline),
 * the server recomputes it from the same secret to confirm a catch.
 */
class Totp(private val secret: ByteArray, val periodSeconds: Int = 30, val digits: Int = 6) {
    init {
        require(periodSeconds > 0) { "periodSeconds must be positive" }
        require(digits in 1..9) { "digits must be in 1..9" }
    }

    private val periodMillis = periodSeconds * 1000L

    fun codeAt(epochMillis: Long): String = codeForCounter(epochMillis.floorDiv(periodMillis))

    /** Milliseconds until [codeAt] changes. */
    fun millisUntilNextCode(epochMillis: Long): Long = periodMillis - epochMillis.mod(periodMillis)

    /**
     * Accepts codes from up to [window] periods before and after [epochMillis],
     * to tolerate clock skew and the time it takes to scan or read out the code.
     */
    fun verify(code: String, epochMillis: Long, window: Int = 1): Boolean {
        val counter = epochMillis.floorDiv(periodMillis)
        var matched = false
        for (offset in -window..window) {
            // No early exit: keep the timing independent of which window matched.
            if (constantTimeEquals(codeForCounter(counter + offset), code)) matched = true
        }
        return matched
    }

    internal fun codeForCounter(counter: Long): String {
        val message = ByteArray(8) { i -> (counter ushr (56 - 8 * i)).toByte() }
        val hash = hmacSha1(secret, message)
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = (hash[offset].toInt() and 0x7f shl 24) or
            (hash[offset + 1].toInt() and 0xff shl 16) or
            (hash[offset + 2].toInt() and 0xff shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % POWERS_OF_TEN[digits]).toString().padStart(digits, '0')
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private companion object {
        val POWERS_OF_TEN =
            intArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000, 1_000_000_000)
    }
}
