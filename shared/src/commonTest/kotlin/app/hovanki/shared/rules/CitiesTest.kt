package app.hovanki.shared.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
