package app.hovanki.server.admin

/** RFC 4648 base32 without padding: how authenticator apps take a TOTP secret (`otpauth://…?secret=`). */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(bytes: ByteArray): String = buildString((bytes.size * 8 + 4) / 5) {
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                append(ALPHABET[(buffer shr (bits - 5)) and 0x1f])
                bits -= 5
            }
        }
        if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
    }
}
