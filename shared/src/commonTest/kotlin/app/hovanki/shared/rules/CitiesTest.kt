package app.hovanki.shared.rules

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CitiesTest {
    @Test
    fun idsAreUniqueLowercaseSlugs() {
        assertEquals(Cities.IDS.size, Cities.IDS.toSet().size)
        Cities.IDS.forEach { assertTrue(Regex("[a-z]+(_[a-z]+)*").matches(it), it) }
    }

    @Test
    fun onlyListedIdsAreKnown() {
        assertTrue(Cities.isKnown("kyiv"))
        assertFalse(Cities.isKnown("Kyiv"))
        assertFalse(Cities.isKnown(""))
        assertFalse(Cities.isKnown("moscow"))
    }

    @Test
    fun thePlayerIsInTheNearestCityAround() {
        assertEquals("kyiv", Cities.at(GeoPoint(50.4547, 30.5238)), "Maidan")
        assertEquals("kyiv", Cities.at(GeoPoint(50.5110, 30.7900)), "Brovary counts as Kyiv")
        assertEquals("lviv", Cities.at(GeoPoint(49.8419, 24.0315)))
        assertEquals("bila_tserkva", Cities.at(GeoPoint(49.8000, 30.1200)))
        assertNull(Cities.at(GeoPoint(50.0000, 31.5000)), "fields between cities")
        assertNull(Cities.at(GeoPoint(52.2297, 21.0122)), "Warsaw is not on the list")
    }

    @Test
    fun noTwoCitiesOverlap() {
        for (a in Cities.ALL) {
            for (b in Cities.ALL) {
                if (a.id < b.id) {
                    assertTrue(a.center.distanceTo(b.center) > a.radiusMeters + b.radiusMeters, "${a.id} and ${b.id}")
                }
            }
        }
    }
}
