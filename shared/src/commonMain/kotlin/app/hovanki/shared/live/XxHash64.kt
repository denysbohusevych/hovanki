package app.hovanki.shared.live

/**
 * xxHash64 (seed 0) of a byte range: the server's fingerprint of a section it sent (docs/live-v2/spec.md §8.2). Pure
 * Kotlin after the reference (github.com/Cyan4973/xxHash, XXH64); not a cryptographic hash.
 */
object XxHash64 {
    private const val P1 = -7046029288634856825L // 0x9E3779B185EBCA87
    private const val P2 = -4417276706812531889L // 0xC2B2AE3D27D4EB4F
    private const val P3 = 1609587929392839161L // 0x165667B19E3779F9
    private const val P4 = -8796714831421723037L // 0x85EBCA77C2B2AE63
    private const val P5 = 2870177450012600261L // 0x27D4EB2F165667C5

    fun hash(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Long {
        val end = offset + length
        var i = offset
        var h: Long
        if (length >= 32) {
            var v1 = P1 + P2
            var v2 = P2
            var v3 = 0L
            var v4 = -P1
            while (i <= end - 32) {
                v1 = round(v1, long(bytes, i))
                v2 = round(v2, long(bytes, i + 8))
                v3 = round(v3, long(bytes, i + 16))
                v4 = round(v4, long(bytes, i + 24))
                i += 32
            }
            h = v1.rotateLeft(1) + v2.rotateLeft(7) + v3.rotateLeft(12) + v4.rotateLeft(18)
            h = merge(h, v1)
            h = merge(h, v2)
            h = merge(h, v3)
            h = merge(h, v4)
        } else {
            h = P5
        }
        h += length.toLong()
        while (i <= end - 8) {
            h = h xor round(0L, long(bytes, i))
            h = h.rotateLeft(27) * P1 + P4
            i += 8
        }
        if (i <= end - 4) {
            h = h xor (int(bytes, i) * P1)
            h = h.rotateLeft(23) * P2 + P3
            i += 4
        }
        while (i < end) {
            h = h xor ((bytes[i].toLong() and 0xff) * P5)
            h = h.rotateLeft(11) * P1
            i++
        }
        h = h xor (h ushr 33)
        h *= P2
        h = h xor (h ushr 29)
        h *= P3
        h = h xor (h ushr 32)
        return h
    }

    private fun round(acc: Long, input: Long): Long = (acc + input * P2).rotateLeft(31) * P1

    private fun merge(acc: Long, value: Long): Long = (acc xor round(0L, value)) * P1 + P4

    private fun long(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (k in 7 downTo 0) value = (value shl 8) or (bytes[at + k].toLong() and 0xff)
        return value
    }

    private fun int(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (k in 3 downTo 0) value = (value shl 8) or (bytes[at + k].toLong() and 0xff)
        return value
    }
}
