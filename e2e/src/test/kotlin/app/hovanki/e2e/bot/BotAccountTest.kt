package app.hovanki.e2e.bot

import app.hovanki.shared.rules.AccountRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BotAccountTest {
    @Test
    fun uniqueValidNicknamesAndEmails() {
        val accounts = listOf("Anna", "Anna", "G3-P30", "Анна", "Maximilian Alexander", "", "-").map(BotAccount::unique)

        assertEquals(accounts.size, accounts.map { AccountRules.nicknameKey(it.nickname) }.toSet().size)
        assertEquals(accounts.size, accounts.map { AccountRules.emailKey(it.email) }.toSet().size)
        assertTrue(accounts.all { AccountRules.isValidNickname(it.nickname) }, accounts.toString())
        assertTrue(accounts.all { AccountRules.isValidEmail(it.email) }, accounts.toString())
        assertTrue(accounts.all { AccountRules.isValidPassword(it.password) })
        assertTrue(accounts[0].nickname.startsWith("Anna_"))
        assertTrue(accounts[2].nickname.startsWith("G3P30_"))
    }
}
