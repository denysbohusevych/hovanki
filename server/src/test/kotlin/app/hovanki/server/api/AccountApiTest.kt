package app.hovanki.server.api

import app.hovanki.server.MutableClock
import app.hovanki.server.account.AccountTestConfig
import app.hovanki.server.account.DeletionRecorder
import app.hovanki.server.account.TestRoutes
import app.hovanki.server.account.awaitCode
import app.hovanki.server.account.uniqueName
import app.hovanki.server.mail.EmailPurpose
import app.hovanki.server.mail.EmailSender
import app.hovanki.server.mail.RecordingEmailSender
import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ChangeEmailRequest
import app.hovanki.shared.protocol.ChangePasswordRequest
import app.hovanki.shared.protocol.DeleteAccountRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.LoginRequest
import app.hovanki.shared.protocol.PasswordResetConfirmRequest
import app.hovanki.shared.protocol.PasswordResetRequest
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.VerifyEmailRequest
import app.hovanki.shared.protocol.protocolJson
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The account routes over HTTP with the shared DTOs, as the app uses them; emails come from the recording sender. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountTestConfig::class)
class AccountApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired emailSender: EmailSender,
    @Autowired private val clock: MutableClock,
    @Autowired private val deletions: DeletionRecorder,
    @Autowired private val jdbc: JdbcClient,
) {
    private val emails = emailSender as RecordingEmailSender

    @Test
    fun registerConfirmTheEmailAndUseTheAccount() {
        val nickname = uniqueName()
        val email = "$nickname@Example.com"
        val session = register(nickname, email, language = "uk-UA").ok<AccountSession>()
        assertEquals(nickname, session.user.nickname)
        assertEquals(email, session.user.email)
        assertFalse(session.user.emailVerified)

        // Only the verification routes work until the email is confirmed.
        assertEquals(session.user, getRaw(ApiRoutes.ME, session.token).ok<UserProfile>())
        getRaw(TestRoutes.VERIFIED, session.token).error(403, ErrorCode.FORBIDDEN, ErrorReason.EMAIL_NOT_VERIFIED)

        emails.awaitCode(email)
        val sent = emails.sentTo(email).single().email
        assertEquals(EmailPurpose.VERIFY_EMAIL, sent.purpose)
        assertEquals("uk", sent.language)
        assertContains(sent.text, sent.code)
        val verified = verify(session, sent.code).ok<UserProfile>()
        assertTrue(verified.emailVerified)

        assertEquals(session.user.id.value, getRaw(TestRoutes.VERIFIED, session.token).expect(200).body)
        assertEquals(verified, getRaw(ApiRoutes.ME, session.token).ok<UserProfile>())
        // Once confirmed, the email stays: changing it is not possible (yet).
        postRaw(ApiRoutes.ME_EMAIL, ChangeEmailRequest("other-$email").toJson(), session.token)
            .error(409, ErrorCode.WRONG_STATE)
    }

    @Test
    fun wrongAndExpiredCodes() {
        val (session, email) = register()
        val code = emails.awaitCode(email)

        verify(session, wrong(code)).error(422, ErrorCode.INVALID_CODE)
        verify(session, "12x").error(422, ErrorCode.INVALID_CODE)
        clock.advance(Duration.ofMinutes(16))
        verify(session, code).error(422, ErrorCode.INVALID_CODE, ErrorReason.CODE_EXPIRED)

        // A new code works, and replaces the old one.
        postRaw(ApiRoutes.ME_EMAIL_RESEND, json = null, session.token).expect(204)
        val newCode = emails.awaitCode(email, count = 2)
        assertTrue(verify(session, " $newCode ").ok<UserProfile>().emailVerified)
        // Nothing to resend once confirmed.
        postRaw(ApiRoutes.ME_EMAIL_RESEND, json = null, session.token).expect(204)
        assertEquals(2, emails.sentTo(email).size)
    }

    @Test
    fun aCodeIsUsedUpAfterFiveWrongAttempts() {
        val (session, email) = register()
        val code = emails.awaitCode(email)

        repeat(4) { verify(session, wrong(code)).error(422, ErrorCode.INVALID_CODE) }
        // The fifth wrong one was the last: the code is gone.
        verify(session, wrong(code)).error(422, ErrorCode.INVALID_CODE, ErrorReason.CODE_EXPIRED)
        verify(session, code).error(422, ErrorCode.INVALID_CODE, ErrorReason.CODE_EXPIRED)
    }

    @Test
    fun loginByEmailOrNicknameAndTheSameErrorForEveryFailure() {
        val (_, email, nickname) = registerVerified()

        val byEmail = login(" ${email.uppercase()} ", PASSWORD).ok<AccountSession>()
        val byNickname = login(nickname.uppercase(), PASSWORD).ok<AccountSession>()
        assertEquals(byEmail.user, byNickname.user)
        assertTrue(byEmail.user.emailVerified)
        assertNotEquals(byEmail.token, byNickname.token)

        val wrongPassword = login(nickname, "not-$PASSWORD")
        val failures = listOf(
            wrongPassword,
            login(uniqueName(), PASSWORD),
            login("${uniqueName()}@example.com", PASSWORD),
            login(nickname, "x".repeat(200)),
        )
        for (response in failures) {
            response.error(403, ErrorCode.FORBIDDEN, ErrorReason.WRONG_CREDENTIALS)
            assertEquals(wrongPassword.body, response.body)
        }
    }

    @Test
    fun logout() {
        val (session) = register()
        postRaw(ApiRoutes.LOGOUT, json = null, session.token).expect(204)
        getRaw(ApiRoutes.ME, session.token).error(401, ErrorCode.UNAUTHORIZED, ErrorReason.SESSION_EXPIRED)
    }

    @Test
    fun tokensAndTheirErrors() {
        getRaw(ApiRoutes.ME, token = null).error(401, ErrorCode.UNAUTHORIZED)
        getRaw(ApiRoutes.ME, "nope").error(401, ErrorCode.UNAUTHORIZED, ErrorReason.SESSION_EXPIRED)
        // An optional account: none is fine, a bad one is not.
        assertEquals("guest", getRaw(TestRoutes.OPTIONAL, token = null).expect(200).body)
        getRaw(TestRoutes.OPTIONAL, "nope").error(401, ErrorCode.UNAUTHORIZED, ErrorReason.SESSION_EXPIRED)
        val (session) = registerVerified()
        assertEquals(session.user.id.value, getRaw(TestRoutes.OPTIONAL, session.token).expect(200).body)
        // A path variable of the wrong type is the client's mistake.
        getRaw("/api/v1/test/numbers/abc", token = null).error(400, ErrorCode.BAD_REQUEST)
        assertEquals("42", getRaw("/api/v1/test/numbers/42", token = null).expect(200).body)
    }

    @Test
    fun registrationIsValidated() {
        val nickname = uniqueName()
        val email = "$nickname@example.com"

        register("x", email).error(400, ErrorCode.BAD_REQUEST, ErrorReason.INVALID_NICKNAME)
        register("_$nickname", email).error(400, ErrorCode.BAD_REQUEST, ErrorReason.INVALID_NICKNAME)
        register(nickname, "not-an-email").error(400, ErrorCode.BAD_REQUEST, ErrorReason.INVALID_EMAIL)
        register(nickname, email, password = "short").error(400, ErrorCode.BAD_REQUEST, ErrorReason.INVALID_PASSWORD)
        register(nickname, email).expect(200)

        val other = uniqueName()
        register(nickname.uppercase(), "$other@example.com")
            .error(409, ErrorCode.WRONG_STATE, ErrorReason.NICKNAME_TAKEN)
        register(other, email.uppercase()).error(409, ErrorCode.WRONG_STATE, ErrorReason.EMAIL_TAKEN)
        // Nothing of the failed attempts stayed.
        register(other, "$other@example.com").expect(200)
    }

    @Test
    fun fixAMistypedEmail() {
        val (session, oldEmail) = register()
        val oldCode = emails.awaitCode(oldEmail)
        val (_, takenEmail) = register()

        postRaw(ApiRoutes.ME_EMAIL, ChangeEmailRequest(takenEmail.uppercase()).toJson(), session.token)
            .error(409, ErrorCode.WRONG_STATE, ErrorReason.EMAIL_TAKEN)
        val newEmail = "${uniqueName()}@example.com"
        val changed = postRaw(
            ApiRoutes.ME_EMAIL,
            ChangeEmailRequest(newEmail).toJson(),
            session.token,
        ).ok<UserProfile>()
        assertEquals(newEmail, changed.email)
        assertFalse(changed.emailVerified)

        // The code sent to the old address no longer confirms anything.
        verify(session, oldCode).error(422, ErrorCode.INVALID_CODE)
        assertTrue(verify(session, emails.awaitCode(newEmail)).ok<UserProfile>().emailVerified)
    }

    @Test
    fun passwordReset() {
        val (oldSession, email, nickname) = registerVerified()

        // The same answer for an address without an account, and no email.
        val nobody = "${uniqueName()}@example.com"
        requestReset(nobody).expect(204)
        requestReset(email.uppercase()).expect(204)
        val code = emails.awaitCode(email, purpose = EmailPurpose.RESET_PASSWORD)
        assertTrue(emails.sentTo(nobody).isEmpty())
        requestReset("nope").error(400, ErrorCode.BAD_REQUEST, ErrorReason.INVALID_EMAIL)

        val newPassword = "new-$PASSWORD"
        confirmReset(email, wrong(code), newPassword).error(422, ErrorCode.INVALID_CODE)
        confirmReset(nobody, code, newPassword).error(422, ErrorCode.INVALID_CODE, ErrorReason.CODE_EXPIRED)
        confirmReset(email, code, "short").error(400, ErrorCode.BAD_REQUEST, ErrorReason.INVALID_PASSWORD)
        val session = confirmReset(email, code, newPassword).ok<AccountSession>()
        assertEquals(oldSession.user.id, session.user.id)

        // Every other device is logged out; the old password is gone.
        getRaw(ApiRoutes.ME, oldSession.token).error(401, ErrorCode.UNAUTHORIZED, ErrorReason.SESSION_EXPIRED)
        getRaw(ApiRoutes.ME, session.token).expect(200)
        login(nickname, PASSWORD).error(403, ErrorCode.FORBIDDEN, ErrorReason.WRONG_CREDENTIALS)
        login(nickname, newPassword).expect(200)
        // A code works once.
        confirmReset(email, code, PASSWORD).error(422, ErrorCode.INVALID_CODE, ErrorReason.CODE_EXPIRED)
    }

    @Test
    fun passwordResetConfirmsTheEmail() {
        val (session, email) = register()
        requestReset(email).expect(204)
        val code = emails.awaitCode(email, purpose = EmailPurpose.RESET_PASSWORD)

        assertTrue(confirmReset(email, code, PASSWORD).ok<AccountSession>().user.emailVerified)
        getRaw(ApiRoutes.ME, session.token).error(401, ErrorCode.UNAUTHORIZED, ErrorReason.SESSION_EXPIRED)
    }

    @Test
    fun changingThePasswordLogsOutTheOtherDevices() {
        val (session, _, nickname) = registerVerified()
        val otherDevice = login(nickname, PASSWORD).ok<AccountSession>()
        val newPassword = "new-$PASSWORD"
        fun change(current: String, new: String) =
            postRaw(ApiRoutes.ME_PASSWORD, ChangePasswordRequest(current, new).toJson(), session.token)

        change("not-$PASSWORD", newPassword).error(403, ErrorCode.FORBIDDEN, ErrorReason.WRONG_CREDENTIALS)
        change(PASSWORD, "short").error(400, ErrorCode.BAD_REQUEST, ErrorReason.INVALID_PASSWORD)
        change(PASSWORD, newPassword).expect(204)

        getRaw(ApiRoutes.ME, session.token).expect(200)
        getRaw(ApiRoutes.ME, otherDevice.token).error(401, ErrorCode.UNAUTHORIZED, ErrorReason.SESSION_EXPIRED)
        login(nickname, newPassword).expect(200)
    }

    @Test
    fun deleteTheAccount() {
        val (session, email, nickname) = registerVerified()
        login(email, PASSWORD).expect(200)
        fun delete(password: String) =
            postRaw(ApiRoutes.ME_DELETE, DeleteAccountRequest(password).toJson(), session.token)

        delete("not-$PASSWORD").error(403, ErrorCode.FORBIDDEN, ErrorReason.WRONG_CREDENTIALS)
        delete(PASSWORD).expect(204)

        assertContains(deletions.deleted, session.user.id)
        getRaw(ApiRoutes.ME, session.token).error(401, ErrorCode.UNAUTHORIZED, ErrorReason.SESSION_EXPIRED)
        login(email, PASSWORD).error(403, ErrorCode.FORBIDDEN, ErrorReason.WRONG_CREDENTIALS)
        for (table in listOf("users", "account_sessions", "email_codes")) {
            val column = if (table == "users") "id" else "user_id"
            val left = jdbc.sql("SELECT count(*) FROM $table WHERE $column = :id")
                .param("id", session.user.id.value)
                .query(Int::class.java)
                .single()
            assertEquals(0, left, table)
        }
        // The nickname and the email are free again.
        register(nickname, email).expect(200)
    }

    private data class Registered(val session: AccountSession, val email: String, val nickname: String)

    private fun register(): Registered {
        val nickname = uniqueName()
        val email = "$nickname@example.com"
        return Registered(register(nickname, email).ok(), email, nickname)
    }

    private fun registerVerified(): Registered {
        val registered = register()
        val profile = verify(registered.session, emails.awaitCode(registered.email)).ok<UserProfile>()
        return registered.copy(session = registered.session.copy(user = profile))
    }

    private fun register(nickname: String, email: String, password: String = PASSWORD, language: String = "en") =
        postRaw(ApiRoutes.ACCOUNTS, RegisterRequest(nickname, email, password, language).toJson())

    private fun login(login: String, password: String) =
        postRaw(ApiRoutes.LOGIN, LoginRequest(login, password).toJson())

    private fun verify(session: AccountSession, code: String) =
        postRaw(ApiRoutes.ME_EMAIL_VERIFY, VerifyEmailRequest(code).toJson(), session.token)

    private fun requestReset(email: String) = postRaw(ApiRoutes.PASSWORD_RESET, PasswordResetRequest(email).toJson())

    private fun confirmReset(email: String, code: String, password: String) =
        postRaw(ApiRoutes.PASSWORD_RESET_CONFIRM, PasswordResetConfirmRequest(email, code, password).toJson())

    /** Another 6-digit code. */
    private fun wrong(code: String) = code.map { '0' + (it - '0' + 1) % 10 }.joinToString("")

    private data class Response(val status: Int, val body: String) {
        fun expect(status: Int) = also { assertEquals(status, this.status, body) }

        inline fun <reified T> ok(): T = protocolJson.decodeFromString(expect(200).body)

        /** An [ApiError] with [code] and exactly [reason] (null: none). */
        fun error(status: Int, code: ErrorCode, reason: ErrorReason? = null) {
            val error = protocolJson.decodeFromString<ApiError>(expect(status).body)
            assertEquals(code, error.code, body)
            if (reason == null) assertNull(error.reason, body) else assertEquals(reason, error.reason, body)
        }
    }

    private inline fun <reified T> T.toJson(): String = protocolJson.encodeToString(this)

    private fun postRaw(path: String, json: String?, token: String? = null): Response {
        val response = mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            if (json != null) content = json
            if (token != null) header("Authorization", "${ApiRoutes.AUTH_SCHEME} $token")
        }.andReturn().response
        return Response(response.status, response.getContentAsString(Charsets.UTF_8))
    }

    private fun getRaw(path: String, token: String?): Response {
        val response = mvc.get(path) {
            if (token != null) header("Authorization", "${ApiRoutes.AUTH_SCHEME} $token")
        }.andReturn().response
        return Response(response.status, response.getContentAsString(Charsets.UTF_8))
    }

    private companion object {
        const val PASSWORD = "correct horse battery"
    }
}
