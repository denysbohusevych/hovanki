package app.hovanki.radar

/** The game's own identifiers on the air (docs/adr/0012-nearby-radar.md, section 2.2). */
object RadarService {
    /** The game's 128-bit UUID: the service of a hider's frame and the proximity UUID of a seeker's iBeacon. */
    const val UUID = "7A0B8D2E-4C1F-4E6A-9B3D-2F5E8C1A7D10"

    /**
     * An iPhone hider on the screen carries its token as its name; the first apps put this before it, which iOS cut
     * off with the token's end, so it is only still read.
     */
    const val NAME_PREFIX = "hv"

    /** Apple's company id in manufacturer data: iBeacon frames and the overflow area live there. */
    const val APPLE_COMPANY_ID = 0x004C

    /** The measured power of the seekers' iBeacon: -59 dBm a metre away, a typical phone. */
    const val MEASURED_POWER = -59
}

/**
 * Bluetooth UUIDs as strings, the way [AirFrame] and [AdPart] carry them: upper case; 128-bit with dashes; 16- and
 * 32-bit ones (on the Bluetooth base UUID) short, as 4 or 8 hex digits.
 */
object BleUuid {
    private const val BASE_SUFFIX = "-0000-1000-8000-00805F9B34FB"

    fun normalize(uuid: String): String {
        val upper = uuid.trim().uppercase()
        if (upper.length == 36 && upper.endsWith(BASE_SUFFIX)) {
            val head = upper.substring(0, 8)
            return if (head.startsWith("0000")) head.substring(4) else head
        }
        return upper
    }

    /** How many bytes [uuid] takes on the air: 2, 4 or 16. */
    fun size(uuid: String): Int = when (normalize(uuid).length) {
        4 -> 2
        8 -> 4
        else -> 16
    }

    /** [uuid]'s bytes, most significant first (the iBeacon frame's order; the advertisement's fields reverse them). */
    fun bytes(uuid: String): ByteArray {
        val hex = normalize(uuid).replace("-", "")
        require(hex.length in setOf(4, 8, 32) && hex.all { it in '0'..'9' || it in 'A'..'F' }) { "uuid $uuid" }
        return ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    /** The UUID of [bytes] (2, 4 or 16, most significant first), normalized. */
    fun of(bytes: ByteArray): String {
        val hex = bytes.toHex().uppercase()
        return when (bytes.size) {
            2, 4 -> hex

            16 -> normalize(
                "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                    "${hex.substring(16, 20)}-${hex.substring(20)}",
            )

            else -> throw IllegalArgumentException("${bytes.size} bytes")
        }
    }

    /** [uuid] in lower-case hex without dashes, as `IBeaconFrame.uuidHex`. */
    fun hex(uuid: String): String = bytes(uuid).toHex()
}
