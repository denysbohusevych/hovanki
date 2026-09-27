package app.hovanki.shared.rules

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestIdsTest {
    @Test
    fun longRandomIdsOnly() {
        assertTrue(RequestIds.isValid("0f8fad5b-d9cb-469f-a165-70867728950e"))
        assertTrue(RequestIds.isValid("0f8fad5bd9cb469fa16570867728950e"))
        assertTrue(RequestIds.isValid("x".repeat(RequestIds.MAX_LENGTH)))
        // Guessable, too long, or not an id at all.
        assertFalse(RequestIds.isValid("1"))
        assertFalse(RequestIds.isValid("x".repeat(RequestIds.MIN_LENGTH - 1)))
        assertFalse(RequestIds.isValid("x".repeat(RequestIds.MAX_LENGTH + 1)))
        assertFalse(RequestIds.isValid("0f8fad5b d9cb 469f a165"))
        assertFalse(RequestIds.isValid("0f8fad5b/d9cb/469f/a165"))
    }
}
