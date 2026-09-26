package app.hovanki.e2e.devices

import app.hovanki.client.network.AccountApi
import app.hovanki.client.network.ApiException
import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.LoginRequest
import app.hovanki.shared.protocol.PasswordResetConfirmRequest
import app.hovanki.shared.protocol.PasswordResetRequest
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.rules.AccountRules
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeviceAccountsTest {
    @Test
    fun registersConfirmsAndEndsTheRegistrationSession() = runBlocking {
        val server = FakeAccountServer()
        val accounts = DeviceAccounts(server, emailedCode = { email -> server.codeSentTo(email) })

        val host = accounts.create("Android-1")
        val next = accounts.create("Android-1")

        assertTrue(host.nickname.startsWith("Android1_") && AccountRules.isValidNickname(host.nickname))
        assertTrue(host.nickname != next.nickname && host.email != next.email, "unique per run")
        val registered = server.users.getValue(host.email)
        assertEquals(host.nickname, registered.nickname)
        assertTrue(registered.emailVerified, "confirmed with the emailed code")
        assertEquals(host.password, server.passwords.getValue(host.email))
        assertEquals(DeviceAccounts.LANGUAGE, server.languages.getValue(host.email))
        assertEquals(emptySet(), server.openSessions, "the device logs in with a session of its own")
    }

    @Test
    fun aRejectedCodeFailsTheRun() {
        val server = FakeAccountServer()
        val accounts = DeviceAccounts(server, emailedCode = { "000000" })

        assertFailsWith<ApiException> { runBlocking { accounts.create("iOS-1") } }
    }

    /** The account routes as far as [DeviceAccounts] uses them. */
    private class FakeAccountServer : AccountApi {
        val users = mutableMapOf<String, UserProfile>()
        val passwords = mutableMapOf<String, String>()
        val languages = mutableMapOf<String, String>()
        private val codes = mutableMapOf<String, String>()
        private val sessions = mutableMapOf<String, String>()
        val openSessions: Set<String> get() = sessions.keys

        fun codeSentTo(email: String): String = codes.getValue(email)

        override suspend fun register(request: RegisterRequest): AccountSession {
            val user = UserProfile(UserId("u${users.size}"), request.nickname, request.email, false, 0)
            users[request.email] = user
            passwords[request.email] = request.password
            languages[request.email] = request.language
            codes[request.email] = "12345${users.size}"
            val token = "token-${request.email}"
            sessions[token] = request.email
            return AccountSession(token, user)
        }

        override suspend fun verifyEmail(token: String, code: String): UserProfile {
            val email = sessions.getValue(token)
            if (codes[email] != code) throw ApiException(422, ApiError(ErrorCode.INVALID_CODE, "Wrong code"))
            return users.getValue(email).copy(emailVerified = true).also { users[email] = it }
        }

        override suspend fun logOut(token: String) {
            sessions.remove(token)
        }

        override suspend fun logIn(request: LoginRequest): AccountSession = error("not used")

        override suspend fun requestPasswordReset(request: PasswordResetRequest) = error("not used")

        override suspend fun confirmPasswordReset(request: PasswordResetConfirmRequest): AccountSession =
            error("not used")

        override suspend fun me(token: String): UserProfile = error("not used")

        override suspend fun resendCode(token: String) = error("not used")

        override suspend fun changeEmail(token: String, email: String): UserProfile = error("not used")

        override suspend fun changePassword(token: String, currentPassword: String, newPassword: String) =
            error("not used")

        override suspend fun deleteAccount(token: String, password: String) = error("not used")
    }
}
