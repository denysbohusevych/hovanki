package app.hovanki.server.admin

import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SecretBoxTest {
    private fun key() = Base64.getEncoder().encodeToString(Random.nextBytes(32))

    @Test
    fun sealsAndOpensWithTheSameKeyOnly() {
        val box = SecretBox(AdminProperties(secretKey = key()))
        val secret = Random.nextBytes(20)
        val sealed = box.seal(secret)
        assertFalse(sealed.contains(Base64.getEncoder().encodeToString(secret)))
        assertContentEquals(secret, box.open(sealed))
        // A fresh nonce every time.
        assertFalse(sealed == box.seal(secret))
        // Another key, or a changed byte: nothing.
        assertNull(SecretBox(AdminProperties(secretKey = key())).open(sealed))
        val bytes = Base64.getDecoder().decode(sealed).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(box.open(Base64.getEncoder().encodeToString(bytes)))
    }

    @Test
    fun aMalformedKeyStopsTheServer() {
        assertFailsWith<IllegalStateException> { SecretBox(AdminProperties(secretKey = "not base64!")) }
        assertFailsWith<IllegalStateException> {
            SecretBox(AdminProperties(secretKey = Base64.getEncoder().encodeToString(ByteArray(16))))
        }
        // No key: the admin is off, nothing to seal with.
        assertFailsWith<IllegalStateException> { SecretBox(AdminProperties()).seal(ByteArray(20)) }
    }

    @Test
    fun base32LikeAuthenticatorApps() {
        // RFC 4648 test vectors, without padding.
        val vectors = mapOf(
            "" to "",
            "f" to "MY",
            "fo" to "MZXQ",
            "foo" to "MZXW6",
            "foob" to "MZXW6YQ",
            "fooba" to "MZXW6YTB",
        )
        for ((plain, encoded) in vectors) assertEquals(encoded, Base32.encode(plain.toByteArray()))
    }

    @Test
    fun maskedEmails() {
        assertEquals("d•••@gmail.com", EmailMask.mask("denys@gmail.com"))
        assertEquals("a•••@b.co", EmailMask.mask("a@b.co"))
        assertEquals("•••", EmailMask.mask("nonsense"))
    }
}
