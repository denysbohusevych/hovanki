package app.hovanki.shared.totp

private const val HEX_DIGITS = "0123456789abcdef"

fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0f])
    }
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have an even length" }
    return ByteArray(length / 2) { i ->
        val high = HEX_DIGITS.indexOf(this[i * 2].lowercaseChar())
        val low = HEX_DIGITS.indexOf(this[i * 2 + 1].lowercaseChar())
        require(high >= 0 && low >= 0) { "Invalid hex string" }
        ((high shl 4) or low).toByte()
    }
}
