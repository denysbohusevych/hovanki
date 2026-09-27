package app.hovanki.client.account

import app.hovanki.client.network.ApiException
import app.hovanki.client.network.ApiResult
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.FakeSecureStore
import app.hovanki.client.storage.SavedAccount
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ChangeEmailRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.LoginRequest
import app.hovanki.shared.protocol.PasswordResetConfirmRequest
import app.hovanki.shared.protocol.PasswordResetRequest
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.VerifyEmailRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AccountManagerTest {
    private class Offline : Exception("offline")

    private val server = "https://hovanki.example.org"
    private val storage = ClientStorage(FakeSecureStore())
    private val api = FakeAccountApi()
    private val unverified = testUser.copy(emailVerified = false)

    private fun TestScope.manager() = AccountManager(api, storage, ServerUrl(server), backgroundScope)

    /** Restored from storage as [user] (the server agrees), with the calls of the restore forgotten. */
    private fun TestScope.loggedIn(user: UserProfile = testUser): AccountManager {
        api.user = user
        storage.saveAccount(SavedAccount(server, TEST_ACCOUNT_TOKEN, user))
        return manager().also {
            it.restore()
            runCurrent()
            api.calls.clear()
        }
    }

    @Test
    fun unknownUntilRestoredThenLoggedOut() = runTest {
        val manager = manager()
        assertFalse(manager.state.value.isRestored)

        manager.restore()

        assertEquals(AccountState(isRestored = true), manager.state.value)
        assertNull(manager.accountToken)
        runCurrent()
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun restoresTheSavedAccountThenRefreshesIt() = runTest {
        storage.saveAccount(SavedAccount(server, TEST_ACCOUNT_TOKEN, unverified))
        val manager = manager()

        manager.restore()
        assertEquals(unverified, manager.state.value.user, "logged in right away, without waiting for the server")
        assertTrue(manager.state.value.hasUnconfirmedEmail)
        assertEquals(TEST_ACCOUNT_TOKEN, manager.accountToken, "an unconfirmed email is no obstacle")

        runCurrent()
        assertEquals(listOf("me $TEST_ACCOUNT_TOKEN"), api.calls)
        assertTrue(manager.state.value.hasConfirmedEmail, "confirmed meanwhile (e.g. on another device)")
        assertEquals(TEST_ACCOUNT_TOKEN, manager.accountToken)
        assertEquals(testUser, storage.loadAccount()?.user)
    }

    @Test
    fun restoresOnlyOnce() = runTest {
        val manager = loggedIn()
        manager.logOut()
        storage.saveAccount(SavedAccount(server, TEST_ACCOUNT_TOKEN, testUser))

        manager.restore()

        assertFalse(manager.state.value.isLoggedIn)
    }

    @Test
    fun anAccountOfAnotherServerIsDropped() = runTest {
        storage.saveAccount(SavedAccount("http://10.0.2.2:8080", TEST_ACCOUNT_TOKEN, testUser))
        val manager = manager()

        manager.restore()
        runCurrent()

        assertFalse(manager.state.value.isLoggedIn)
        assertNull(storage.loadAccount())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun aRefreshRejectedByTheServerLogsOut() = runTest {
        storage.saveAccount(SavedAccount(server, TEST_ACCOUNT_TOKEN, testUser))
        api.failWith = sessionExpired()
        val manager = manager()

        manager.restore()
        runCurrent()

        assertEquals(AccountState(isRestored = true, sessionExpired = true), manager.state.value)
        assertNull(storage.loadAccount())
        assertNull(manager.accountToken)

        manager.dismissSessionExpired()
        assertFalse(manager.state.value.sessionExpired)
    }

    @Test
    fun aRefreshWithoutNetworkKeepsTheSavedProfile() = runTest {
        storage.saveAccount(SavedAccount(server, TEST_ACCOUNT_TOKEN, testUser))
        api.failWith = Offline()
        val manager = manager()

        manager.restore()
        runCurrent()

        assertEquals(testUser, manager.state.value.user)
        assertEquals(TEST_ACCOUNT_TOKEN, manager.accountToken)
        assertEquals(testUser, storage.loadAccount()?.user)
    }

    @Test
    fun registerLogsInRightAwayWithAnUnconfirmedEmail() = runTest {
        val manager = manager()
        manager.restore()

        val result = manager.register(" anna ", " anna@example.org ", "password1", "ru-RU")

        assertEquals(ApiResult.Success(Unit), result)
        assertEquals(RegisterRequest("anna", "anna@example.org", "password1", "ru"), api.requests.single())
        assertTrue(manager.state.value.hasUnconfirmedEmail)
        assertEquals(TEST_ACCOUNT_TOKEN, manager.accountToken, "games, friends and groups work right away")
        assertEquals(SavedAccount(server, TEST_ACCOUNT_TOKEN, unverified), storage.loadAccount())
    }

    @Test
    fun verifyingTheEmailConfirmsIt() = runTest {
        val manager = loggedIn(unverified)

        assertEquals(ApiResult.Success(Unit), manager.verifyEmail(" 123 456 "))

        assertEquals(VerifyEmailRequest("123456"), api.requests.single())
        assertEquals(listOf("verifyEmail $TEST_ACCOUNT_TOKEN"), api.calls)
        assertTrue(manager.state.value.hasConfirmedEmail)
        assertEquals(TEST_ACCOUNT_TOKEN, manager.accountToken, "the same session")
        assertTrue(storage.loadAccount()!!.user.emailVerified)
    }

    @Test
    fun aWrongCodeKeepsTheReason() = runTest {
        val manager = loggedIn(unverified)
        for (reason in listOf(null, ErrorReason.CODE_EXPIRED)) {
            api.failWith = ApiException(422, ApiError(ErrorCode.INVALID_CODE, "Wrong code", reason))

            val result = assertIs<ApiResult.Rejected>(manager.verifyEmail("000000"))

            assertEquals(ErrorCode.INVALID_CODE, result.code)
            assertEquals(reason, result.reason)
            assertTrue(manager.state.value.hasUnconfirmedEmail)
        }
    }

    @Test
    fun changeEmailAndResendCode() = runTest {
        val manager = loggedIn(unverified)

        assertEquals(ApiResult.Success(Unit), manager.changeEmail(" anna@example.com ", "password1"))
        assertEquals(ApiResult.Success(Unit), manager.resendCode())

        assertEquals(ChangeEmailRequest("anna@example.com", "password1"), api.requests.single())
        assertEquals(listOf("changeEmail $TEST_ACCOUNT_TOKEN", "resendCode $TEST_ACCOUNT_TOKEN"), api.calls)
        assertEquals("anna@example.com", manager.state.value.user?.email)
        assertEquals("anna@example.com", storage.loadAccount()?.user?.email)
    }

    @Test
    fun changingTheEmailWithAWrongPasswordKeepsIt() = runTest {
        val manager = loggedIn(unverified)
        api.failWith = ApiException(403, ApiError(ErrorCode.FORBIDDEN, "Wrong password", ErrorReason.WRONG_CREDENTIALS))

        val result = assertIs<ApiResult.Rejected>(manager.changeEmail("anna@example.com", "wrong"))

        assertEquals(ErrorReason.WRONG_CREDENTIALS, result.reason)
        assertEquals(unverified, manager.state.value.user, "still logged in, with the old email")
        assertEquals(unverified, storage.loadAccount()?.user)
    }

    @Test
    fun aRateLimitSaysWhenToTryAgain() = runTest {
        val manager = loggedIn(unverified)
        api.failWith = ApiException(
            429,
            ApiError(ErrorCode.WRONG_STATE, "Slow down", ErrorReason.TOO_MANY_REQUESTS),
            retryAfterSeconds = 42,
        )

        val result = assertIs<ApiResult.Rejected>(manager.resendCode())

        assertEquals(ErrorReason.TOO_MANY_REQUESTS, result.reason)
        assertEquals(42L, result.retryAfterSeconds)
        assertTrue(manager.state.value.isLoggedIn)
    }

    @Test
    fun logInByNicknameOrEmail() = runTest {
        val manager = manager()
        manager.restore()

        assertEquals(ApiResult.Success(Unit), manager.logIn(" anna ", "password1"))

        assertEquals(LoginRequest("anna", "password1"), api.requests.single())
        assertEquals(testUser, manager.state.value.user)
        assertEquals(TEST_ACCOUNT_TOKEN, manager.accountToken)
        assertEquals(SavedAccount(server, TEST_ACCOUNT_TOKEN, testUser), storage.loadAccount())
    }

    @Test
    fun wrongCredentialsAreRejectedWithoutLoggingIn() = runTest {
        val manager = manager()
        manager.restore()
        api.failWith = ApiException(403, ApiError(ErrorCode.FORBIDDEN, "Wrong login", ErrorReason.WRONG_CREDENTIALS))

        val result = manager.logIn("anna", "wrong")

        assertEquals(ApiResult.Rejected(ErrorCode.FORBIDDEN, ErrorReason.WRONG_CREDENTIALS, "Wrong login"), result)
        assertEquals(AccountState(isRestored = true), manager.state.value)
    }

    @Test
    fun noNetworkIsReportedAsSuch() = runTest {
        val manager = manager()
        api.failWith = Offline()

        assertEquals(ApiResult.Network("offline"), manager.logIn("anna", "password1"))
        assertFalse(manager.state.value.isLoggedIn)
    }

    @Test
    fun passwordResetLogsIn() = runTest {
        val manager = manager()
        manager.restore()
        api.user = unverified

        assertEquals(ApiResult.Success(Unit), manager.requestPasswordReset(" anna@example.org"))
        assertEquals(ApiResult.Success(Unit), manager.resetPassword("anna@example.org ", "12 34 56", "password2"))

        assertEquals(
            listOf(
                PasswordResetRequest("anna@example.org"),
                PasswordResetConfirmRequest("anna@example.org", "123456", "password2"),
            ),
            api.requests,
        )
        assertTrue(manager.state.value.hasConfirmedEmail, "the code confirms the email too")
        assertEquals(TEST_ACCOUNT_TOKEN, storage.loadAccount()?.token)
    }

    @Test
    fun logOutIsImmediateAndTellsTheServer() = runTest {
        val manager = loggedIn()
        api.failWith = Offline()

        manager.logOut()

        assertEquals(AccountState(isRestored = true), manager.state.value)
        assertNull(storage.loadAccount())
        assertNull(manager.accountToken)
        runCurrent()
        assertEquals(listOf("logOut $TEST_ACCOUNT_TOKEN"), api.calls, "best effort: a failure changes nothing")
    }

    @Test
    fun anyUnauthorizedAnswerLogsOut() = runTest {
        val manager = loggedIn()
        api.failWith = sessionExpired()

        val result = assertIs<ApiResult.Rejected>(manager.changePassword("password1", "password2"))

        assertEquals(ErrorReason.SESSION_EXPIRED, result.reason)
        assertEquals(AccountState(isRestored = true, sessionExpired = true), manager.state.value)
        assertNull(storage.loadAccount())
    }

    @Test
    fun aWrongPasswordIsNoLogout() = runTest {
        val manager = loggedIn()
        api.failWith = ApiException(403, ApiError(ErrorCode.FORBIDDEN, "Wrong password", ErrorReason.WRONG_CREDENTIALS))

        val result = assertIs<ApiResult.Rejected>(manager.deleteAccount("wrong"))

        assertEquals(ErrorReason.WRONG_CREDENTIALS, result.reason)
        assertTrue(manager.state.value.isLoggedIn)
        assertEquals(TEST_ACCOUNT_TOKEN, storage.loadAccount()?.token)
    }

    @Test
    fun deletingTheAccountLogsOut() = runTest {
        val manager = loggedIn()

        assertEquals(ApiResult.Success(Unit), manager.deleteAccount("password1"))

        assertEquals(listOf("deleteAccount $TEST_ACCOUNT_TOKEN"), api.calls)
        assertEquals(AccountState(isRestored = true), manager.state.value)
        assertNull(storage.loadAccount())
    }

    @Test
    fun changingThePasswordKeepsThisDeviceLoggedIn() = runTest {
        val manager = loggedIn()

        assertEquals(ApiResult.Success(Unit), manager.changePassword("password1", "password2"))

        assertTrue(manager.state.value.isLoggedIn)
        assertEquals(TEST_ACCOUNT_TOKEN, manager.accountToken)
    }

    @Test
    fun aRejectedTokenLogsOutOnlyIfItIsStillTheCurrentOne() = runTest {
        val manager = loggedIn()

        manager.onTokenRejected("an-older-token")
        assertTrue(manager.state.value.isLoggedIn)

        manager.onTokenRejected(TEST_ACCOUNT_TOKEN)
        assertEquals(AccountState(isRestored = true, sessionExpired = true), manager.state.value)
    }

    @Test
    fun accountCommandsNeedAnAccount() = runTest {
        val manager = manager()
        manager.restore()

        val result = assertIs<ApiResult.Rejected>(manager.verifyEmail("123456"))

        assertEquals(ErrorReason.ACCOUNT_REQUIRED, result.reason)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun busyWhileACommandRuns() = runTest {
        val manager = manager()
        val gate = CompletableDeferred<Unit>()
        api.gate = gate

        val login = async { manager.logIn("anna", "password1") }
        runCurrent()
        assertTrue(manager.state.value.isBusy)

        gate.complete(Unit)
        login.await()
        assertFalse(manager.state.value.isBusy)
    }

    @Test
    fun forgetSavedAccountStartsLoggedOut() = runTest {
        storage.saveAccount(SavedAccount(server, TEST_ACCOUNT_TOKEN, testUser))
        val manager = manager()

        manager.forgetSavedAccount()
        manager.restore()
        runCurrent()

        assertFalse(manager.state.value.isLoggedIn)
        assertNull(storage.loadAccount())
        assertEquals(listOf("logOut $TEST_ACCOUNT_TOKEN"), api.calls)
    }
}
