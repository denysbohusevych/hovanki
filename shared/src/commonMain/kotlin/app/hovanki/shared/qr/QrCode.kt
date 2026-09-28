package app.hovanki.shared.qr

import kotlin.math.abs

/**
 * Error-correction level of a [QrCode]: roughly the share of the symbol that may be damaged or covered and still
 * read (7, 15, 25 and 30 %). A higher level makes the symbol bigger for the same text.
 */
enum class QrEcc(internal val formatBits: Int) {
    LOW(1),
    MEDIUM(0),
    QUARTILE(3),
    HIGH(2),
}

/**
 * A QR code (ISO/IEC 18004, model 2): [size] × [size] modules, `true` = dark. The quiet zone, a light border of at
 * least 4 modules that readers need around the symbol, is not included: the drawing code adds it.
 *
 * Only encoding, in pure Kotlin for every platform: [encode] turns the hider's catch-code payload (or any text) into
 * modules, and the UI draws them.
 */
class QrCode private constructor(
    /** 1..40, the smallest version that holds the text; the symbol is 17 + 4 × version modules wide. */
    val version: Int,
    val ecc: QrEcc,
    /** The data mask pattern (0..7) that was applied. */
    internal val mask: Int,
    private val modules: BooleanArray,
) {
    /** Width and height in modules, without the quiet zone. */
    val size: Int get() = sizeOf(version)

    /** Whether the module in column [x] and row [y], both counted from the top-left corner, is dark. */
    operator fun get(x: Int, y: Int): Boolean {
        if (x !in 0 until size || y !in 0 until size) {
            throw IndexOutOfBoundsException("Module ($x, $y) is outside the $size×$size QR code")
        }
        return modules[y * size + x]
    }

    companion object {
        /**
         * Encodes [text] as UTF-8 bytes in a single byte-mode segment (no ECI header: readers recognise UTF-8) in
         * the smallest version that fits at the [ecc] level, with the mask of the lowest penalty score.
         *
         * @throws IllegalArgumentException if the text does not fit even in version 40: its UTF-8 form is longer
         * than 2953 bytes at [QrEcc.LOW], 2331 at [QrEcc.MEDIUM], 1663 at [QrEcc.QUARTILE] or 1273 at [QrEcc.HIGH].
         */
        fun encode(text: String, ecc: QrEcc = QrEcc.MEDIUM): QrCode = encodeBytes(text.encodeToByteArray(), ecc)

        /** [encode] for raw bytes; [forcedMask] skips the mask choice (tests compare with a reference encoder). */
        internal fun encodeBytes(data: ByteArray, ecc: QrEcc, forcedMask: Int? = null): QrCode {
            require(forcedMask == null || forcedMask in 0..7) { "Mask must be 0..7, not $forcedMask" }
            val version = (MIN_VERSION..MAX_VERSION).firstOrNull { data.size <= byteCapacity(it, ecc) }
            require(version != null) {
                "Text is too long for a QR code: ${data.size} bytes, at most ${byteCapacity(MAX_VERSION, ecc)} " +
                    "at error-correction level $ecc"
            }
            val matrix = Matrix(version)
            matrix.drawFunctionPatterns()
            matrix.drawCodewords(addEccAndInterleave(dataCodewords(data, version, ecc), version, ecc))
            val mask = forcedMask ?: (0..7).minBy { matrix.penaltyWith(ecc, it) }
            matrix.applyMask(mask)
            matrix.drawFormatBits(ecc, mask)
            return QrCode(version, ecc, mask, matrix.modules)
        }

        /** How many bytes a symbol of [version] holds at the [ecc] level. */
        internal fun byteCapacity(version: Int, ecc: QrEcc): Int =
            (dataCodewordCount(version, ecc) * 8 - MODE_BITS - charCountBits(version)) / 8
    }
}

private const val MIN_VERSION = 1
private const val MAX_VERSION = 40

/** Mode indicator of a byte-mode segment. */
private const val BYTE_MODE = 0b0100
private const val MODE_BITS = 4

private fun sizeOf(version: Int) = 17 + 4 * version

/** Width of the byte-mode character count field. */
private fun charCountBits(version: Int) = if (version <= 9) 8 else 16

/**
 * Header, data, terminator and padding: all data codewords of the symbol, before error correction.
 * The array starts zeroed, so only the one bits are written.
 */
private fun dataCodewords(data: ByteArray, version: Int, ecc: QrEcc): ByteArray {
    val result = ByteArray(dataCodewordCount(version, ecc))
    var bitLength = 0
    fun append(value: Int, bitCount: Int) {
        for (i in bitCount - 1 downTo 0) {
            if ((value ushr i) and 1 != 0) {
                val index = bitLength ushr 3
                result[index] = (result[index].toInt() or (0x80 ushr (bitLength and 7))).toByte()
            }
            bitLength++
        }
    }
    append(BYTE_MODE, MODE_BITS)
    append(data.size, charCountBits(version))
    for (byte in data) append(byte.toInt() and 0xFF, 8)
    // Terminator: up to 4 zero bits, then zero bits up to the byte boundary. The pad codewords 0xEC and 0x11
    // alternate after it until the capacity is full.
    val used = (minOf(bitLength + 4, result.size * 8) + 7) / 8
    for (i in used until result.size) result[i] = (if ((i - used) % 2 == 0) 0xEC else 0x11).toByte()
    return result
}

/**
 * Splits the data codewords into blocks, appends each block's Reed–Solomon codewords and interleaves them: the
 * first codeword of every block, then the second of every block and so on, first data, then error correction.
 */
private fun addEccAndInterleave(data: ByteArray, version: Int, ecc: QrEcc): ByteArray {
    val blockCount = ECC_BLOCK_COUNT[ecc.ordinal][version - 1]
    val eccLength = ECC_CODEWORDS_PER_BLOCK[ecc.ordinal][version - 1]
    val total = rawDataModules(version) / 8
    // Two groups of blocks: the long blocks, last, hold one data codeword more than the short ones.
    val shortBlocks = blockCount - total % blockCount
    val shortDataLength = total / blockCount - eccLength
    val divisor = reedSolomonDivisor(eccLength)
    var offset = 0
    val dataBlocks = List(blockCount) { block ->
        val length = shortDataLength + if (block < shortBlocks) 0 else 1
        data.copyOfRange(offset, offset + length).also { offset += length }
    }
    val eccBlocks = dataBlocks.map { reedSolomonRemainder(it, divisor) }

    val result = ByteArray(total)
    var k = 0
    for (i in 0..shortDataLength) {
        for (block in dataBlocks) if (i < block.size) result[k++] = block[i]
    }
    for (i in 0 until eccLength) {
        for (block in eccBlocks) result[k++] = block[i]
    }
    check(k == total) { "Interleaved $k codewords instead of $total" }
    return result
}

/**
 * The Reed–Solomon generator polynomial of [degree]: the product of (x − α^i) for i in 0 until [degree], α = 2.
 * Coefficients from the highest power down, without the leading 1.
 */
private fun reedSolomonDivisor(degree: Int): IntArray {
    val result = IntArray(degree)
    result[degree - 1] = 1 // the polynomial 1
    var root = 1
    repeat(degree) {
        // Multiply by (x − root); minus is plus in GF(2^8).
        for (j in 0 until degree) {
            result[j] = gfMultiply(result[j], root)
            if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
        }
        root = gfMultiply(root, 2)
    }
    return result
}

/** Error-correction codewords of one block: the remainder of data × x^degree divided by [divisor]. */
private fun reedSolomonRemainder(data: ByteArray, divisor: IntArray): ByteArray {
    val result = IntArray(divisor.size)
    for (byte in data) {
        val factor = (byte.toInt() and 0xFF) xor result[0]
        result.copyInto(result, destinationOffset = 0, startIndex = 1)
        result[result.lastIndex] = 0
        for (i in result.indices) result[i] = result[i] xor gfMultiply(divisor[i], factor)
    }
    return ByteArray(result.size) { result[it].toByte() }
}

/** Product in GF(2^8) modulo the QR code's primitive polynomial x^8 + x^4 + x^3 + x^2 + 1 (0x11D). */
private fun gfMultiply(x: Int, y: Int): Int {
    var z = 0
    for (i in 7 downTo 0) {
        z = (z shl 1) xor ((z ushr 7) * 0x11D)
        z = z xor (((y ushr i) and 1) * x)
    }
    return z
}

/** Modules left for data and error correction once the function patterns are drawn. */
private fun rawDataModules(version: Int): Int {
    // All modules minus the finders with separators, the timing patterns, the format information and the dark module.
    var result = (16 * version + 128) * version + 64
    if (version >= 2) {
        val count = version / 7 + 2
        // Alignment patterns: 25 modules each, minus the overlaps with the timing patterns; none on the finders.
        result -= (25 * count - 10) * count - 55
        if (version >= 7) result -= 2 * 18 // two copies of the version information
    }
    return result
}

private fun dataCodewordCount(version: Int, ecc: QrEcc): Int = rawDataModules(version) / 8 -
    ECC_CODEWORDS_PER_BLOCK[ecc.ordinal][version - 1] * ECC_BLOCK_COUNT[ecc.ordinal][version - 1]

/** Row and column of the alignment pattern centers: the first is 6, the rest evenly spaced up to size − 7. */
internal fun alignmentPatternCenters(version: Int): IntArray {
    if (version == 1) return IntArray(0)
    val count = version / 7 + 2
    // The spacing is even and the gap next to 6 takes the leftover (only version 32 needs the rounding up).
    val step = (version * 8 + count * 3 + 5) / (count * 4 - 4) * 2
    return IntArray(count) { i -> if (i == 0) 6 else sizeOf(version) - 7 - (count - 1 - i) * step }
}

/** Whether data mask [mask] flips the module in column [x] and row [y]. */
private fun maskFlips(mask: Int, x: Int, y: Int): Boolean = when (mask) {
    0 -> (x + y) % 2 == 0
    1 -> y % 2 == 0
    2 -> x % 3 == 0
    3 -> (x + y) % 3 == 0
    4 -> (x / 3 + y / 2) % 2 == 0
    5 -> x * y % 2 + x * y % 3 == 0
    6 -> (x * y % 2 + x * y % 3) % 2 == 0
    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
}

/** The module grid being built; [isFunction] marks the modules that data and masks leave alone. */
private class Matrix(private val version: Int) {
    val size = sizeOf(version)
    val modules = BooleanArray(size * size)
    private val isFunction = BooleanArray(size * size)

    private fun setFunction(x: Int, y: Int, dark: Boolean) {
        modules[y * size + x] = dark
        isFunction[y * size + x] = true
    }

    fun drawFunctionPatterns() {
        // Timing patterns along row 6 and column 6, dark on even indexes; the finders overwrite their ends.
        for (i in 0 until size) {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)
        val centers = alignmentPatternCenters(version)
        val last = centers.lastIndex
        for (i in centers.indices) {
            for (j in centers.indices) {
                val onFinder = (i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)
                if (!onFinder) drawAlignment(centers[i], centers[j])
            }
        }
        // Reserves the format areas and draws the dark module; the real format goes in once the mask is chosen.
        drawFormatBits(QrEcc.LOW, 0)
        drawVersion()
    }

    /** A 7×7 finder centered at ([cx], [cy]) with its light separator, clipped at the symbol's edge. */
    private fun drawFinder(cx: Int, cy: Int) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                val ring = maxOf(abs(dx), abs(dy))
                if (x in 0 until size && y in 0 until size) setFunction(x, y, ring != 2 && ring != 4)
            }
        }
    }

    private fun drawAlignment(cx: Int, cy: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) setFunction(cx + dx, cy + dy, maxOf(abs(dx), abs(dy)) != 1)
        }
    }

    /** 15 bits: level and mask, 10 BCH(15, 5) bits (generator 0x537), XOR 0x5412 so that they are never all light. */
    fun drawFormatBits(ecc: QrEcc, mask: Int) {
        val data = (ecc.formatBits shl 3) or mask
        var remainder = data
        repeat(10) { remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537) }
        val bits = ((data shl 10) or remainder) xor 0x5412
        fun bit(i: Int) = (bits ushr i) and 1 != 0

        // First copy around the top-left finder: down column 8 (skipping the timing row), then left along row 8.
        for (i in 0..5) setFunction(8, i, bit(i))
        setFunction(8, 7, bit(6))
        setFunction(8, 8, bit(7))
        setFunction(7, 8, bit(8))
        for (i in 9..14) setFunction(14 - i, 8, bit(i))
        // Second copy: bits 0..7 along row 8 from the right edge, bits 8..14 down column 8 to the bottom edge.
        for (i in 0..7) setFunction(size - 1 - i, 8, bit(i))
        for (i in 8..14) setFunction(8, size - 15 + i, bit(i))
        setFunction(8, size - 8, true) // the dark module
    }

    /** Version 7 and up: 6 version bits and 12 Golay(18, 6) bits (generator 0x1F25), next to both upper finders. */
    private fun drawVersion() {
        if (version < 7) return
        var remainder = version
        repeat(12) { remainder = (remainder shl 1) xor ((remainder ushr 11) * 0x1F25) }
        val bits = (version shl 12) or remainder
        for (i in 0 until 18) {
            val dark = (bits ushr i) and 1 != 0
            val a = size - 11 + i % 3
            val b = i / 3
            setFunction(a, b, dark) // 3×6 block left of the top-right finder
            setFunction(b, a, dark) // 6×3 block above the bottom-left finder
        }
    }

    /**
     * Places the codewords, most significant bit first, in two-module-wide columns from the right edge, going up and
     * down in turn and skipping function modules and the vertical timing column. Leftover remainder bits stay light.
     */
    fun drawCodewords(codewords: ByteArray) {
        var bit = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            val upward = ((right + 1) and 2) == 0
            for (vertical in 0 until size) {
                val y = if (upward) size - 1 - vertical else vertical
                for (x in right downTo right - 1) {
                    val index = y * size + x
                    if (!isFunction[index] && bit < codewords.size * 8) {
                        modules[index] = (codewords[bit ushr 3].toInt() ushr (7 - (bit and 7))) and 1 != 0
                        bit++
                    }
                }
            }
            right -= 2
        }
    }

    /** XORs the mask into the data modules; applying it twice undoes it. */
    fun applyMask(mask: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                val index = y * size + x
                if (!isFunction[index] && maskFlips(mask, x, y)) modules[index] = !modules[index]
            }
        }
    }

    /** Penalty score of the finished symbol with [mask] and its format information. */
    fun penaltyWith(ecc: QrEcc, mask: Int): Int {
        applyMask(mask)
        drawFormatBits(ecc, mask)
        val result = penalty()
        applyMask(mask)
        return result
    }

    /** The four penalty rules of ISO/IEC 18004 (section 7.8.3), counted as ZXing counts them. */
    private fun penalty(): Int {
        var result = 0
        for (i in 0 until size) {
            result += linePenalty(BooleanArray(size) { modules[i * size + it] }) // row i
            result += linePenalty(BooleanArray(size) { modules[it * size + i] }) // column i
        }
        // N2: 3 per 2×2 block of one color (overlapping blocks count separately).
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val color = modules[y * size + x]
                if (color == modules[y * size + x + 1] &&
                    color == modules[(y + 1) * size + x] &&
                    color == modules[(y + 1) * size + x + 1]
                ) {
                    result += 3
                }
            }
        }
        // N4: 10 per full 5 % by which the share of dark modules is off 50 %.
        val total = size * size
        result += abs(modules.count { it } * 2 - total) * 10 / total * 10
        return result
    }

    /** N1 and N3 of one row or column. */
    private fun linePenalty(line: BooleanArray): Int {
        var result = 0
        // N1: 3 per run of 5 modules of one color, plus 1 per module beyond 5.
        var run = 1
        for (i in 1..line.size) {
            if (i < line.size && line[i] == line[i - 1]) {
                run++
            } else {
                if (run >= 5) result += run - 2
                run = 1
            }
        }
        // N3: 40 per dark-light-dark-dark-dark-light-dark (1:1:3:1:1, like a finder) with 4 light modules before or
        // after it. As in ZXing, those 4 must lie inside the symbol: the quiet zone does not count.
        fun light(from: Int, to: Int) = from >= 0 && to <= line.size && (from until to).none { line[it] }
        for (i in 0..line.size - 7) {
            val finderLike = line[i] && !line[i + 1] && line[i + 2] && line[i + 3] && line[i + 4] &&
                !line[i + 5] && line[i + 6]
            if (finderLike && (light(i - 4, i) || light(i + 7, i + 11))) result += 40
        }
        return result
    }
}

// Standard tables (ISO/IEC 18004, table 9), rows LOW, MEDIUM, QUARTILE, HIGH, columns versions 1..40.

/** Error-correction codewords in each block. */
private val ECC_CODEWORDS_PER_BLOCK: Array<IntArray> = arrayOf(
    intArrayOf(
        7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28,
        28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
    ),
    intArrayOf(
        10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26,
        26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
    ),
    intArrayOf(
        13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30,
        28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
    ),
    intArrayOf(
        17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28,
        30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
    ),
)

/** Number of error-correction blocks. */
private val ECC_BLOCK_COUNT: Array<IntArray> = arrayOf(
    intArrayOf(
        1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8,
        8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25,
    ),
    intArrayOf(
        1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16,
        17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49,
    ),
    intArrayOf(
        1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20,
        23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68,
    ),
    intArrayOf(
        1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25,
        25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81,
    ),
)
