package app.hovanki.shared.totp

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Our pure-Kotlin HMAC-SHA1 must match the JDK implementation byte for byte. */
class HmacCrossCheckTest {
    @Test
    fun matchesJdk() {
        val random = Random(42)
        repeat(200) {
            val key = random.nextBytes(random.nextInt(1, 100))
            val message = random.nextBytes(random.nextInt(0, 300))
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
            assertEquals(mac.doFinal(message).toHex(), hmacSha1(key, message).toHex())
        }
    }
}
