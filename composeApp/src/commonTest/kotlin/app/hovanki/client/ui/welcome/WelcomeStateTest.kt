package app.hovanki.client.ui.welcome

import app.hovanki.client.account.AccountState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WelcomeStateTest {
    private fun state(local: WelcomeLocal = WelcomeLocal(), inputs: WelcomeInputs = WelcomeInputs()) =
        buildWelcomeState(inputs, local, buildLabel = "1.0 (1)")

    @Test
    fun theFormsAreCheckedAsTheServerChecksThem() {
        val valid = state(WelcomeLocal(nickname = " sam_k ", email = "sam@example.com", registerPassword = "secret123"))
        assertTrue(valid.isNicknameValid)
        assertTrue(valid.isEmailValid)
        assertTrue(valid.isRegisterPasswordValid)

        val invalid = state(WelcomeLocal(nickname = "s", email = "sam", registerPassword = "1", resetPassword = "1"))
        assertFalse(invalid.isNicknameValid)
        assertFalse(invalid.isEmailValid)
        assertFalse(invalid.isRegisterPasswordValid)
        assertFalse(invalid.isResetPasswordValid)
    }

    @Test
    fun aGuestNeedsANameAndACode() {
        assertFalse(state(WelcomeLocal(guestName = "  ", joinCode = "ABC123")).isGuestFormComplete)
        assertFalse(state(WelcomeLocal(guestName = "Sam", joinCode = "")).isGuestFormComplete)
        assertTrue(state(WelcomeLocal(guestName = "Sam", joinCode = "ABC123")).isGuestFormComplete)
    }

    @Test
    fun busyWhileACommandRunsOrTheAccountLogsIn() {
        assertFalse(state().isBusy)
        assertTrue(state(inputs = WelcomeInputs(isBusy = true)).isBusy)
        assertTrue(state(inputs = WelcomeInputs(account = AccountState(isBusy = true))).isBusy)
    }

    @Test
    fun theAccountsExpiredSessionAndTheBuildAreShown() {
        val shown = state(inputs = WelcomeInputs(account = AccountState(sessionExpired = true)))
        assertTrue(shown.sessionExpired)
        assertEquals("1.0 (1)", shown.buildLabel)
    }
}
