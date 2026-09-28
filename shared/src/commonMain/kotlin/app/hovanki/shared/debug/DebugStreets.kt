package app.hovanki.shared.debug

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GeoPoint

/**
 * The test streets the server's fake street source lays around every zone center (Spring profile `e2e` and tests,
 * docs/adr/0009-game-setup-glow-streets.md): a grid of straight streets every [SPACING] meters, north–south and
 * east–west, half a spacing off the center, so the center is in the middle of a 100 × 100 m block and the test
 * quarter of [DebugBuildings] lies inside another block. Offsets are meters east / north of the zone center.
 */
object DebugStreets {
    const val SPACING = 100.0

    /** The streets closest to the center run at ±[FIRST] meters. */
    const val FIRST = 50.0

    /** Streets covering at least [radiusMeters] around [center]. */
    fun around(center: GeoPoint, radiusMeters: Double): List<List<GeoPoint>> {
        val count = (radiusMeters / SPACING).toInt() + 2
        val extent = count * SPACING
        val offsets = (-count until count).map { FIRST + it * SPACING }
        fun at(east: Double, north: Double) = center.moveBy(eastMeters = east, northMeters = north)
        val northSouth = offsets.map { east -> listOf(at(east, -extent), at(east, extent)) }
        val eastWest = offsets.map { north -> listOf(at(-extent, north), at(extent, north)) }
        return northSouth + eastWest
    }
}
