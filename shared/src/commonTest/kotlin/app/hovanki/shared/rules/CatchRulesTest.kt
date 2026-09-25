package app.hovanki.shared.rules

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatchRulesTest {
    private val origin = GeoPoint(50.4501, 30.5234)

    private fun fix(eastMeters: Double, accuracy: Double = 5.0) =
        LocationSample(origin.moveBy(eastMeters, 0.0), accuracy, timestampMillis = 0)

    @Test
    fun closestPairWins() {
        // One jumpy fix far away must not hide that the players were close.
        val seeker = listOf(fix(0.0), fix(80.0))
        val hider = listOf(fix(30.0))

        assertEquals(20.0, CatchRules.closestPossibleDistanceMeters(seeker, hider)!!, 0.5)
        assertEquals(30.0, CatchRules.estimatedDistanceMeters(seeker, hider)!!, 0.5)
    }

    @Test
    fun accuracyNeverMakesTheDistanceNegative() {
        assertEquals(0.0, CatchRules.minPossibleDistanceMeters(fix(0.0, accuracy = 20.0), fix(10.0, accuracy = 20.0)))
    }

    @Test
    fun unknownWithoutFixes() {
        assertNull(CatchRules.closestPossibleDistanceMeters(listOf(fix(0.0)), emptyList()))
    }
}
