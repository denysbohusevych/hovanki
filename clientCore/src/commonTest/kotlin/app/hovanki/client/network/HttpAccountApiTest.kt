package app.hovanki.client.network

import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.LoginRequest
import app.hovanki.shared.protocol.PasswordResetConfirmRequest
import app.hovanki.shared.protocol.PasswordResetRequest
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserProfile
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HttpAccountApiTest {
    private val profile = UserProfile(UserId("u1"), "anna", "anna@example.org", emailVerified = true, 5)
    private val accountSession = AccountSession("account-token", profile)

    private lateinit var server: MockServer

    private fun api(handler: MockRequestHandler): HttpAccountApi {
        server = MockServer(handler)
        return HttpAccountApi(server.client, server.serverUrl)
    }

    /** Answers like the server: a session, a profile or 204 No Content, depending on the route. */
    private fun api(): HttpAccountApi = api { request ->
        when (request.url.encodedPath) {
            "/api/v1/accounts", "/api/v1/accounts/login", "/api/v1/accounts/password-reset/confirm" ->
                jsonOf(accountSession)

            "/api/v1/me", "/api/v1/me/email/verify", "/api/v1/me/email" -> jsonOf(profile)

            else -> noContent()
        }
    }

    @Test
    fun sessionCallsAreSentWithoutToken() = runTest {
        val api = api()

        assertEquals(accountSession, api.register(RegisterRequest("anna", "anna@example.org", "password1", "uk")))
        assertEquals(accountSession, api.logIn(LoginRequest("anna", "password1")))
        api.requestPasswordReset(PasswordResetRequest("anna@example.org"))
        assertEquals(
            accountSession,
            api.confirmPasswordReset(PasswordResetConfirmRequest("anna@example.org", "123456", "password2")),
        )

        assertEquals(
            listOf(
                "/api/v1/accounts",
                "/api/v1/accounts/login",
                "/api/v1/accounts/password-reset",
                "/api/v1/accounts/password-reset/confirm",
            ),
            server.recorded.map { it.path },
        )
        assertEquals(List(4) { HttpMethod.Post }, server.recorded.map { it.method })
        assertEquals(List(4) { null }, server.recorded.map { it.authorization })
        assertEquals(
            listOf(
                """{"nickname":"anna","email":"anna@example.org","password":"password1","language":"uk"}""",
                """{"login":"anna","password":"password1"}""",
                """{"email":"anna@example.org"}""",
                """{"email":"anna@example.org","code":"123456","newPassword":"password2"}""",
            ),
            server.recorded.map { it.body },
        )
    }

    @Test
    fun ownAccountCallsCarryTheAccountToken() = runTest {
        val api = api()

        assertEquals(profile, api.me("account-token"))
        assertEquals(profile, api.verifyEmail("account-token", "123456"))
        api.resendCode("account-token")
        assertEquals(profile, api.changeEmail("account-token", "anna@example.com", "password1"))
        api.changePassword("account-token", "password1", "password2")
        api.deleteAccount("account-token", "password2")
        api.logOut("account-token")

        assertEquals(
            listOf(
                HttpMethod.Get to "/api/v1/me",
                HttpMethod.Post to "/api/v1/me/email/verify",
                HttpMethod.Post to "/api/v1/me/email/resend",
                HttpMethod.Post to "/api/v1/me/email",
                HttpMethod.Post to "/api/v1/me/password",
                HttpMethod.Post to "/api/v1/me/delete",
                HttpMethod.Post to "/api/v1/accounts/logout",
            ),
            server.recorded.map { it.method to it.path },
        )
        assertEquals(List(7) { "Bearer account-token" }, server.recorded.map { it.authorization })
        assertEquals(
            listOf(
                "",
                """{"code":"123456"}""",
                "",
                """{"email":"anna@example.com","password":"password1"}""",
                """{"currentPassword":"password1","newPassword":"password2"}""",
                """{"password":"password2"}""",
                "",
            ),
            server.recorded.map { it.body },
        )
    }

    @Test
    fun rejectionsKeepTheirReason() = runTest {
        val cases = listOf(
            HttpStatusCode.Conflict to ApiError(ErrorCode.WRONG_STATE, "Taken", ErrorReason.NICKNAME_TAKEN),
            HttpStatusCode.BadRequest to ApiError(ErrorCode.BAD_REQUEST, "Too short", ErrorReason.INVALID_PASSWORD),
            HttpStatusCode.Forbidden to ApiError(ErrorCode.FORBIDDEN, "No", ErrorReason.WRONG_CREDENTIALS),
            HttpStatusCode.UnprocessableEntity to ApiError(ErrorCode.INVALID_CODE, "Wrong code"),
            HttpStatusCode.UnprocessableEntity to ApiError(
                ErrorCode.INVALID_CODE,
                "Old code",
                ErrorReason.CODE_EXPIRED,
            ),
            HttpStatusCode.Unauthorized to ApiError(ErrorCode.UNAUTHORIZED, "Log in", ErrorReason.SESSION_EXPIRED),
        )
        for ((status, error) in cases) {
            val api = api { apiError(status, error) }

            val exception = assertFailsWith<ApiException> { api.verifyEmail("account-token", "000000") }

            assertEquals(status.value, exception.status)
            assertEquals(error, exception.error)
            assertEquals(error.reason, exception.reason)
        }
    }

    @Test
    fun rateLimitSaysWhenToTryAgain() = runTest {
        val error = ApiError(ErrorCode.WRONG_STATE, "Too many attempts", ErrorReason.TOO_MANY_REQUESTS)
        val api = api { apiError(HttpStatusCode.TooManyRequests, error, retryAfter = 60) }

        val exception = assertFailsWith<ApiException> { api.logIn(LoginRequest("anna", "password1")) }

        assertEquals(429, exception.status)
        assertEquals(ErrorReason.TOO_MANY_REQUESTS, exception.reason)
        assertEquals(60L, exception.retryAfterSeconds)
    }

    @Test
    fun unknownReasonFromANewerServerIsNull() = runTest {
        val api =
            api {
                json("""{"code":"FORBIDDEN","message":"New rule","reason":"SOMETHING_NEW"}""", HttpStatusCode.Forbidden)
            }

        val exception = assertFailsWith<ApiException> { api.resendCode("account-token") }

        assertEquals(ErrorCode.FORBIDDEN, exception.error?.code)
        assertNull(exception.reason)
    }
}
