package app.hovanki.server.account

import app.hovanki.server.MutableClock
import app.hovanki.server.game.GameException
import app.hovanki.server.mail.EmailPurpose
import app.hovanki.server.mail.EmailSender
import app.hovanki.server.mail.RecordingEmailSender
import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.ChangeEmailRequest
import app.hovanki.shared.protocol.DeleteAccountRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.PasswordResetConfirmRequest
import app.hovanki.shared.protocol.PasswordResetRequest
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.VerifyEmailRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [AccountService] on the test database; the same context as AccountApiTest. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountTestConfig::class)
class AccountServiceTest(
    @Autowired private val accounts: AccountService,
    @Autowired private val users: UserRepository,
    @Autowired emailSender: EmailSender,
    @Autowired private val clock: MutableClock,
    @Autowired private val deletions: DeletionRecorder,
    @Autowired private val jdbc: JdbcClient,
) {
    private val emails = emailSender as RecordingEmailSender

    @Test
    fun nicknamesAreNfkcNormalized() {
        val nickname = uniqueName("Bob")
        val session = register(nickname.fullwidth())
        // Stored in its plain form, and every spelling of it is the same nickname.
        assertEquals(nickname, session.user.nickname)
        assertEquals(session.user.id, users.findByNickname(" ${nickname.uppercase().fullwidth()} ")?.id)
        val taken = assertFailsWith<GameException> { register(nickname.lowercase(), "${uniqueName()}@example.com") }
        assertEquals(ErrorReason.NICKNAME_TAKEN, taken.reason)
    }

    @Test
    fun sessionsAreTouchedAtMostHourlyAndEndWhenIdle() {
        val session = register(uniqueName())
        val created = lastUsedAt(session)

        clock.advance(Duration.ofMinutes(30))
        assertEquals(session.user.id, accounts.authenticate(session.token).userId)
        assertEquals(created, lastUsedAt(session))
        clock.advance(Duration.ofMinutes(31))
        accounts.authenticate(session.token)
        assertEquals(clock.instant(), lastUsedAt(session))

        clock.advance(Duration.ofDays(181))
        val expired = assertFailsWith<GameException> { accounts.authenticate(session.token) }
        assertEquals(ErrorCode.UNAUTHORIZED, expired.code)
        assertEquals(ErrorReason.SESSION_EXPIRED, expired.reason)
        assertNull(lastUsedAt(session))
    }

    @Test
    fun authenticationKnowsWhetherTheEmailIsConfirmed() {
        val nickname = uniqueName()
        val session = register(nickname)
        val user = accounts.authenticate(session.token)
        assertFalse(user.emailVerified)

        accounts.verifyEmail(user, VerifyEmailRequest(emails.awaitCode("$nickname@example.com")))
        assertTrue(accounts.authenticate(session.token).emailVerified)
    }

    @Test
    fun codesAreStoredHashedAndANewOneReplacesTheOld() {
        val nickname = uniqueName()
        val email = "$nickname@example.com"
        val user = accounts.authenticate(register(nickname).token)
        val first = emails.awaitCode(email)
        accounts.resendCode(user)
        val second = emails.awaitCode(email, count = 2)

        val stored = jdbc.sql("SELECT code_hash FROM email_codes WHERE user_id = :id")
            .param("id", user.userId.value)
            .query(String::class.java)
            .single()
        assertNotEquals(second, stored)
        if (first != second) {
            val old = assertFailsWith<GameException> { accounts.verifyEmail(user, VerifyEmailRequest(first)) }
            assertEquals(ErrorCode.INVALID_CODE, old.code)
        }
        assertTrue(accounts.verifyEmail(user, VerifyEmailRequest(second)).emailVerified)
    }

    @Test
    fun aResetCodeDoesNotConfirmAnAddressItWasNotSentTo() {
        val nickname = uniqueName()
        val user = accounts.authenticate(register(nickname).token)
        accounts.requestPasswordReset(PasswordResetRequest("$nickname@example.com"), IP)
        val code = emails.awaitCode("$nickname@example.com", purpose = EmailPurpose.RESET_PASSWORD)

        val newEmail = "${uniqueName()}@example.com"
        accounts.changeEmail(user, ChangeEmailRequest(newEmail))
        val refused = assertFailsWith<GameException> {
            accounts.confirmPasswordReset(PasswordResetConfirmRequest(newEmail, code, "new-$PASSWORD"))
        }
        assertEquals(ErrorReason.CODE_EXPIRED, refused.reason)
        assertFalse(assertNotNull(users.findByEmail(newEmail)).emailVerified)
    }

    @Test
    fun deletionRunsTheHooksInItsTransaction() {
        val session = register(uniqueName())
        val user = accounts.authenticate(session.token)

        deletions.failing = true
        try {
            assertFailsWith<IllegalStateException> { accounts.delete(user, DeleteAccountRequest(PASSWORD)) }
        } finally {
            deletions.failing = false
        }
        assertNotNull(users.findById(user.userId))

        accounts.delete(user, DeleteAccountRequest(PASSWORD))
        assertNull(users.findById(user.userId))
        assertEquals(2, deletions.deleted.count { it == user.userId })
    }

    @Test
    fun passwordHasher() {
        val hasher = PasswordHasher(AccountProperties(bcryptStrength = 4))
        val hash = hasher.hash(PASSWORD)
        assertTrue(hasher.matches(PASSWORD, hash))
        assertFalse(hasher.matches("not-$PASSWORD", hash))
        assertFalse(hasher.matches(PASSWORD, null))
        // More than BCrypt's 72 bytes: never a match, and no exception.
        assertFalse(hasher.matches("ж".repeat(40), hash))
    }

    private fun register(
        nickname: String,
        email: String = "${AccountKeys.normalizeNickname(nickname)}@example.com",
    ): AccountSession = accounts.register(RegisterRequest(nickname, email, PASSWORD), IP)

    private fun lastUsedAt(session: AccountSession): Instant? =
        jdbc.sql("SELECT last_used_at FROM account_sessions WHERE token_hash = :hash")
            .param("hash", AccountKeys.tokenHash(session.token))
            .query(OffsetDateTime::class.java)
            .optional()
            .map { it.toInstant() }
            .orElse(null)

    /** `Bob` → `Ｂｏｂ`: the fullwidth forms that NFKC turns back into ASCII. */
    private fun String.fullwidth(): String = map { if (it in '!'..'~') it + 0xFEE0 else it }.joinToString("")

    private companion object {
        const val PASSWORD = "correct horse battery"
        const val IP = "192.0.2.1"
    }
}
