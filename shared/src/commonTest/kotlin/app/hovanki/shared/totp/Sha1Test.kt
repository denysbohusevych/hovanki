package app.hovanki.shared.totp

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha1Test {
    @Test
    fun knownDigests() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", sha1(""))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1("abc"))
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            sha1("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
        assertEquals("2fd4e1c67a2d28fced849ee1bb76e7391b93eb12", sha1("The quick brown fox jumps over the lazy dog"))
    }

    @Test
    fun paddingBoundaries() {
        // 55/56/64 bytes are the lengths where padding spills into an extra block.
        assertEquals("c1c8bbdc22796e28c0e15163d20899b65621d65a", sha1("a".repeat(55)))
        assertEquals("c2db330f6083854c99d4b5bfb6e8f29f201be699", sha1("a".repeat(56)))
        assertEquals("0098ba824b5c16427bd7a1122a5a442a25ec644d", sha1("a".repeat(64)))
    }

    @Test
    fun hmacRfc2202() {
        assertEquals(
            "b617318655057264e28bc0b6fb378c8ef146be00",
            hmacSha1(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()).toHex(),
        )
        assertEquals(
            "effcdf6ae5eb2fa2d27416d5f184df9c259a7c79",
            hmacSha1("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()).toHex(),
        )
        assertEquals(
            "aa4ae5e15272d00e95705637ce8a3b55ed402112",
            hmacSha1(
                ByteArray(80) { 0xaa.toByte() },
                "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray(),
            ).toHex(),
        )
    }

    private fun sha1(text: String) = Sha1.digest(text.encodeToByteArray()).toHex()
}
