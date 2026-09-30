package app.hovanki.e2e.beacon

import app.hovanki.radar.SightingVia

/** A line the Bluetooth helper (`e2e/mac-beacon/beacon.swift`) prints. */
sealed interface HelperLine {
    /** `state on|off|denied|unsupported`. */
    data class State(val state: String) : HelperLine

    /** `heard <token> <rssi> <how> [<peer>]`: a radar token (older helpers print no peer). */
    data class Heard(val token: String, val rssi: Int, val how: String, val peer: String?) : HelperLine {
        val via: SightingVia
            get() = when (how) {
                "name" -> SightingVia.NAME
                "ibeacon" -> SightingVia.IBEACON
                "service-data" -> SightingVia.SERVICE_DATA
                else -> SightingVia.UNKNOWN
            }
    }

    /** `raw <hex> <rssi> <peer>`: Apple's manufacturer data after the company id, while sniffing. */
    data class Raw(val data: ByteArray, val rssi: Int, val peer: String?) : HelperLine {
        override fun equals(other: Any?): Boolean =
            other is Raw && data.contentEquals(other.data) && rssi == other.rssi && peer == other.peer

        override fun hashCode(): Int = data.contentHashCode() * 31 + rssi
    }

    /** `overflow <uuid,uuid,...> <rssi> <peer>`: the overflow area's UUIDs, when macOS lists them. */
    data class Overflow(val uuids: List<String>, val rssi: Int, val peer: String?) : HelperLine

    /** `log <text>`, or anything else. */
    data class Log(val text: String) : HelperLine

    companion object {
        fun parse(line: String): HelperLine {
            val parts = line.trim().split(' ')
            return when (parts.first()) {
                "state" -> State(parts.getOrNull(1) ?: "?")

                "heard" -> {
                    val rssi = parts.getOrNull(2)?.toIntOrNull() ?: return Log(line)
                    Heard(parts[1], rssi, parts.getOrNull(3) ?: "?", parts.getOrNull(4))
                }

                "raw" -> {
                    val data = parts.getOrNull(1)?.let(::bytesOf) ?: return Log(line)
                    val rssi = parts.getOrNull(2)?.toIntOrNull() ?: return Log(line)
                    Raw(data, rssi, parts.getOrNull(3))
                }

                "overflow" -> {
                    val rssi = parts.getOrNull(2)?.toIntOrNull() ?: return Log(line)
                    Overflow(parts[1].split(',').filter { it.isNotEmpty() }, rssi, parts.getOrNull(3))
                }

                "log" -> Log(line.removePrefix("log "))

                else -> Log(line)
            }
        }

        private fun bytesOf(hex: String): ByteArray? {
            if (hex.length % 2 != 0) return null
            return ByteArray(hex.length / 2) { i ->
                hex.substring(2 * i, 2 * i + 2).toIntOrNull(16)?.toByte() ?: return null
            }
        }
    }
}
