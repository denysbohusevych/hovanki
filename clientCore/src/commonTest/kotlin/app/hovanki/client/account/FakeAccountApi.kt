package app.hovanki.client.account

import app.hovanki.client.network.AccountApi
import app.hovanki.client.network.ApiException
import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ChangeEmailRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.LoginRequest
import app.hovanki.shared.protocol.PasswordResetConfirmRequest
import app.hovanki.shared.protocol.PasswordResetRequest
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.VerifyEmailRequest
import kotlinx.coroutines.CompletableDeferred

val testUser = UserProfile(UserId("u1"), "anna", "anna@example.org", emailVerified = true, createdAtMillis = 5)

const val TEST_ACCOUNT_TOKEN = "account-token"

/** 401 as the server sends it for an unknown or revoked account token. */
fun sessionExpired() = ApiException(401, ApiError(ErrorCode.UNAUTHORIZED, "Log in again", ErrorReason.SESSION_EXPIRED))

/**
 * [AccountApi] that answers like a happy server with [user] and [token]; [failWith] makes every call fail, [gate]
 * holds every call until completed. Records what was sent.
 */
class FakeAccountApi(var user: UserProfile = testUser, var token: String = TEST_ACCOUNT_TOKEN) : AccountApi {
    /** Every call: its name and the account token it was made with, if any. */
    val calls = mutableListOf<String>()

    /** The request bodies, in order. */
    val requests = mutableListOf<Any>()

    var failWith: Exception? = null

    var gate: CompletableDeferred<Unit>? = null

    override suspend fun register(request: RegisterRequest): AccountSession = call("register", request = request) {
        user = user.copy(nickname = request.nickname, email = request.email, emailVerified = false)
        AccountSession(token, user)
    }

    override suspend fun logIn(request: LoginRequest): AccountSession =
        call("logIn", request = request) { AccountSession(token, user) }

    override suspend fun logOut(token: String) = call("logOut", token) {}

    override suspend fun requestPasswordReset(request: PasswordResetRequest) =
        call("requestPasswordReset", request = request) {}

    override suspend fun confirmPasswordReset(request: PasswordResetConfirmRequest): AccountSession =
        call("confirmPasswordReset", request = request) {
            user = user.copy(emailVerified = true)
            AccountSession(token, user)
        }

    override suspend fun me(token: String): UserProfile = call("me", token) { user }

    override suspend fun verifyEmail(token: String, code: String): UserProfile =
        call("verifyEmail", token, VerifyEmailRequest(code)) {
            user = user.copy(emailVerified = true)
            user
        }

    override suspend fun resendCode(token: String) = call("resendCode", token) {}

    override suspend fun changeEmail(token: String, email: String, password: String): UserProfile =
        call("changeEmail", token, ChangeEmailRequest(email, password)) {
            user = user.copy(email = email)
            user
        }

    override suspend fun changePassword(token: String, currentPassword: String, newPassword: String) =
        call("changePassword", token) {}

    override suspend fun deleteAccount(token: String, password: String) = call("deleteAccount", token) {}

    private suspend fun <T> call(name: String, token: String? = null, request: Any? = null, answer: () -> T): T {
        calls += listOfNotNull(name, token).joinToString(" ")
        if (request != null) requests += request
        gate?.await()
        failWith?.let { throw it }
        return answer()
    }
}
