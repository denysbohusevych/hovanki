package app.hovanki.server.api

import app.hovanki.server.account.awaitCode
import app.hovanki.server.account.uniqueName
import app.hovanki.server.mail.EmailPurpose
import app.hovanki.server.mail.RecordingEmailSender
import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.AdminEnrollRequest
import app.hovanki.shared.protocol.AdminEnrollment
import app.hovanki.shared.protocol.AdminLoginRequest
import app.hovanki.shared.protocol.AdminLoginResponse
import app.hovanki.shared.protocol.AdminTotpRequest
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.UserRole
import app.hovanki.shared.protocol.VerifyEmailRequest
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.totp.Totp
import jakarta.servlet.http.Cookie
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.io.ByteArrayOutputStream
import java.time.Clock
import kotlin.test.assertEquals

/**
 * A staff member's browser for the admin's API tests, over MockMvc: signs up an account, makes it staff in the
 * database (like `docs/deploy.md` does for admins), sets up the authenticator and keeps the session cookie the answers
 * set. [AdminApiTest] has its own copy with more of the login's corners.
 */
class AdminTestClient(
    private val mvc: MockMvc,
    private val emails: RecordingEmailSender,
    private val clock: Clock,
    private val jdbc: JdbcClient,
) {
    /** A logged-in staff member: [token] is the session cookie, replaced when an answer rotates it. */
    class StaffLogin(val account: AccountSession, var token: String) {
        fun follow(response: TestResponse) {
            response.session?.takeIf { it.isNotEmpty() }?.let { token = it }
        }
    }

    fun staff(role: UserRole): StaffLogin {
        val nickname = uniqueName()
        val email = "$nickname@example.com"
        val session = postRaw(ApiRoutes.ACCOUNTS, RegisterRequest(nickname, email, PASSWORD).asJson(), null)
            .ok<AccountSession>()
        val verify = emails.awaitCode(email, purpose = EmailPurpose.VERIFY_EMAIL)
        val verified = mvc.post(ApiRoutes.ME_EMAIL_VERIFY) {
            contentType = MediaType.APPLICATION_JSON
            content = VerifyEmailRequest(verify).asJson()
            header(HttpHeaders.AUTHORIZATION, "${ApiRoutes.AUTH_SCHEME} ${session.token}")
        }.andReturn().response
        assertEquals(200, verified.status, verified.contentAsString)
        jdbc.sql("UPDATE users SET role = :role WHERE id = :id")
            .param("role", role.name)
            .param("id", session.user.id.value)
            .update()
        val login = postRaw(ApiRoutes.ADMIN_LOGIN, AdminLoginRequest(email, PASSWORD).asJson(), null)
            .ok<AdminLoginResponse>()
        val code = emails.awaitCode(email, purpose = EmailPurpose.STAFF_ENROLL)
        val enrollment = postRaw(ApiRoutes.ADMIN_ENROLL, AdminEnrollRequest(login.challenge, code).asJson(), null)
            .ok<AdminEnrollment>()
        val totp = Totp(base32(enrollment.secret))
        val confirmed = postRaw(
            ApiRoutes.ADMIN_ENROLL_CONFIRM,
            AdminTotpRequest(login.challenge, totp.codeAt(clock.millis())).asJson(),
            null,
        ).expect(200)
        return StaffLogin(session, checkNotNull(confirmed.session))
    }

    fun get(path: String, staff: StaffLogin): TestResponse {
        val response = mvc.get(path) {
            header(ApiRoutes.ADMIN_HEADER, "1")
            cookie(Cookie(ApiRoutes.ADMIN_COOKIE, staff.token))
        }.andReturn().response
        return TestResponse(
            response.status,
            response.getContentAsString(Charsets.UTF_8),
            response.getHeader(HttpHeaders.SET_COOKIE),
            response.contentAsByteArray,
            response.contentType,
            response.getHeader(HttpHeaders.CONTENT_DISPOSITION),
        ).also(staff::follow)
    }

    inline fun <reified T> post(path: String, body: T, staff: StaffLogin): TestResponse =
        postRaw(path, body.asJson(), staff).also(staff::follow)

    inline fun <reified T> delete(path: String, body: T, staff: StaffLogin): TestResponse =
        deleteRaw(path, body.asJson(), staff).also(staff::follow)

    fun deleteRaw(path: String, json: String, staff: StaffLogin): TestResponse {
        val response = mvc.delete(path) {
            contentType = MediaType.APPLICATION_JSON
            content = json
            header(ApiRoutes.ADMIN_HEADER, "1")
            cookie(Cookie(ApiRoutes.ADMIN_COOKIE, staff.token))
        }.andReturn().response
        return TestResponse(
            response.status,
            response.getContentAsString(Charsets.UTF_8),
            response.getHeader(HttpHeaders.SET_COOKIE),
            response.contentAsByteArray,
            response.contentType,
            response.getHeader(HttpHeaders.CONTENT_DISPOSITION),
        )
    }

    fun postRaw(path: String, json: String?, staff: StaffLogin?): TestResponse {
        val response = mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            if (json != null) content = json
            header(ApiRoutes.ADMIN_HEADER, "1")
            if (staff != null) cookie(Cookie(ApiRoutes.ADMIN_COOKIE, staff.token))
        }.andReturn().response
        return TestResponse(
            response.status,
            response.getContentAsString(Charsets.UTF_8),
            response.getHeader(HttpHeaders.SET_COOKIE),
            response.contentAsByteArray,
            response.contentType,
            response.getHeader(HttpHeaders.CONTENT_DISPOSITION),
        )
    }

    companion object {
        const val PASSWORD = "correct horse battery"

        /** RFC 4648 base32, as an authenticator app reads the secret. */
        fun base32(text: String): ByteArray {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            var buffer = 0
            var bits = 0
            val out = ByteArrayOutputStream()
            for (char in text) {
                buffer = (buffer shl 5) or alphabet.indexOf(char)
                bits += 5
                if (bits >= 8) {
                    out.write((buffer shr (bits - 8)) and 0xff)
                    bits -= 8
                }
            }
            return out.toByteArray()
        }
    }
}

/** An answer of the server in the API tests. */
class TestResponse(
    val status: Int,
    val body: String,
    val setCookie: String? = null,
    val bytes: ByteArray = body.toByteArray(),
    val contentType: String? = null,
    val contentDisposition: String? = null,
) {
    fun expect(status: Int) = also { assertEquals(status, this.status, body) }

    inline fun <reified T> ok(): T = protocolJson.decodeFromString(expect(200).body)

    fun error(status: Int, code: ErrorCode, reason: ErrorReason? = null) {
        val error = protocolJson.decodeFromString<ApiError>(expect(status).body)
        assertEquals(code, error.code, body)
        if (reason != null) assertEquals(reason, error.reason, body)
    }

    /** The admin session token of the cookie this answer set. */
    val session: String? get() = setCookie?.substringAfter("${ApiRoutes.ADMIN_COOKIE}=")?.substringBefore(';')
}

inline fun <reified T> T.asJson(): String = protocolJson.encodeToString(this)
