package app.hovanki.e2e.cli

import app.hovanki.shared.protocol.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ParseLocationTest {
    @Test
    fun latitudeCommaLongitude() {
        assertEquals(GeoPoint(52.2297, 21.0122), parseLocation("52.2297,21.0122"))
        assertEquals(GeoPoint(-33.8568, 151.2153), parseLocation(" -33.8568 , 151.2153 "))
    }

    @Test
    fun anythingElseSaysWhatIsExpected() {
        for (text in listOf("52.2297", "52.2297;21.0122", "north,east", "95,10", "10,190")) {
            assertFailsWith<IllegalArgumentException>(text) { parseLocation(text) }
        }
    }
}
