package app.hovanki.shared.live

import app.hovanki.shared.totp.hexToBytes
import app.hovanki.shared.totp.toHex
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The encrypted datagrams of docs/live-v2/spec.md §18 with the JDK's AES-GCM (the server's and Android's) and the
 * RESET's HMAC: [LiveDatagrams] puts the header, nonce and AAD where the spec says.
 */
class LiveAeadVectorsTest {
    /** The JDK's AES-128-GCM, as the server will have it. */
    private class JdkAead(key: ByteArray) : LiveAead {
        private val key = SecretKeySpec(key, "AES")

        override fun seal(nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(plaintext)
            }

        override fun open(nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray? = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(ciphertextAndTag)
            }
        } catch (_: AEADBadTagException) {
            null
        }
    }

    private val upKey = JdkAead(ByteArray(16) { it.toByte() })
    private val downKey = JdkAead(ByteArray(16) { (16 + it).toByte() })
    private val session = LiveVectorsTest.SESSION

    @Test
    fun theUpDatagram() {
        val plaintext = LiveDatagrams.message(LiveVectorsTest.UP.hexToBytes())
        val datagram = LiveDatagrams.seal(upKey, session, 1, plaintext)
        assertEquals(84, datagram.size)
        assertEquals(UP_DATAGRAM, datagram.toHex())
        assertContentEquals(plaintext, LiveDatagrams.open(upKey, datagram))
    }

    @Test
    fun theDownDatagram() {
        val datagram = LiveDatagrams.seal(downKey, session, 1, LiveDatagrams.message(LiveVectorsTest.DOWN.hexToBytes()))
        assertEquals(77, datagram.size)
        assertEquals(DOWN_DATAGRAM, datagram.toHex())
    }

    @Test
    fun aChangedHeaderOrTheOtherKeyDoesNotOpen() {
        val datagram = UP_DATAGRAM.hexToBytes()
        assertNull(LiveDatagrams.open(downKey, datagram))
        for (at in listOf(0, 1, 12, 13, datagram.size - 1)) {
            val changed = datagram.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            assertNull(LiveDatagrams.open(upKey, changed), "byte $at")
        }
    }

    @Test
    fun theResetTag() {
        val secret = ByteArray(32) { (32 + it).toByte() }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        val tag = mac.doFinal("a1b2c3d4e5f60718".hexToBytes()).copyOf(16)
        assertEquals("5db9c3d4b54d3a1a55839d6ff976fe7d", tag.toHex())
    }

    private companion object {
        const val UP_DATAGRAM =
            "21a1b2c3d4e5f6071800000001bbddae7b4d3106b5d07766ed2b35c2b43db4799b76dba4d5a6fa391538f0" +
                "ac3a5bb3adcf192233b86b6847c293f56e1ad4173739da6088e67200e8bf1dfd38a6afd84130f11f3f"
        const val DOWN_DATAGRAM =
            "21a1b2c3d4e5f6071800000001c68d58fc287fbe691d352fa96a4d30817164b5741762c2d56ecffb21a4bb32c3" +
                "750c8e2d0c9fe06c5d091078d38253fbdcba3756b13ed8aa9a215f2f0b0f0d59"
    }
}
