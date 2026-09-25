package app.hovanki.shared.totp

/**
 * SHA-1 (FIPS 180-4) in pure Kotlin, so TOTP works the same on JVM, Android and iOS without platform crypto.
 * Only used for HMAC in TOTP (RFC 6238), where SHA-1 is still the standard choice.
 */
internal object Sha1 {
    fun digest(message: ByteArray): ByteArray {
        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()

        // Padding: 0x80, zeros, then the message length in bits as a 64-bit big-endian number.
        val paddedLength = ((message.size + 8) / 64 + 1) * 64
        val padded = message.copyOf(paddedLength)
        padded[message.size] = 0x80.toByte()
        val bitLength = message.size.toLong() * 8
        for (i in 0 until 8) {
            padded[paddedLength - 1 - i] = (bitLength ushr (8 * i)).toByte()
        }

        val w = IntArray(80)
        for (chunk in 0 until paddedLength step 64) {
            for (i in 0 until 16) {
                val j = chunk + i * 4
                w[i] = (padded[j].toInt() and 0xff shl 24) or
                    (padded[j + 1].toInt() and 0xff shl 16) or
                    (padded[j + 2].toInt() and 0xff shl 8) or
                    (padded[j + 3].toInt() and 0xff)
            }
            for (i in 16 until 80) {
                w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
            }

            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            for (i in 0 until 80) {
                val f: Int
                val k: Int
                when {
                    i < 20 -> {
                        f = (b and c) or (b.inv() and d)
                        k = 0x5A827999
                    }

                    i < 40 -> {
                        f = b xor c xor d
                        k = 0x6ED9EBA1
                    }

                    i < 60 -> {
                        f = (b and c) or (b and d) or (c and d)
                        k = 0x8F1BBCDC.toInt()
                    }

                    else -> {
                        f = b xor c xor d
                        k = 0xCA62C1D6.toInt()
                    }
                }
                val temp = a.rotateLeft(5) + f + e + k + w[i]
                e = d
                d = c
                c = b.rotateLeft(30)
                b = a
                a = temp
            }
            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
        }

        val out = ByteArray(20)
        intArrayOf(h0, h1, h2, h3, h4).forEachIndexed { index, h ->
            for (j in 0 until 4) {
                out[index * 4 + j] = (h ushr (24 - 8 * j)).toByte()
            }
        }
        return out
    }
}

/** HMAC-SHA1 (RFC 2104). */
internal fun hmacSha1(key: ByteArray, message: ByteArray): ByteArray {
    val blockSize = 64
    val normalizedKey = (if (key.size > blockSize) Sha1.digest(key) else key).copyOf(blockSize)
    val innerPad = ByteArray(blockSize) { (normalizedKey[it].toInt() xor 0x36).toByte() }
    val outerPad = ByteArray(blockSize) { (normalizedKey[it].toInt() xor 0x5c).toByte() }
    return Sha1.digest(outerPad + Sha1.digest(innerPad + message))
}
