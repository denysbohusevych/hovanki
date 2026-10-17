package app.hovanki.shared.live

import kotlin.test.Test
import kotlin.test.assertFalse

/** What [LiveAeadCheck] turns down without a real AES-GCM (the JDK's passes it in `LiveAeadVectorsTest`). */
class LiveAeadCheckTest {
    @Test
    fun anAeadThatDoesNotEncryptFails() {
        val plain = object : LiveAead {
            override fun seal(nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
                plaintext + ByteArray(LiveLimits.TAG_BYTES)

            override fun open(nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray? =
                ciphertextAndTag.copyOf(ciphertextAndTag.size - LiveLimits.TAG_BYTES)
        }
        assertFalse(LiveAeadCheck.passes { plain })
    }

    @Test
    fun anAeadThatThrowsFailsWithoutThrowing() {
        assertFalse(LiveAeadCheck.passes { error("no AES-GCM here") })
        val refusing = object : LiveAead {
            override fun seal(nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
                throw IllegalStateException("refused")

            override fun open(nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray? = null
        }
        assertFalse(LiveAeadCheck.passes { refusing })
    }
}
