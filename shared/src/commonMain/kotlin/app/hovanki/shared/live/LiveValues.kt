package app.hovanki.shared.live

import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.totp.hexToBytes
import app.hovanki.shared.totp.toHex
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** How the values of v1 travel in v2 (docs/live-v2/spec.md §10), both ways. */
object LiveValues {
    private const val E7 = 10_000_000.0

    /** Degrees as E7: × 10^7, rounded half away from zero. */
    fun toE7(degrees: Double): Int {
        val scaled = degrees * E7
        val rounded = floor(abs(scaled) + 0.5)
        return (if (scaled < 0) -rounded else rounded).toInt()
    }

    fun fromE7(e7: Int): Double = e7 / E7

    /** A point as it comes out of v2: what v1 sends, rounded to E7. */
    fun round(point: GeoPoint): GeoPoint = GeoPoint(fromE7(toE7(point.lat)), fromE7(toE7(point.lon)))

    /**
     * Accuracy in metres as decimetres, rounded up: a fix never looks more accurate than it was. The tiny slack keeps
     * a value that is already whole decimetres (4.2 m is 42.00000000000001 dm in doubles) from going one up.
     */
    fun toDecimetres(metres: Double): Int {
        if (!(metres > 0.0)) return 0
        val decimetres = metres * 10.0
        return ceil(decimetres - 1e-6).toInt().coerceAtLeast(0)
    }

    fun fromDecimetres(decimetres: Int): Double = decimetres / 10.0

    /** An accuracy as it comes out of v2. */
    fun roundAccuracy(metres: Double): Double = fromDecimetres(toDecimetres(metres))

    /** A radar token (8 lowercase hex characters) as the 4 bytes of a `fixed32`, big-endian; null: not a token. */
    fun tokenToInt(token: String): Int? {
        if (token.length != TOKEN_CHARS) return null
        var value = 0L
        for (char in token) {
            val digit =
                when (char) {
                    in '0'..'9' -> char - '0'
                    in 'a'..'f' -> char - 'a' + 10
                    else -> return null
                }
            value = (value shl 4) or digit.toLong()
        }
        return value.toInt()
    }

    fun tokenFromInt(value: Int): String = (value.toLong() and 0xffffffffL).toString(16).padStart(TOKEN_CHARS, '0')

    /** A hex secret of v1 as bytes; null or empty: empty bytes. */
    fun secretToBytes(hex: String?): ByteString =
        if (hex.isNullOrEmpty()) ByteString.EMPTY else hex.hexToBytes().toByteString()

    /** Bytes back as v1's lowercase hex; empty: null. */
    fun secretFromBytes(bytes: ByteString): String? = if (bytes.size == 0) null else bytes.toByteArray().toHex()

    /** v1's nullable string: empty means null. */
    fun orEmpty(value: String?): String = value ?: ""

    fun nullIfEmpty(value: String): String? = value.ifEmpty { null }

    private const val TOKEN_CHARS = 8
}
