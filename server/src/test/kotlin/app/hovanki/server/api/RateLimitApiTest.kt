package app.hovanki.server.api

import app.hovanki.server.account.uniqueName
import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ChangeEmailRequest
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.LoginRequest
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.SendChatRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.shrinkingZone
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
        "hovanki.rate-limits.reports.count=2",
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
    fun wrongPasswordsWhenChangingTheEmailCountLikeFailedLogins() {
        val session = register()
        fun changeEmail(password: String) = post(
            ApiRoutes.ME_EMAIL,
            protocolJson.encodeToString(ChangeEmailRequest("${uniqueName()}@example.com", password)),
            session.token,
        )
        repeat(3) { assertEquals(403, changeEmail("wrong password").status) }

        // Locked, even with the right password: a stolen session can't try passwords one after another.
        assertTrue(assertTooManyRequests(changeEmail(PASSWORD)) in 1..15 * 60)
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

    @Test
    fun chatReports() {
        val settings = GameSettings(zone = shrinkingZone(GeoPoint(50.4501, 30.5234)))
        val host = post(ApiRoutes.GAMES, protocolJson.encodeToString(CreateGameRequest("Host", settings)))
            .decode<SessionResponse>()
        val guest = post(ApiRoutes.JOIN, protocolJson.encodeToString(JoinGameRequest(host.snapshot.joinCode, "Guest")))
            .decode<SessionResponse>().session
        val messages = (1..3).map {
            val request = protocolJson.encodeToString(SendChatRequest("message $it", chatAfter = 0))
            post(ApiRoutes.chat(host.session.gameId), request, host.session.token).decode<GameSnapshot>().chat.last()
        }
        fun report(seq: Long) = post(ApiRoutes.chatReport(guest.gameId, seq), json = null, guest.token)

        // Two an hour in this context: the guest's third report is one too many.
        repeat(2) { assertEquals(200, report(messages[it].seq).status) }
        assertTrue(assertTooManyRequests(report(messages[2].seq)) in 1..60 * 60)
    }

    private inline fun <reified T> MockHttpServletResponse.decode(): T {
        assertEquals(200, status, contentAsString)
        return protocolJson.decodeFromString(contentAsString)
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

    private fun post(path: String, json: String?, token: String? = null): MockHttpServletResponse = mvc.post(path) {
        if (json != null) {
            contentType = MediaType.APPLICATION_JSON
            content = json
        }
        if (token != null) header("Authorization", "${ApiRoutes.AUTH_SCHEME} $token")
    }.andReturn().response

    private companion object {
        const val PASSWORD = "correct horse battery"
    }
}
