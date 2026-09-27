package app.hovanki.shared.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupRulesTest {
    @Test
    fun names() {
        assertEquals("Park crew", GroupRules.normalizeName("  Park \n  crew "))
        assertTrue(GroupRules.isValidName("Двір 🙂"))
        assertTrue(GroupRules.isValidName("x".repeat(GroupRules.NAME_MAX_LENGTH)))
        assertFalse(GroupRules.isValidName("x".repeat(GroupRules.NAME_MAX_LENGTH + 1)))
        assertFalse(GroupRules.isValidName("   "))
        assertFalse(GroupRules.isValidName("a\u0000b"))
    }
}
