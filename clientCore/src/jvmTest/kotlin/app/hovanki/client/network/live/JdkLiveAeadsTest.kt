package app.hovanki.client.network.live

import app.hovanki.shared.live.LiveDatagrams
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The JVM's and Android's AES-128-GCM of the live channel v2 (docs/live-v2/spec.md §5.2). */
class JdkLiveAeadsTest {
    private val key = ByteArray(16) { it.toByte() }

    @Test
    fun aSealedDatagramOpensWithItsKeyOnlyAndUnchanged() {
        val plaintext = LiveDatagrams.message("up".encodeToByteArray())
        val datagram = LiveDatagrams.seal(JdkLiveAeads.forKey(key), sessionId = 7, pn = 1, plaintext = plaintext)
        assertEquals(13 + plaintext.size + 16, datagram.size)
        assertContentEquals(plaintext, LiveDatagrams.open(JdkLiveAeads.forKey(key), datagram))
        assertNull(LiveDatagrams.open(JdkLiveAeads.forKey(ByteArray(16)), datagram), "another key")
        val changed = datagram.copyOf().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 1).toByte() }
        assertNull(LiveDatagrams.open(JdkLiveAeads.forKey(key), changed), "changed on the way")
        val header = datagram.copyOf().also { it[12] = 2 }
        assertNull(LiveDatagrams.open(JdkLiveAeads.forKey(key), header), "another packet number")
    }

    @Test
    fun oneKeySealsPacketAfterPacket() {
        val aead = JdkLiveAeads.forKey(key)
        val datagrams = (1..3).map { LiveDatagrams.seal(aead, 7, it, LiveDatagrams.message(byteArrayOf(it.toByte()))) }
        datagrams.forEachIndexed { i, datagram ->
            assertContentEquals(byteArrayOf(1, (i + 1).toByte()), LiveDatagrams.open(aead, datagram))
        }
    }
}
