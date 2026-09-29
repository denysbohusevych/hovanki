package app.hovanki.shared.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverflowAreaTest {
    @Test
    fun theTableNamesEveryBitOnce() {
        assertEquals(OverflowArea.BITS, OverflowArea.UUIDS.distinct().size)
        assertEquals("00000000-0000-0000-0000-00000000007C", OverflowArea.uuid(0))
        assertEquals("00000000-0000-0000-0000-000000000037", OverflowArea.uuid(1))
        for (bit in 0 until OverflowArea.BITS) assertEquals(bit, OverflowArea.bitOf(OverflowArea.uuid(bit).lowercase()))
        assertEquals(0, OverflowArea.bitOf("7C"))
        assertNull(OverflowArea.bitOf("7A0B8D2E-4C1F-4E6A-9B3D-2F5E8C1A7D10"))
    }

    @Test
    fun masksGoBothWays() {
        val bits = setOf(0, 7, 8, 63, 127)
        val mask = OverflowArea.maskOf(bits)
        assertEquals(0x81.toByte(), mask[0])
        assertEquals(0x80.toByte(), mask[1])
        assertEquals(bits, OverflowArea.bitsOf(mask))
    }

    /** What an iPhone 12 on iOS 26.2.1 put on the air and a MacBook heard (docs/radio-lab.md, «Как прошло», E1b, E2). */
    @Test
    fun theAirAsMeasured() {
        assertEquals(setOf(0), OverflowArea.bitsOf(hex("80000000000000000000000000000000")))
        assertEquals(setOf(7), OverflowArea.bitsOf(hex("01000000000000000000000000000000")))
        assertEquals(setOf(8), OverflowArea.bitsOf(hex("00800000000000000000000000000000")))
        assertEquals(setOf(11), OverflowArea.bitsOf(hex("00100000000000000000000000000000")))
        // A locked hider: our service's bit alone.
        assertEquals(setOf(OverflowArea.OUR_SERVICE_BIT), OverflowArea.bitsOf(hex("00000000000000000000000000000400")))
        // The probe's tokens: locked (our service's bit too) and on the screen.
        val locked = hex("996995aaa6a99659a280000000000400")
        assertEquals(listOf("68fde92c"), OverflowCode.decode(OverflowArea.bitsOf(locked)))
        assertTrue(
            OverflowArea.maskOf(OverflowCode.encode("68fde92c") + OverflowArea.OUR_SERVICE_BIT).contentEquals(locked),
        )
        val onScreen = hex("9956655a659a699a9280000000000000")
        assertEquals(listOf("1434b6b8"), OverflowCode.decode(OverflowArea.bitsOf(onScreen)))
        assertTrue(OverflowArea.maskOf(OverflowCode.encode("1434b6b8")).contentEquals(onScreen))
    }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun appleDataFindsTheMaskAfterOtherFrames() {
        val mask = OverflowArea.maskOf(setOf(3, 99))
        // A «nearby info» frame (type 0x10, 2 bytes) first, then the overflow area.
        val data = byteArrayOf(0x10, 0x02, 0x0b, 0x1c, 0x01) + mask
        assertTrue(mask.contentEquals(AppleData.overflowMask(data)))
        assertNull(AppleData.overflowMask(byteArrayOf(0x01, 0x00)))
        assertNull(AppleData.overflowMask(byteArrayOf(0x10, 0x02, 0x0b, 0x1c)))
    }

    @Test
    fun appleDataReadsAnIBeacon() {
        val uuid = ByteArray(16) { it.toByte() }
        val data = byteArrayOf(0x02, 0x15) + uuid + byteArrayOf(0x12, 0x34, 0x56, 0x78, 0xC5.toByte())
        val frame = AppleData.iBeacon(data)
        assertEquals("000102030405060708090a0b0c0d0e0f", frame?.uuidHex)
        assertEquals(0x1234, frame?.major)
        assertEquals(0x5678, frame?.minor)
        assertNull(AppleData.iBeacon(byteArrayOf(0x01) + uuid))
    }

    @Test
    fun aTokenGoesThereAndBack() {
        for (token in listOf("00000000", "ffffffff", "0a1b2c3d", "deadbeef")) {
            val bits = OverflowCode.encode(token)
            assertEquals(36, bits.size, "one position of every pair")
            assertTrue(OverflowArea.APPLE_WATCH_BIT !in bits)
            assertEquals(listOf(token), OverflowCode.decode(bits))
        }
    }

    @Test
    fun otherAppsBitsAddCandidatesButNeverChangeAToken() {
        val token = "0a1b2c3d"
        val clean = OverflowCode.encode(token)
        val layout = OverflowLayout.LAB
        // Somebody else sets the other half of two token pairs, plus bits we never use.
        val first = layout.tokenPairs[0]
        val tenth = layout.tokenPairs[9]
        val damaged = clean + first.toList() + tenth.toList() + setOf(100, 120)
        val candidates = OverflowCode.decode(damaged)
        assertEquals(4, candidates.size)
        assertTrue(token in candidates)
        assertEquals(2, OverflowCode.decode(clean + first.toList()).size)
        // A damaged marker pair still passes.
        assertEquals(listOf(token), OverflowCode.decode(clean + layout.markerPairs[1].toList()))
    }

    @Test
    fun notOurMasksDecodeToNothing() {
        assertEquals(emptyList(), OverflowCode.decode(emptySet()))
        assertEquals(emptyList(), OverflowCode.decode(setOf(5, 17)))
        assertEquals(emptyList(), OverflowCode.decode((0 until OverflowArea.BITS).toSet()), "all ones: too damaged")
        val layout = OverflowLayout.LAB
        val wrongMarker = OverflowCode.encode("0a1b2c3d") - layout.markerPairs[0].first + layout.markerPairs[0].second
        assertEquals(emptyList(), OverflowCode.decode(wrongMarker))
        val threeDamaged = OverflowCode.encode("0a1b2c3d") + layout.tokenPairs.take(3).flatMap { it.toList() }
        assertEquals(emptyList(), OverflowCode.decode(threeDamaged))
    }

    @Test
    fun theProbeNeverSetsTheWatchBit() {
        assertEquals(setOf(1, 3, 4, 6, 9, 11, 12, 14), OverflowProbe.PATTERN)
        assertEquals(setOf(42), OverflowProbe.single(42))
        assertFailsWith<IllegalArgumentException> { OverflowProbe.single(OverflowArea.APPLE_WATCH_BIT) }
        val used = (OverflowLayout.LAB.markerPairs + OverflowLayout.LAB.tokenPairs).flatMap { it.toList() }
        assertEquals(72, used.size)
        assertTrue(OverflowArea.APPLE_WATCH_BIT !in used)
        assertTrue(OverflowArea.OUR_SERVICE_BIT !in used)
    }
}
