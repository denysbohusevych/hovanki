package app.hovanki.shared.totp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TotpTest {
    // RFC 6238, Appendix B (SHA1, 8 digits, 30 s step).
    private val rfcSecret = "12345678901234567890".encodeToByteArray()
    private val rfcTotp = Totp(rfcSecret, periodSeconds = 30, digits = 8)

    @Test
    fun matchesRfc6238TestVectors() {
        val vectors = mapOf(
            59L to "94287082",
            1111111109L to "07081804",
            1111111111L to "14050471",
            1234567890L to "89005924",
            2000000000L to "69279037",
            20000000000L to "65353130",
        )
        vectors.forEach { (seconds, expected) ->
            assertEquals(expected, rfcTotp.codeAt(seconds * 1000), "T=$seconds")
        }
    }

    @Test
    fun verifyAcceptsNeighbourPeriodsOnly() {
        val totp = Totp("00112233445566778899aabbccddeeff00112233".hexToBytes(), periodSeconds = 30, digits = 8)
        val now = 1_700_000_000_000L

        assertTrue(totp.verify(totp.codeAt(now), now))
        assertTrue(totp.verify(totp.codeAt(now - 30_000), now), "previous code is still accepted")
        assertTrue(totp.verify(totp.codeAt(now + 30_000), now), "a slightly fast clock is tolerated")
        assertFalse(totp.verify(totp.codeAt(now - 90_000), now), "old codes expire")
        assertFalse(totp.verify("123", now))
    }

    @Test
    fun codesArePaddedToDigits() {
        val totp = Totp(rfcSecret, digits = 6)
        repeat(50) { step ->
            assertEquals(6, totp.codeAt(step * 30_000L).length)
        }
    }

    @Test
    fun millisUntilNextCode() {
        val totp = Totp(rfcSecret, periodSeconds = 30)
        assertEquals(30_000, totp.millisUntilNextCode(60_000))
        assertEquals(1, totp.millisUntilNextCode(89_999))
    }
}
