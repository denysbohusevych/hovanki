package app.hovanki.shared.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountRulesTest {
    @Test
    fun nicknamesOfAnyAlphabet() {
        for (nickname in listOf("denys", "Олена_7", "Їжак", "abc", "a.b-c_d", "7up", "x".repeat(20))) {
            assertTrue(AccountRules.isValidNickname(nickname), nickname)
        }
    }

    @Test
    fun badNicknames() {
        val bad = listOf("ab", "x".repeat(21), "_denys", ".x1", "-x1", "de nys", "den@ys", "emoji🙂", "tab\tx", "")
        for (nickname in bad) {
            assertFalse(AccountRules.isValidNickname(nickname), nickname)
        }
    }

    @Test
    fun nicknameKeysIgnoreCase() {
        assertEquals(AccountRules.nicknameKey("Denys"), AccountRules.nicknameKey("dENYS"))
        assertEquals(AccountRules.nicknameKey("Олена"), AccountRules.nicknameKey("ОЛЕНА"))
    }

    @Test
    fun emails() {
        for (email in listOf("d@example.com", " D.B+tag@Mail.Example.org ", "x@y.co")) {
            assertTrue(AccountRules.isValidEmail(email), email)
        }
        val bad = listOf(
            "",
            "denys",
            "@example.com",
            "d@example",
            "d@@example.com",
            "d@ex@ample.com",
            "d@.example.com",
            "d@example.com.",
            "d@exa..mple.com",
            "d e@example.com",
            "d@" + "x".repeat(250) + ".com",
        )
        for (email in bad) {
            assertFalse(AccountRules.isValidEmail(email), email)
        }
        assertEquals("D.B@Mail.com", AccountRules.normalizeEmail("  D.B@Mail.com "))
        assertEquals("d.b@mail.com", AccountRules.emailKey("  D.B@Mail.com "))
    }

    @Test
    fun passwords() {
        assertTrue(AccountRules.isValidPassword("12345678"))
        assertTrue(AccountRules.isValidPassword("x".repeat(64)))
        assertFalse(AccountRules.isValidPassword("1234567"))
        assertFalse(AccountRules.isValidPassword("x".repeat(65)))
        // 36 Cyrillic letters are 72 bytes of UTF-8, 37 are too many for BCrypt.
        assertTrue(AccountRules.isValidPassword("ж".repeat(36)))
        assertFalse(AccountRules.isValidPassword("ж".repeat(37)))
    }

    @Test
    fun codes() {
        assertTrue(AccountRules.isCodeFormat("012345"))
        assertTrue(AccountRules.isCodeFormat(" 012 345 "))
        assertEquals("012345", AccountRules.normalizeCode(" 012 345 "))
        assertFalse(AccountRules.isCodeFormat("12345"))
        assertFalse(AccountRules.isCodeFormat("12345a"))
    }

    @Test
    fun languages() {
        assertEquals("uk", AccountRules.language("uk-UA"))
        assertEquals("ru", AccountRules.language("RU"))
        assertEquals("en", AccountRules.language("de"))
        assertEquals("en", AccountRules.language(null))
    }
}
