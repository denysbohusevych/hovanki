package app.hovanki.shared.qr

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QrCodeTest {
    private val catchCode = "hovanki:1:abc123def456:zyx987wvu654:0421"

    @Test
    fun catchCodeMatchesAKnownSymbol() {
        // ZXing 3.5.4's encoder output for the same text at level M (version 3, mask 2).
        val expected = listOf(
            "#######..#.#..#..#.#..#######",
            "#.....#..##....#.##...#.....#",
            "#.###.#.##.##.....###.#.###.#",
            "#.###.#.#.#.##..##..#.#.###.#",
            "#.###.#.#.#...#..#.#..#.###.#",
            "#.....#.#..#..##.##.#.#.....#",
            "#######.#.#.#.#.#.#.#.#######",
            "........##.##..##.#.#........",
            "#.#####..#.###.###..#.#####..",
            "##.###.....##.#..#.#.####..##",
            ".....##..#...#.##.#..#...#...",
            "..####..#.#....##...#.#.##.##",
            "..##.#######.#.###..##....#..",
            "#####..#.#.#..#..#.#..#####.#",
            "#####.######...#..#..#.##.#..",
            "#..##...##..#.#.#.###...##..#",
            ".#..###..#.#...####..#....#.#",
            "##......##.###...#.##.###.###",
            "#.#######..######.#.##...##..",
            "#.#.#..##.####.##...###..#.#.",
            "#.#...#.#.#.#.#####.#####.###",
            "........####....##..#...#.###",
            "#######..#.....#..###.#.##...",
            "#.....#.#####.#.#.###...#..#.",
            "#.###.#.####...####.#########",
            "#.###.#.##.#.##.#....#.#..###",
            "#.###.#.###..#.###.#..#.#.##.",
            "#.....#.....##..#...##.##..#.",
            "#######.##..#..###.#..#.###..",
        )
        val code = QrCode.encode(catchCode, QrEcc.MEDIUM)

        assertEquals(3, code.version)
        assertEquals(expected, code.rows())
    }

    @Test
    fun sizeFollowsTheVersion() {
        for ((text, version) in listOf("" to 1, "hovanki" to 1, catchCode to 3, "x".repeat(80) to 5)) {
            val code = QrCode.encode(text)
            assertEquals(version, code.version, text)
            assertEquals(17 + 4 * version, code.size, text)
        }
        assertEquals(QrEcc.MEDIUM, QrCode.encode(catchCode).ecc)
    }

    @Test
    fun versionGrowsWithTheText() {
        for (ecc in QrEcc.entries) {
            var previous = 1
            for (length in 0..600 step 7) {
                val version = QrCode.encode("h".repeat(length), ecc).version
                assertTrue(version >= previous, "$length bytes at $ecc: version $version after $previous")
                previous = version
            }
            assertTrue(previous > 10, "600 bytes at $ecc need more than version 10, not $previous")
        }
        // More error correction leaves less room for data.
        val versions = QrEcc.entries.map { QrCode.encode(catchCode, it).version }
        assertEquals(versions.sorted(), versions)
        assertTrue(versions.first() < versions.last())
    }

    @Test
    fun capacityOfVersion40() {
        val capacities = mapOf(QrEcc.LOW to 2953, QrEcc.MEDIUM to 2331, QrEcc.QUARTILE to 1663, QrEcc.HIGH to 1273)
        for ((ecc, capacity) in capacities) {
            assertEquals(40, QrCode.encode("h".repeat(capacity), ecc).version)
            assertFailsWith<IllegalArgumentException> { QrCode.encode("h".repeat(capacity + 1), ecc) }
        }
        // UTF-8 bytes count, not characters: Cyrillic letters take 2 bytes each.
        assertEquals(40, QrCode.encode("п".repeat(2331 / 2)).version)
        assertFailsWith<IllegalArgumentException> { QrCode.encode("п".repeat(2331 / 2 + 1)) }
    }

    @Test
    fun functionPatterns() {
        for (text in listOf("", catchCode, "x".repeat(200), "x".repeat(2000))) {
            val code = QrCode.encode(text, QrEcc.LOW)
            val last = code.size - 1
            assertFinder(code, 0, 0)
            assertFinder(code, code.size - 7, 0)
            assertFinder(code, 0, code.size - 7)
            // Timing patterns between the finders: dark on even indexes.
            for (i in 8..last - 8) {
                assertEquals(i % 2 == 0, code[i, 6], "horizontal timing at $i, version ${code.version}")
                assertEquals(i % 2 == 0, code[6, i], "vertical timing at $i, version ${code.version}")
            }
            assertTrue(code[8, code.size - 8], "dark module, version ${code.version}")
            if (code.version >= 2) assertAlignment(code, last - 6, last - 6)
        }
    }

    @Test
    fun formatInformationOfMask0() {
        // Standard format bits for mask 0 (ISO/IEC 18004, annex C), bit 14 first.
        val expected = mapOf(
            QrEcc.LOW to 0b111011111000100,
            QrEcc.MEDIUM to 0b101010000010010,
            QrEcc.QUARTILE to 0b011010101011111,
            QrEcc.HIGH to 0b001011010001001,
        )
        for ((ecc, bits) in expected) {
            val code = QrCode.encodeBytes("hovanki".encodeToByteArray(), ecc, forcedMask = 0)
            // Bit i of the first copy: down column 8 (skipping the timing row), then left along row 8.
            val first = listOf(0, 1, 2, 3, 4, 5, 7, 8).map { 8 to it } + listOf(7, 5, 4, 3, 2, 1, 0).map { it to 8 }
            // Second copy: left from the right edge along row 8, then down column 8 to the bottom edge.
            val second = (0..7).map { code.size - 1 - it to 8 } + (8..14).map { 8 to code.size - 15 + it }
            for ((name, positions) in listOf("first" to first, "second" to second)) {
                val read = positions.withIndex().sumOf { (i, xy) -> if (code[xy.first, xy.second]) 1 shl i else 0 }
                assertEquals(bits, read, "$name copy at $ecc")
            }
        }
    }

    @Test
    fun versionInformationFromVersion7() {
        // Standard version bits (ISO/IEC 18004, annex D).
        for ((version, bits) in listOf(7 to 0x07C94, 20 to 0x149A6, 40 to 0x28C69)) {
            val text = "h".repeat(QrCode.byteCapacity(version, QrEcc.MEDIUM))
            val code = QrCode.encode(text)
            assertEquals(version, code.version)
            var topRight = 0
            var bottomLeft = 0
            for (i in 0 until 18) {
                if (code[code.size - 11 + i % 3, i / 3]) topRight = topRight or (1 shl i)
                if (code[i / 3, code.size - 11 + i % 3]) bottomLeft = bottomLeft or (1 shl i)
            }
            assertEquals(bits, topRight, "version $version, top right")
            assertEquals(bits, bottomLeft, "version $version, bottom left")
        }
    }

    @Test
    fun rejectsModulesOutsideTheSymbol() {
        val code = QrCode.encode(catchCode)
        assertFailsWith<IndexOutOfBoundsException> { code[-1, 0] }
        assertFailsWith<IndexOutOfBoundsException> { code[0, code.size] }
    }

    private fun QrCode.rows() = (0 until size).map { y ->
        (0 until size).joinToString("") { x -> if (this[x, y]) "#" else "." }
    }

    /** A 7×7 finder with its top-left corner at ([left], [top]), and its light separator inside the symbol. */
    private fun assertFinder(code: QrCode, left: Int, top: Int) {
        for (dy in -1..7) {
            for (dx in -1..7) {
                val x = left + dx
                val y = top + dy
                if (x !in 0 until code.size || y !in 0 until code.size) continue
                val ring = maxOf(abs(dx - 3), abs(dy - 3))
                assertEquals(ring != 2 && ring != 4, code[x, y], "finder module ($x, $y), version ${code.version}")
            }
        }
    }

    private fun assertAlignment(code: QrCode, cx: Int, cy: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                val dark = maxOf(abs(dx), abs(dy)) != 1
                assertEquals(dark, code[cx + dx, cy + dy], "alignment module (${cx + dx}, ${cy + dy})")
            }
        }
    }
}
