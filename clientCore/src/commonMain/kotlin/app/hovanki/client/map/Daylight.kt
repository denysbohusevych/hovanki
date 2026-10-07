package app.hovanki.client.map

import app.hovanki.shared.protocol.GeoPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Whether the sun is up, for the map's [MapTheme.AUTO]: the sun's position by the Astronomical Almanac's low-precision
 * formulas, good to a minute or two of the real sunset, which is plenty for choosing colors. The phone's own clock is
 * enough here: nothing of the game depends on it.
 */
object Daylight {
    /** The sun's center this far under the horizon is sunset: its radius and the air's refraction. */
    private const val SUNSET_ELEVATION_DEGREES = -0.833

    /** Between sunset and sunrise at [point]. */
    fun isNight(point: GeoPoint, epochMillis: Long): Boolean =
        sunElevationDegrees(point, epochMillis) < SUNSET_ELEVATION_DEGREES

    /** How high the sun's center is over the horizon at [point], in degrees; negative under it. */
    fun sunElevationDegrees(point: GeoPoint, epochMillis: Long): Double {
        // Days since the J2000 epoch, 2000-01-01 12:00 UTC.
        val days = epochMillis / MILLIS_PER_DAY + UNIX_EPOCH_JULIAN_DAY - J2000_JULIAN_DAY
        val meanLongitude = (280.460 + 0.9856474 * days).mod(360.0)
        val meanAnomaly = radians((357.528 + 0.9856003 * days).mod(360.0))
        val eclipticLongitude =
            radians(meanLongitude + 1.915 * sin(meanAnomaly) + 0.020 * sin(2 * meanAnomaly))
        val obliquity = radians(23.439 - 0.0000004 * days)
        val rightAscension = atan2(cos(obliquity) * sin(eclipticLongitude), cos(eclipticLongitude))
        val declination = asin(sin(obliquity) * sin(eclipticLongitude))
        val siderealDegrees = (280.46061837 + 360.98564736629 * days).mod(360.0)
        val hourAngle = radians(siderealDegrees + point.lon) - rightAscension
        val latitude = radians(point.lat)
        val elevation = asin(
            sin(latitude) * sin(declination) + cos(latitude) * cos(declination) * cos(hourAngle),
        )
        return elevation * 180.0 / PI
    }

    private fun radians(degrees: Double): Double = degrees * PI / 180.0

    private const val MILLIS_PER_DAY = 86_400_000.0
    private const val UNIX_EPOCH_JULIAN_DAY = 2_440_587.5
    private const val J2000_JULIAN_DAY = 2_451_545.0
}
