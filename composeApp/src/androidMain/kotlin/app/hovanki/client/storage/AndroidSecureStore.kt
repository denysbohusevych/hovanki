package app.hovanki.client.storage

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SecureStore] on Android: every value is encrypted with AES-256-GCM under a key that lives in the Android Keystore
 * (not exportable, never in the app's memory as bytes), and only the ciphertext goes to the app's private
 * SharedPreferences. The key name is authenticated with the value, so values can't be swapped between keys.
 * Backups are off (`android:allowBackup="false"`), and a copied file is useless without the key.
 * See docs/adr/0002-session-storage.md.
 */
class AndroidSecureStore(context: Context) : SecureStore {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun read(key: String): String? {
        val stored = preferences.getString(key, null) ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        cipher.updateAAD(key.encodeToByteArray())
        return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
    }

    // commit, not apply: the session has to be on disk before the player swipes the app away.
    @SuppressLint("ApplySharedPref")
    override fun write(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore picks a fresh random IV for every encryption.
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(key.encodeToByteArray())
        val encrypted = cipher.iv + cipher.doFinal(value.encodeToByteArray())
        preferences.edit().putString(key, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()
    }

    @SuppressLint("ApplySharedPref")
    override fun remove(key: String) {
        preferences.edit().remove(key).commit()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val FILE_NAME = "hovanki_secure"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "hovanki_storage"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
