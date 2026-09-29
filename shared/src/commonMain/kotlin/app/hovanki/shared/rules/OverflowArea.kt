package app.hovanki.shared.rules

/**
 * The iOS «overflow area» (docs/adr/0016-iphone-overflow-radar.md): an app advertising in the background loses its
 * service UUIDs from the air; each becomes one bit of a 128-bit mask in Apple's manufacturer data
 * (`4C 00 01 <16 bytes>`). The hash from UUID to bit is Apple's and unpublished, but [UUID_SUFFIXES] names one UUID for
 * every bit: advertise those UUIDs and exactly those bits are set.
 *
 * The table comes from David G. Young's OverflowAreaBeaconRef (`OverflowAreaUtils.swift`,
 * https://github.com/davidgyoung/OverflowAreaBeaconRef, Apache License 2.0; see NOTICE). The radio lab confirmed it on
 * iOS 26.2.1 (2026-09-29, docs/radio-lab.md «Как прошло»): the table's positions are right, the bytes come in order,
 * and within a byte the air puts the highest bit first ([bitsOf]). Only the lab uses it so far.
 */
object OverflowArea {
    const val BITS = 128
    const val MASK_BYTES = BITS / 8

    /**
     * Set, the bit of the Apple Watch's set-up service (9aa4730f-b25c-4cc3-b821-c931559fc196): an iPhone or a watch a
     * few centimeters away offers to set up a watch. Never advertised.
     */
    const val APPLE_WATCH_BIT = 69

    /**
     * The bit our game's service UUID (`7A0B8D2E-…`) hashes to: a locked iPhone advertising the service alone (a hider)
     * shows only this bit (measured by the radio lab, E1b). No layout uses it for anything else.
     */
    const val OUR_SERVICE_BIT = 117

    /** The last byte of the UUID `00000000-0000-0000-0000-0000000000XX` that sets bit N, at index N. */
    private val UUID_SUFFIXES = intArrayOf(
        0x7C, 0x37, 0x6E, 0x25, 0x59, 0x12, 0x4B, 0x00, 0x36, 0x7D, 0x24, 0x6F, 0x13, 0x58, 0x01, 0x4A,
        0x6C, 0x27, 0x7E, 0x35, 0x49, 0x02, 0x5B, 0x10, 0x26, 0x6D, 0x34, 0x7F, 0x03, 0x48, 0x11, 0x5A,
        0x5D, 0x16, 0x4F, 0x04, 0x78, 0x33, 0x6A, 0x21, 0x17, 0x5C, 0x05, 0x4E, 0x32, 0x79, 0x20, 0x6B,
        0x4D, 0x06, 0x5F, 0x14, 0x68, 0x23, 0x7A, 0x31, 0x07, 0x4C, 0x15, 0x5E, 0x22, 0x69, 0x30, 0x7B,
        0x3E, 0x75, 0x2C, 0x67, 0x1B, 0x50, 0x09, 0x42, 0x74, 0x3F, 0x66, 0x2D, 0x51, 0x1A, 0x43, 0x08,
        0x2E, 0x65, 0x3C, 0x77, 0x0B, 0x40, 0x19, 0x52, 0x64, 0x2F, 0x76, 0x3D, 0x41, 0x0A, 0x53, 0x18,
        0x1F, 0x54, 0x0D, 0x46, 0x3A, 0x71, 0x28, 0x63, 0x55, 0x1E, 0x47, 0x0C, 0x70, 0x3B, 0x62, 0x29,
        0x0F, 0x44, 0x1D, 0x56, 0x2A, 0x61, 0x38, 0x73, 0x45, 0x0E, 0x57, 0x1C, 0x60, 0x2B, 0x72, 0x39,
    )

    /** The UUID of every bit, index = bit, upper case as CoreBluetooth prints them. */
    val UUIDS: List<String> = UUID_SUFFIXES.map { "00000000-0000-0000-0000-0000000000" + hexByte(it) }

    private val bitByUuid: Map<String, Int> = UUIDS.withIndex().associate { (bit, uuid) -> uuid to bit }

    fun uuid(bit: Int): String = UUIDS[bit]

    /** The bit [uuid] sets; null: not one of the table's. Any case; a 16-bit short form ("7C") too. */
    fun bitOf(uuid: String): Int? {
        val upper = uuid.uppercase()
        return bitByUuid[upper] ?: bitByUuid["00000000-0000-0000-0000-0000000000" + upper.padStart(2, '0')]
    }

    /**
     * The bits set in a [MASK_BYTES]-byte mask as it comes on the air: bit N is byte N / 8, value `0x80 >> (N % 8)`,
     * the highest bit first. Measured by the radio lab (E1b): the table's bit 0 comes as `80 00…`, bit 7 as `01 00…`,
     * bit 8 as `00 80…`. Young's code numbers the bits of a byte the other way round; that is his own bookkeeping,
     * not the air's.
     */
    fun bitsOf(mask: ByteArray): Set<Int> = buildSet {
        for (bit in 0 until minOf(BITS, mask.size * 8)) {
            if (mask[bit / 8].toInt() and (0x80 shr (bit % 8)) != 0) add(bit)
        }
    }

    /** The mask with [bits] set, as on the air ([bitsOf]'s order). */
    fun maskOf(bits: Collection<Int>): ByteArray {
        val mask = ByteArray(MASK_BYTES)
        for (bit in bits) {
            require(bit in 0 until BITS) { "bit $bit" }
            mask[bit / 8] = (mask[bit / 8].toInt() or (0x80 shr (bit % 8))).toByte()
        }
        return mask
    }

    private fun hexByte(value: Int): String = value.toString(16).uppercase().padStart(2, '0')
}

/**
 * Apple's manufacturer data (company id 0x004C already taken off): a row of frames, a type byte and then, for most
 * types, a length byte. The overflow area (type 0x01) has no length: 16 bytes of mask follow. iBeacon is type 0x02
 * with length 0x15.
 */
object AppleData {
    const val OVERFLOW = 0x01
    const val IBEACON = 0x02
    private const val IBEACON_LENGTH = 0x15

    /** The overflow area's mask in [data]; null: none. */
    fun overflowMask(data: ByteArray): ByteArray? {
        var at = 0
        while (at < data.size) {
            val type = data[at].toInt() and 0xff
            if (type == OVERFLOW) {
                return if (data.size - at - 1 >= OverflowArea.MASK_BYTES) {
                    data.copyOfRange(at + 1, at + 1 + OverflowArea.MASK_BYTES)
                } else {
                    null
                }
            }
            if (at + 1 >= data.size) return null
            at += 2 + (data[at + 1].toInt() and 0xff)
        }
        return null
    }

    /** The iBeacon frame in [data]: its proximity UUID (lower-case hex, no dashes), major and minor; null: none. */
    fun iBeacon(data: ByteArray): IBeaconFrame? {
        if (data.size < 2 + IBEACON_LENGTH) return null
        if (data[0].toInt() and 0xff != IBEACON || data[1].toInt() and 0xff != IBEACON_LENGTH) return null
        val uuid = data.copyOfRange(2, 18).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val major = ((data[18].toInt() and 0xff) shl 8) or (data[19].toInt() and 0xff)
        val minor = ((data[20].toInt() and 0xff) shl 8) or (data[21].toInt() and 0xff)
        return IBeaconFrame(uuid, major, minor)
    }
}

data class IBeaconFrame(val uuidHex: String, val major: Int, val minor: Int)

/**
 * Which bits of the mask carry what (docs/adr/0016-iphone-overflow-radar.md, section 2.1): [markerPairs] (4 pairs) and
 * [tokenPairs] (32 pairs, the token's highest bit first). A pair is (p, q): 1 sets p, 0 sets q.
 */
class OverflowLayout(val markerPairs: List<Pair<Int, Int>>, val tokenPairs: List<Pair<Int, Int>>) {
    init {
        require(markerPairs.size == OverflowCode.MARKER.size && tokenPairs.size == RadarToken.LENGTH * 4)
        val all = (markerPairs + tokenPairs).flatMap { listOf(it.first, it.second) }
        require(all.distinct().size == all.size)
        require(OverflowArea.APPLE_WATCH_BIT !in all && OverflowArea.OUR_SERVICE_BIT !in all)
    }

    companion object {
        /**
         * Provisional, for the radio lab only: the first 72 positions but [OverflowArea.APPLE_WATCH_BIT], in pairs of
         * neighbours ([OverflowArea.OUR_SERVICE_BIT] is beyond them). The layout of the game (V1) also leaves out the
         * city's busy bits, which the street's survey tells (docs/radio-lab.md, E8).
         */
        val LAB: OverflowLayout = run {
            val positions = (0 until OverflowArea.BITS).filter { it != OverflowArea.APPLE_WATCH_BIT }.take(72)
            val pairs = positions.chunked(2).map { it[0] to it[1] }
            OverflowLayout(pairs.take(OverflowCode.MARKER.size), pairs.drop(OverflowCode.MARKER.size))
        }
    }
}

/**
 * A radar token in the overflow mask, Manchester-coded (docs/adr/0016-iphone-overflow-radar.md, sections 2.1–2.3):
 * every bit is a pair with exactly one of its two positions set. Other apps only ever add ones, so a damaged pair
 * reads `11` (either value), and `00` means the mask is not ours.
 */
object OverflowCode {
    /** The marker and layout version, «1010»: version 1. */
    val MARKER: List<Boolean> = listOf(true, false, true, false)

    /** Damaged token pairs beyond this: too noisy to guess, nothing decoded. */
    const val MAX_AMBIGUOUS_PAIRS = 2

    /** The bits that carry [token] (8 hex characters) in [layout]. */
    fun encode(token: String, layout: OverflowLayout = OverflowLayout.LAB): Set<Int> {
        require(RadarToken.isWellFormed(token)) { "token" }
        val value = token.toLong(16)
        val tokenBits = (0 until RadarToken.LENGTH * 4).map { value shr (RadarToken.LENGTH * 4 - 1 - it) and 1L == 1L }
        return buildSet {
            for ((pair, bit) in layout.markerPairs.zip(MARKER)) add(if (bit) pair.first else pair.second)
            for ((pair, bit) in layout.tokenPairs.zip(tokenBits)) add(if (bit) pair.first else pair.second)
        }
    }

    /**
     * The tokens [bits] may carry: none when the mask is not ours (a `00` pair, another marker) or too damaged, one
     * when clean, 2 or 4 when [MAX_AMBIGUOUS_PAIRS] pairs or fewer read `11`.
     */
    fun decode(bits: Set<Int>, layout: OverflowLayout = OverflowLayout.LAB): List<String> {
        fun read(pair: Pair<Int, Int>): Boolean? {
            val p = pair.first in bits
            val q = pair.second in bits
            return when {
                p && q -> null
                p -> true
                q -> false
                else -> throw NotOurs()
            }
        }
        return try {
            val marker = layout.markerPairs.map(::read)
            if (marker.zip(MARKER).any { (read, expected) -> read != null && read != expected }) return emptyList()
            val token = layout.tokenPairs.map(::read)
            val ambiguous = token.withIndex().filter { it.value == null }.map { it.index }
            if (ambiguous.size > MAX_AMBIGUOUS_PAIRS) return emptyList()
            (0 until (1 shl ambiguous.size)).map { choice ->
                var value = 0L
                for ((index, bit) in token.withIndex()) {
                    val one = bit ?: (choice shr ambiguous.indexOf(index) and 1 == 1)
                    value = value shl 1 or (if (one) 1L else 0L)
                }
                value.toString(16).padStart(RadarToken.LENGTH, '0')
            }
        } catch (_: NotOurs) {
            emptyList()
        }
    }

    private class NotOurs : Exception()
}

/** What the lab's overflow probe advertises besides a token (docs/radio-lab.md §5). */
object OverflowProbe {
    /** `0x5A 0x5A` in the first 16 bits: easy to tell by eye in a hex dump (the same either bit order). */
    val PATTERN: Set<Int> = OverflowArea.bitsOf(byteArrayOf(0x5A, 0x5A))

    /** Only bit [bit]: to check the table's position against the air's. */
    fun single(bit: Int): Set<Int> {
        require(bit in 0 until OverflowArea.BITS && bit != OverflowArea.APPLE_WATCH_BIT) { "bit $bit" }
        return setOf(bit)
    }
}
