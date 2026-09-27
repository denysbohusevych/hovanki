package app.hovanki.client.network

import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ChangeEmailRequest
import app.hovanki.shared.protocol.ChangePasswordRequest
import app.hovanki.shared.protocol.DeleteAccountRequest
import app.hovanki.shared.protocol.LoginRequest
import app.hovanki.shared.protocol.PasswordResetConfirmRequest
import app.hovanki.shared.protocol.PasswordResetRequest
import app.hovanki.shared.protocol.PrivacyRequest
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.VerifyEmailRequest
import io.ktor.client.HttpClient

/**
 * Account calls (docs/adr/0004-accounts-friends-chat.md). [token] is the account token of [AccountSession], never a
 * game token. A 401 on a call with a token means the account session is gone (revoked, expired): log in again.
 *
 * Throws [ApiException] when the server rejects a call, and I/O or serialization exceptions on network problems.
 */
interface AccountApi {
    /** Creates the account, usable right away, and emails the code to confirm its email (optional). */
    suspend fun register(request: RegisterRequest): AccountSession

    suspend fun logIn(request: LoginRequest): AccountSession

    /** Ends this device's session on the server. */
    suspend fun logOut(token: String)

    /** Emails a reset code if the address belongs to an account; the answer is the same either way. */
    suspend fun requestPasswordReset(request: PasswordResetRequest)

    /** New password with the emailed code: every other session ends, the email counts as confirmed. */
    suspend fun confirmPasswordReset(request: PasswordResetConfirmRequest): AccountSession

    suspend fun me(token: String): UserProfile

    suspend fun verifyEmail(token: String, code: String): UserProfile

    /** Emails a new verification code. */
    suspend fun resendCode(token: String)

    /** Fixes a mistyped, not yet confirmed email, with the current [password]; a new code goes there. */
    suspend fun changeEmail(token: String, email: String, password: String): UserProfile

    /** Ends every other session of the account. */
    suspend fun changePassword(token: String, currentPassword: String, newPassword: String)

    suspend fun deleteAccount(token: String, password: String)

    /**
     * Keep the routes of my games, or not (docs/adr/0007-game-history-and-routes.md). Off deletes every route saved so
     * far; on also keeps the games that just finished (the results screen).
     */
    suspend fun setSaveRoutes(token: String, enabled: Boolean): UserProfile
}

/** [AccountApi] over HTTP/JSON. */
class HttpAccountApi(client: HttpClient, serverUrl: ServerUrl) : AccountApi {
    private val http = HttpSupport(client, serverUrl)

    override suspend fun register(request: RegisterRequest): AccountSession =
        http.post(ApiRoutes.ACCOUNTS, token = null, request)

    override suspend fun logIn(request: LoginRequest): AccountSession =
        http.post(ApiRoutes.LOGIN, token = null, request)

    override suspend fun logOut(token: String) {
        http.post<Unit>(ApiRoutes.LOGOUT, token)
    }

    override suspend fun requestPasswordReset(request: PasswordResetRequest) {
        http.post<PasswordResetRequest, Unit>(ApiRoutes.PASSWORD_RESET, token = null, request)
    }

    override suspend fun confirmPasswordReset(request: PasswordResetConfirmRequest): AccountSession =
        http.post(ApiRoutes.PASSWORD_RESET_CONFIRM, token = null, request)

    override suspend fun me(token: String): UserProfile = http.get(ApiRoutes.ME, token)

    override suspend fun verifyEmail(token: String, code: String): UserProfile =
        http.post(ApiRoutes.ME_EMAIL_VERIFY, token, VerifyEmailRequest(code))

    override suspend fun resendCode(token: String) {
        http.post<Unit>(ApiRoutes.ME_EMAIL_RESEND, token)
    }

    override suspend fun changeEmail(token: String, email: String, password: String): UserProfile =
        http.post(ApiRoutes.ME_EMAIL, token, ChangeEmailRequest(email, password))

    override suspend fun changePassword(token: String, currentPassword: String, newPassword: String) {
        http.post<ChangePasswordRequest, Unit>(
            ApiRoutes.ME_PASSWORD,
            token,
            ChangePasswordRequest(currentPassword, newPassword),
        )
    }

    override suspend fun deleteAccount(token: String, password: String) {
        http.post<DeleteAccountRequest, Unit>(ApiRoutes.ME_DELETE, token, DeleteAccountRequest(password))
    }

    override suspend fun setSaveRoutes(token: String, enabled: Boolean): UserProfile =
        http.post(ApiRoutes.ME_PRIVACY, token, PrivacyRequest(enabled))
}
