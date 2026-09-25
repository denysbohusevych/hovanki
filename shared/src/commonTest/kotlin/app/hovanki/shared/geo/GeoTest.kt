package app.hovanki.shared.geo

import app.hovanki.shared.protocol.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class GeoTest {
    private val kyiv = GeoPoint(50.4501, 30.5234)

    @Test
    fun oneDegreeOfLatitude() {
        assertClose(111_195.0, GeoPoint(0.0, 0.0).distanceTo(GeoPoint(1.0, 0.0)), tolerance = 1.0)
    }

    @Test
    fun moveByAndOffsetAreConsistent() {
        val moved = kyiv.moveBy(eastMeters = 300.0, northMeters = -200.0)
        val offset = moved.offsetFrom(kyiv)
        assertClose(300.0, offset.eastMeters, tolerance = 0.5)
        assertClose(-200.0, offset.northMeters, tolerance = 0.5)
        assertClose(360.55, kyiv.distanceTo(moved), tolerance = 0.5)
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected ± $tolerance but was $actual")
    }
}
