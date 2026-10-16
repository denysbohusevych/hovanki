package app.hovanki.client.network.live

import app.hovanki.shared.live.LiveAead
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-GCM of `javax.crypto` (docs/live-v2/client.md §4.3), the same on the JVM and Android: the two source sets
 * keep a copy each. One cipher per key, used by one live session at a time; locked all the same.
 */
object JdkLiveAeads : LiveAeads {
    override fun forKey(key: ByteArray): LiveAead = JdkLiveAead(SecretKeySpec(key, "AES"))
}

private class JdkLiveAead(private val key: SecretKeySpec) : LiveAead {
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")

    @Synchronized
    override fun seal(nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    @Synchronized
    override fun open(nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray? = try {
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        cipher.doFinal(ciphertextAndTag)
    } catch (e: AEADBadTagException) {
        null
    }

    private companion object {
        const val TAG_BITS = 128
    }
}
