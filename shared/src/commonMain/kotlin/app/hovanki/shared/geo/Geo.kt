package app.hovanki.shared.geo

import app.hovanki.shared.protocol.GeoPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Mean Earth radius (IUGG), meters. */
const val EARTH_RADIUS_METERS = 6_371_008.8

/** Great-circle (haversine) distance in meters. */
fun GeoPoint.distanceTo(other: GeoPoint): Double {
    val lat1 = lat.toRadians()
    val lat2 = other.lat.toRadians()
    val dLat = lat2 - lat1
    val dLon = (other.lon - lon).toRadians()
    val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
}

/** Offset from an origin on a local flat projection (fine for a game area of a few kilometers). */
data class LocalOffset(val eastMeters: Double, val northMeters: Double)

fun GeoPoint.offsetFrom(origin: GeoPoint): LocalOffset =
    LocalOffset(
        eastMeters = (lon - origin.lon).toRadians() * EARTH_RADIUS_METERS * cos(origin.lat.toRadians()),
        northMeters = (lat - origin.lat).toRadians() * EARTH_RADIUS_METERS,
    )

fun GeoPoint.moveBy(eastMeters: Double, northMeters: Double): GeoPoint =
    GeoPoint(
        lat = lat + (northMeters / EARTH_RADIUS_METERS).toDegrees(),
        lon = lon + (eastMeters / (EARTH_RADIUS_METERS * cos(lat.toRadians()))).toDegrees(),
    )

internal fun Double.toRadians(): Double = this * PI / 180.0

internal fun Double.toDegrees(): Double = this * 180.0 / PI
