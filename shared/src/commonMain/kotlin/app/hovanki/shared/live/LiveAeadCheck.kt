package app.hovanki.shared.live

import app.hovanki.shared.totp.hexToBytes
import app.hovanki.shared.totp.toHex

/**
 * The spec's known answer for a platform's AES-128-GCM (docs/live-v2/spec.md §18): the Down datagram sealed with
 * the spec's key comes out byte for byte, opens back, and with one byte changed doesn't open. iOS checks its CryptoKit
 * bridge with it before it offers UDP (client.md §6): an implementation that fails keeps the phone on the WebSocket
 * v2.
 */
object LiveAeadCheck {
    /** True when [aeadFor] (a [LiveAead] for a key) seals and opens as the spec does. Never throws. */
    fun passes(aeadFor: (key: ByteArray) -> LiveAead): Boolean = try {
        val aead = aeadFor(ByteArray(KEY_BYTES) { (KEY_BYTES + it).toByte() })
        val plaintext = LiveDatagrams.message(DOWN.hexToBytes())
        val datagram = LiveDatagrams.seal(aead, SESSION, 1, plaintext)
        val changed = datagram.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        datagram.toHex() == DOWN_DATAGRAM &&
            LiveDatagrams.open(aead, datagram)?.contentEquals(plaintext) == true &&
            LiveDatagrams.open(aead, changed) == null
    } catch (e: Exception) {
        false
    }

    private const val KEY_BYTES = 16

    /** a1b2c3d4e5f60718 */
    private const val SESSION = -0x5e4d3c2b1a09f8e8L
    private const val DOWN =
        "080110011896d9cc9b9e332001322022120a0c7037516b324c6d3958613142100158013a0a1208071ca93fd1e2440b"
    private const val DOWN_DATAGRAM =
        "21a1b2c3d4e5f6071800000001c68d58fc287fbe691d352fa96a4d30817164b5741762c2d56ecffb21a4bb32c3" +
            "750c8e2d0c9fe06c5d091078d38253fbdcba3756b13ed8aa9a215f2f0b0f0d59"
}
