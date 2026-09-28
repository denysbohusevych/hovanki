package app.hovanki.server.account

import app.hovanki.server.api.AuthenticatedUser
import app.hovanki.server.game.GameException
import app.hovanki.server.game.IdGenerator
import app.hovanki.server.mail.EmailPurpose
import app.hovanki.server.mail.EmailTemplates
import app.hovanki.server.mail.Mailer
import app.hovanki.server.moderation.SanctionService
import app.hovanki.server.ratelimit.RateLimit
import app.hovanki.server.ratelimit.RateLimiter
import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.ChangeEmailRequest
import app.hovanki.shared.protocol.ChangePasswordRequest
import app.hovanki.shared.protocol.DeleteAccountRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.LoginRequest
import app.hovanki.shared.protocol.PasswordResetConfirmRequest
import app.hovanki.shared.protocol.PasswordResetRequest
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.VerifyEmailRequest
import app.hovanki.shared.rules.AccountRules
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Accounts (docs/adr/0004-accounts-friends-chat.md): registration, confirming the email with an emailed code (optional:
 * an account works right away), login by email or nickname, password reset, account deletion, and [authenticate] for
 * every request with an account token.
 *
 * Transactions are explicit ([TransactionTemplate]) and short: BCrypt runs outside them (it takes ~100 ms, the pool
 * has 5 connections), and so does counting an attempt at an emailed code (a wrong code rolls nothing back).
 * Errors are [GameException]s with an [ErrorReason]; every failure of a login looks the same.
 */
@Service
class AccountService(
    private val users: UserRepository,
    private val sessions: AccountSessionRepository,
    private val codes: EmailCodeRepository,
    private val hasher: PasswordHasher,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val properties: AccountProperties,
    private val rateLimiter: RateLimiter,
    private val mailer: Mailer,
    private val beforeDeletion: ObjectProvider<BeforeAccountDeletion>,
    private val sanctions: SanctionService,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    /**
     * A new account, usable right away, and this device logged in. Its email is unconfirmed: a code goes there, and
     * entering it ([verifyEmail]) is optional.
     */
    fun register(request: RegisterRequest, clientIp: String): AccountSession {
        rateLimiter.acquire(RateLimit.REGISTER_PER_IP, clientIp)
        val nickname = AccountKeys.normalizeNickname(request.nickname)
        if (!AccountRules.isValidNickname(nickname)) {
            throw badRequest(
                ErrorReason.INVALID_NICKNAME,
                "Nickname: ${AccountRules.NICKNAME_MIN_LENGTH}..${AccountRules.NICKNAME_MAX_LENGTH} letters, digits, " +
                    "_ . -, starting with a letter or a digit",
            )
        }
        val email = validEmail(request.email)
        val passwordHash = hasher.hash(validPassword(request.password))
        val now = clock.instant()
        val user = UserRecord(
            id = ids.userId(),
            nickname = nickname,
            email = email,
            emailVerifiedAt = null,
            passwordHash = passwordHash,
            language = AccountRules.language(request.language),
            createdAt = now,
        )
        return inTransaction {
            users.insert(user)
            sendCode(user, EmailPurpose.VERIFY_EMAIL, now)
            newSession(user, now)
        }
    }

    /**
     * By email or nickname. An unknown login, a wrong password and a locked login all answer
     * [ErrorReason.WRONG_CREDENTIALS] after the same BCrypt work; failures count per login and per IP. A banned
     * account, with the right password, gets [ErrorReason.ACCOUNT_BANNED] and when the ban ends.
     */
    fun login(request: LoginRequest, clientIp: String): AccountSession =
        newSession(checkLogin(request, clientIp), clock.instant())

    /** The account of [request] if the password is right; the checks and limits of [login], no session. */
    fun checkLogin(request: LoginRequest, clientIp: String): UserRecord {
        val loginKey = AccountKeys.loginKey(request.login)
        rateLimiter.check(RateLimit.LOGIN_PER_IP, clientIp)
        rateLimiter.check(RateLimit.LOGIN_PER_LOGIN, loginKey)
        val user = users.findByLogin(request.login)
        if (!hasher.matches(request.password, user?.passwordHash) || user == null) {
            rateLimiter.record(RateLimit.LOGIN_PER_IP, clientIp)
            rateLimiter.record(RateLimit.LOGIN_PER_LOGIN, loginKey)
            throw wrongCredentials("Wrong login or password")
        }
        return user
    }

    fun logout(user: AuthenticatedUser) {
        sessions.delete(user.tokenHash)
    }

    fun me(user: AuthenticatedUser): UserProfile = userOf(user).toProfile()

    /**
     * Sends a reset code if [PasswordResetRequest.email] has an account. Answers the same either way (only a malformed
     * address or a rate limit on the address or the IP make a difference), so nobody learns which emails have one.
     */
    fun requestPasswordReset(request: PasswordResetRequest, clientIp: String) {
        rateLimiter.acquire(RateLimit.PASSWORD_RESET_PER_IP, clientIp)
        val email = validEmail(request.email)
        rateLimiter.acquire(RateLimit.PASSWORD_RESET_PER_EMAIL, AccountKeys.emailKey(email))
        val user = users.findByEmail(email) ?: return
        val now = clock.instant()
        transactions.executeWithoutResult {
            try {
                sendCode(user, EmailPurpose.RESET_PASSWORD, now)
            } catch (e: GameException) {
                // The account's own email limit: a 429 here only would tell that the account exists.
                if (e.reason != ErrorReason.TOO_MANY_REQUESTS) throw e
            }
        }
    }

    /** New password with the reset code: logs out every device, confirms the email, logs this device in. */
    fun confirmPasswordReset(request: PasswordResetConfirmRequest): AccountSession {
        val password = validPassword(request.newPassword)
        // An unknown email looks like an address that never got a code.
        val user = users.findByEmail(request.email) ?: throw codeExpired()
        val now = clock.instant()
        val code = checkCode(user.id, EmailPurpose.RESET_PASSWORD, request.code, now)
        val passwordHash = hasher.hash(password)
        return inTransaction {
            if (!codes.consume(user.id, EmailPurpose.RESET_PASSWORD, code)) throw codeExpired()
            users.updatePassword(user.id, passwordHash)
            // The code came to this address: it is confirmed now.
            users.markEmailVerified(user.id, now)
            sessions.deleteAll(user.id)
            newSession(user.copy(emailVerifiedAt = user.emailVerifiedAt ?: now, passwordHash = passwordHash), now)
        }
    }

    /**
     * Confirms the email with the code sent to it: optional, it only shows that the address reaches its owner (who can
     * then count on it for a password reset). An already confirmed email just returns the profile.
     */
    fun verifyEmail(user: AuthenticatedUser, request: VerifyEmailRequest): UserProfile {
        val record = userOf(user)
        if (record.emailVerified) return record.toProfile()
        val now = clock.instant()
        val code = checkCode(record.id, EmailPurpose.VERIFY_EMAIL, request.code, now)
        transactions.executeWithoutResult {
            if (!codes.consume(record.id, EmailPurpose.VERIFY_EMAIL, code)) throw codeExpired()
            users.markEmailVerified(record.id, now)
        }
        return record.copy(emailVerifiedAt = now).toProfile()
    }

    /** A new code for the unconfirmed email; nothing to do once it is confirmed. */
    fun resendCode(user: AuthenticatedUser) {
        val record = userOf(user)
        if (record.emailVerified) return
        val now = clock.instant()
        transactions.executeWithoutResult { sendCode(record, EmailPurpose.VERIFY_EMAIL, now) }
    }

    /**
     * Fixes a mistyped, unconfirmed email and sends a new code there; a confirmed email can't be changed (yet). Needs
     * the current password, checked first: an unconfirmed account lives on, and whoever controls its email can take
     * it over with a password reset, so a stolen session alone must not be enough to point it at another address.
     */
    fun changeEmail(user: AuthenticatedUser, request: ChangeEmailRequest): UserProfile {
        val record = userOf(user)
        checkPassword(record, request.password)
        if (record.emailVerified) throw GameException(ErrorCode.WRONG_STATE, "The email is already confirmed")
        val email = validEmail(request.email)
        val now = clock.instant()
        return inTransaction {
            if (!users.updateUnverifiedEmail(record.id, email)) {
                throw GameException(ErrorCode.WRONG_STATE, "The email is already confirmed")
            }
            // Codes sent to the old address must not confirm the new one.
            codes.deleteAll(record.id)
            val updated = record.copy(email = email)
            // A rate limit here rolls the change back: the address never changes without a code going there.
            sendCode(updated, EmailPurpose.VERIFY_EMAIL, now)
            updated.toProfile()
        }
    }

    /** Needs the current password; logs out every other device. */
    fun changePassword(user: AuthenticatedUser, request: ChangePasswordRequest) {
        val password = validPassword(request.newPassword)
        val record = userOf(user)
        checkPassword(record, request.currentPassword)
        val passwordHash = hasher.hash(password)
        transactions.executeWithoutResult {
            users.updatePassword(record.id, passwordHash)
            sessions.deleteAll(record.id, exceptTokenHash = user.tokenHash)
        }
    }

    /**
     * Needs the password. Runs every [BeforeAccountDeletion] (groups are handed over, invitations dropped), then
     * deletes the user and, by the foreign keys, everything of theirs, all in one transaction.
     */
    fun delete(user: AuthenticatedUser, request: DeleteAccountRequest) {
        val record = userOf(user)
        checkPassword(record, request.password)
        transactions.executeWithoutResult { deleteAccount(record.id) }
    }

    /**
     * Inside a transaction: runs every [BeforeAccountDeletion], then deletes the user and everything of theirs. Also
     * for staff deleting an account on its owner's written request (docs/adr/0008-admin.md).
     */
    fun deleteAccount(userId: UserId) {
        beforeDeletion.orderedStream().forEach { it.beforeDelete(userId) }
        users.delete(userId)
    }

    /**
     * Emails [user] the code that proves a staff member sets up their own authenticator (docs/adr/0008-admin.md).
     * Rate-limited like every code.
     */
    fun sendStaffEnrollCode(user: UserRecord) {
        val now = clock.instant()
        transactions.executeWithoutResult { sendCode(user, EmailPurpose.STAFF_ENROLL, now) }
    }

    /** Checks and uses up the code of [sendStaffEnrollCode]: wrong, 422 `INVALID_CODE`; used up or expired too. */
    fun useStaffEnrollCode(userId: UserId, code: String) {
        val hash = checkCode(userId, EmailPurpose.STAFF_ENROLL, code, clock.instant())
        if (!codes.consume(userId, EmailPurpose.STAFF_ENROLL, hash)) throw codeExpired()
    }

    /**
     * The account of an account token: [UserArgumentResolver][app.hovanki.server.api.UserArgumentResolver] calls it
     * for every request with one. Throws 401 [ErrorReason.SESSION_EXPIRED] for an unknown, revoked or idle token;
     * a confirmed email or not makes no difference.
     */
    fun authenticate(token: String): AuthenticatedUser {
        val tokenHash = AccountKeys.tokenHash(token)
        val session = sessions.find(tokenHash) ?: throw sessionExpired()
        val now = clock.instant()
        if (session.lastUsedAt < now.minus(properties.sessionIdleRetention)) {
            sessions.delete(tokenHash)
            throw sessionExpired()
        }
        // No write per request: the idle limit is months, an hour of precision is plenty.
        if (Duration.between(session.lastUsedAt, now) >= TOUCH_INTERVAL) sessions.touch(tokenHash, now)
        return AuthenticatedUser(session.userId, tokenHash)
    }

    private fun userOf(user: AuthenticatedUser): UserRecord = users.findById(user.userId) ?: throw sessionExpired()

    /** A banned account gets no session: [ErrorReason.ACCOUNT_BANNED] (after its password or code was right). */
    private fun newSession(user: UserRecord, now: Instant): AccountSession {
        sanctions.checkNotBanned(user.id)
        val token = ids.token()
        sessions.create(AccountKeys.tokenHash(token), user.id, now)
        return AccountSession(token, user.toProfile())
    }

    /** Inside a transaction: the email goes out after the commit. Rate-limited per account. */
    private fun sendCode(user: UserRecord, purpose: EmailPurpose, now: Instant) {
        rateLimiter.check(RateLimit.EMAIL_PER_HOUR, user.id.value)
        rateLimiter.acquire(RateLimit.EMAIL_PER_MINUTE, user.id.value)
        rateLimiter.acquire(RateLimit.EMAIL_PER_HOUR, user.id.value)
        val code = ids.emailCode()
        codes.save(user.id, purpose, codeHash(user.id, purpose, code), now.plus(properties.codeTtl), now)
        mailer.sendAfterCommit(EmailTemplates.render(user.email, purpose, user.language, code, properties.codeTtl))
    }

    /**
     * Counts one attempt at the live code (in its own statement, outside any transaction) and returns its hash if
     * [code] is right. Wrong: 422 `INVALID_CODE`; expired, used up or never sent: [ErrorReason.CODE_EXPIRED].
     */
    private fun checkCode(userId: UserId, purpose: EmailPurpose, code: String, now: Instant): String {
        val normalized = AccountRules.normalizeCode(code)
        if (!AccountRules.isCodeFormat(normalized)) throw wrongCode()
        val attempt = codes.useAttempt(userId, purpose, now, properties.codeMaxAttempts) ?: throw codeExpired()
        if (AccountKeys.sameHash(attempt.codeHash, codeHash(userId, purpose, normalized))) return attempt.codeHash
        // That was the last attempt: the next one could only say "expired", say it now.
        if (attempt.attempts >= properties.codeMaxAttempts) throw codeExpired()
        throw wrongCode()
    }

    /**
     * The current password for changing it or the email, or deleting the account; failures count like failed logins.
     */
    private fun checkPassword(user: UserRecord, password: String) {
        val key = "user:${user.id.value}"
        rateLimiter.check(RateLimit.LOGIN_PER_LOGIN, key)
        if (!hasher.matches(password, user.passwordHash)) {
            rateLimiter.record(RateLimit.LOGIN_PER_LOGIN, key)
            throw wrongCredentials("Wrong password")
        }
    }

    private fun codeHash(userId: UserId, purpose: EmailPurpose, code: String) =
        AccountKeys.codeHash(userId.value, purpose.name, code)

    private fun validEmail(email: String): String {
        val normalized = AccountRules.normalizeEmail(email)
        if (!AccountRules.isValidEmail(normalized)) throw badRequest(ErrorReason.INVALID_EMAIL, "Invalid email address")
        return normalized
    }

    private fun validPassword(password: String): String {
        if (!AccountRules.isValidPassword(password)) {
            throw badRequest(
                ErrorReason.INVALID_PASSWORD,
                "Password: ${AccountRules.PASSWORD_MIN_LENGTH}..${AccountRules.PASSWORD_MAX_LENGTH} characters",
            )
        }
        return password
    }

    private fun <T : Any> inTransaction(block: () -> T): T = checkNotNull(transactions.execute { block() })

    private companion object {
        val TOUCH_INTERVAL: Duration = Duration.ofHours(1)

        fun badRequest(reason: ErrorReason, message: String) = GameException(ErrorCode.BAD_REQUEST, message, reason)

        fun wrongCredentials(message: String) =
            GameException(ErrorCode.FORBIDDEN, message, ErrorReason.WRONG_CREDENTIALS)

        fun sessionExpired() =
            GameException(ErrorCode.UNAUTHORIZED, "Logged out: log in again", ErrorReason.SESSION_EXPIRED)

        fun wrongCode() = GameException(ErrorCode.INVALID_CODE, "Wrong code")

        fun codeExpired() =
            GameException(ErrorCode.INVALID_CODE, "The code has expired: ask for a new one", ErrorReason.CODE_EXPIRED)
    }
}
