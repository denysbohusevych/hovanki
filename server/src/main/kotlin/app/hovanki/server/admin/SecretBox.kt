package app.hovanki.server.admin

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts the staff's authenticator secrets for the database with AES-256-GCM and `hovanki.admin.secret-key`: a leaked
 * dump or backup doesn't give away the second factor. Stored as base64 of a random 12-byte nonce + the ciphertext with
 * its tag. A wrong or malformed key fails at startup, an empty one leaves the admin off ([AdminProperties.enabled]).
 */
@Component
class SecretBox(properties: AdminProperties) {
    private val key: SecretKeySpec? = properties.secretKey.takeIf { it.isNotBlank() }?.let { encoded ->
        val bytes = try {
            Base64.getDecoder().decode(encoded.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("hovanki.admin.secret-key is not base64", e)
        }
        check(bytes.size == KEY_BYTES) { "hovanki.admin.secret-key must be $KEY_BYTES bytes, base64" }
        SecretKeySpec(bytes, "AES")
    }
    private val random = SecureRandom()

    fun seal(plain: ByteArray): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, requireKey(), GCMParameterSpec(TAG_BITS, nonce))
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plain))
    }

    /** Null if [sealed] was not made with this key (a new key: the staff set up their authenticators again). */
    fun open(sealed: String): ByteArray? = try {
        val bytes = Base64.getDecoder().decode(sealed)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, requireKey(), GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES))
        cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES)
    } catch (e: javax.crypto.AEADBadTagException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun requireKey(): SecretKeySpec = checkNotNull(key) { "The admin is off: no hovanki.admin.secret-key" }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
