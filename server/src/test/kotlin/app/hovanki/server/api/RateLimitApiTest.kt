package app.hovanki.server.api

import app.hovanki.server.account.uniqueName
import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.LoginRequest
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.protocolJson
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rate limits over HTTP, in a context of their own: the other tests run without them. */
@SpringBootTest(
    properties = [
        "hovanki.rate-limits.enabled=true",
        "hovanki.rate-limits.login-per-login.count=3",
        "hovanki.rate-limits.login-per-login.window=15m",
    ],
)
@AutoConfigureMockMvc
class RateLimitApiTest(@Autowired private val mvc: MockMvc) {
    @Test
    fun failedLoginsLockTheLogin() {
        val nickname = register().user.nickname
        repeat(3) { assertEquals(403, login(nickname, "wrong password").status) }

        for (password in listOf("wrong password", PASSWORD)) {
            val locked = login(nickname, password)
            val retryAfter = assertTooManyRequests(locked)
            assertTrue(retryAfter in 1..15 * 60, "$retryAfter")
        }
        // Another login is not locked.
        assertEquals(403, login(uniqueName(), "wrong password").status)
    }

    @Test
    fun oneCodeAMinute() {
        val session = register()
        // Registering sent the first code.
        val resend = mvc.post(ApiRoutes.ME_EMAIL_RESEND) {
            header("Authorization", "${ApiRoutes.AUTH_SCHEME} ${session.token}")
        }.andReturn().response
        assertTrue(assertTooManyRequests(resend) in 1..60)
    }

    private fun assertTooManyRequests(response: MockHttpServletResponse): Int {
        assertEquals(429, response.status, response.contentAsString)
        val error = protocolJson.decodeFromString<ApiError>(response.contentAsString)
        assertEquals(ErrorCode.WRONG_STATE, error.code)
        assertEquals(ErrorReason.TOO_MANY_REQUESTS, error.reason)
        return assertNotNull(response.getHeader(HttpHeaders.RETRY_AFTER)).toInt()
    }

    private fun register(): AccountSession {
        val nickname = uniqueName()
        val request = RegisterRequest(nickname, "$nickname@example.com", PASSWORD)
        val response = post(ApiRoutes.ACCOUNTS, protocolJson.encodeToString(request))
        assertEquals(200, response.status, response.contentAsString)
        assertNull(response.getHeader(HttpHeaders.RETRY_AFTER))
        return protocolJson.decodeFromString(response.contentAsString)
    }

    private fun login(login: String, password: String) =
        post(ApiRoutes.LOGIN, protocolJson.encodeToString(LoginRequest(login, password)))

    private fun post(path: String, json: String): MockHttpServletResponse = mvc.post(path) {
        contentType = MediaType.APPLICATION_JSON
        content = json
    }.andReturn().response

    private companion object {
        const val PASSWORD = "correct horse battery"
    }
}
