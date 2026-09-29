package app.hovanki.server.admin

import app.hovanki.server.account.AccountKeys
import app.hovanki.server.account.AccountService
import app.hovanki.server.account.UserRecord
import app.hovanki.server.account.UserRepository
import app.hovanki.server.game.GameException
import app.hovanki.server.game.IdGenerator
import app.hovanki.server.ratelimit.RateLimit
import app.hovanki.server.ratelimit.RateLimiter
import app.hovanki.shared.protocol.AdminAction
import app.hovanki.shared.protocol.AdminEnrollRequest
import app.hovanki.shared.protocol.AdminEnrollment
import app.hovanki.shared.protocol.AdminLoginRequest
import app.hovanki.shared.protocol.AdminLoginResponse
import app.hovanki.shared.protocol.AdminLoginStep
import app.hovanki.shared.protocol.AdminMe
import app.hovanki.shared.protocol.AdminTotpRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.LoginRequest
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserRole
import app.hovanki.shared.qr.QrCode
import app.hovanki.shared.totp.Totp
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** A staff member of an admin request (resolved from the session cookie on every request). */
data class Staff(
    val userId: UserId,
    val nickname: String,
    val role: UserRole,
    val tokenHash: String,
    val expiresAt: Instant,
) {
    val isAdmin: Boolean get() = role == UserRole.ADMIN

    fun toMe() = AdminMe(userId, nickname, role, expiresAt.toEpochMilli())

    override fun toString(): String = "Staff(${userId.value})"
}

/** A new admin session: [token] goes into the cookie. */
class AdminLogin(val token: String, val me: AdminMe)

/**
 * An admin request's session: its [staff] member and, when this request rotated the session's token, the [newToken]
 * for the cookie, which lives [cookieMaxAge] more (the rest of the session's maximum).
 */
class AdminAuthentication(val staff: Staff, val newToken: String?, val cookieMaxAge: Duration) {
    override fun toString(): String = "AdminAuthentication($staff, rotated=${newToken != null})"
}

/**
 * The staff login (docs/adr/0008-admin.md): the account's password, then a code of the authenticator app (RFC 6238,
 * [Totp]). The first time, the authenticator is set up with a code emailed to the account's confirmed address, so that
 * someone who only knows the password can't set up theirs. Between the steps, a challenge lives in memory for
 * [AdminProperties.challengeTtl], with [AdminProperties.challengeMaxAttempts] codes; a server restart just means logging
 * in again. Every time step of an authenticator works once.
 */
@Service
class StaffAuthService(
    private val users: UserRepository,
    private val accounts: AccountService,
    private val staff: StaffRepository,
    private val box: SecretBox,
    private val audit: AuditLog,
    private val ids: IdGenerator,
    private val rateLimiter: RateLimiter,
    private val properties: AdminProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private class Challenge(val userId: UserId, val expiresAt: Instant) {
        var attempts = 0
        var emailConfirmed = false
        var newSecret: ByteArray? = null
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val challenges = ConcurrentHashMap<String, Challenge>()
    private val transactions = TransactionTemplate(transactionManager)
    private val random = SecureRandom()

    /**
     * Step 1. Wrong password, unknown login and an account that isn't staff all answer the same
     * [ErrorReason.WRONG_CREDENTIALS], after the same work, with the limits of the app's login.
     */
    fun login(request: AdminLoginRequest, clientIp: String): AdminLoginResponse {
        val user = accounts.checkLogin(LoginRequest(request.login, request.password), clientIp)
        if (!user.role.isStaff) {
            throw GameException(
                ErrorCode.FORBIDDEN,
                "Wrong login or password",
                ErrorReason.WRONG_CREDENTIALS,
            )
        }
        val now = clock.instant()
        challenges.values.removeIf { it.expiresAt <= now }
        val enrolled = staff.totpSecret(user.id) != null
        if (!enrolled) {
            if (!user.emailVerified) {
                throw GameException(
                    ErrorCode.WRONG_STATE,
                    "Confirm your email in the app first",
                    ErrorReason.EMAIL_NOT_VERIFIED,
                )
            }
            try {
                accounts.sendStaffEnrollCode(user)
            } catch (e: GameException) {
                // Logging in again within a minute: the code sent a moment ago still works.
                if (e.reason != ErrorReason.TOO_MANY_REQUESTS) throw e
            }
        }
        val token = ids.token()
        challenges[AccountKeys.tokenHash(token)] = Challenge(user.id, now.plus(properties.challengeTtl))
        return AdminLoginResponse(
            challenge = token,
            next = if (enrolled) AdminLoginStep.TOTP else AdminLoginStep.ENROLL,
            emailHint = if (enrolled) null else EmailMask.mask(user.email),
        )
    }

    /** Step 2: the code of the authenticator app. */
    fun loginWithCode(request: AdminTotpRequest): AdminLogin {
        val (key, challenge) = challenge(request.challenge)
        val user = staffUser(challenge.userId)
        val sealed = staff.totpSecret(user.id)
            ?: throw GameException(ErrorCode.WRONG_STATE, "No authenticator set up: log in again")
        val secret = box.open(sealed)
        if (secret == null) {
            // Sealed with another key: set it up again.
            staff.deleteTotp(user.id)
            throw GameException(ErrorCode.WRONG_STATE, "Set up the authenticator again: log in again")
        }
        val step = checkCode(challenge, secret, request.code)
        if (!staff.useStep(user.id, step)) throw wrongCode()
        challenges.remove(key)
        return newSession(user)
    }

    /** Setting up, step 2: the emailed code; answers the new secret to add to the authenticator app. */
    fun enroll(request: AdminEnrollRequest): AdminEnrollment {
        val (_, challenge) = challenge(request.challenge)
        val user = staffUser(challenge.userId)
        if (staff.totpSecret(user.id) != null) {
            throw GameException(ErrorCode.WRONG_STATE, "The authenticator is already set up")
        }
        synchronized(challenge) {
            if (!challenge.emailConfirmed) {
                accounts.useStaffEnrollCode(user.id, request.emailCode)
                challenge.emailConfirmed = true
            }
            val secret = challenge.newSecret ?: ByteArray(SECRET_BYTES).also(random::nextBytes)
            challenge.newSecret = secret
            val encoded = Base32.encode(secret)
            val label = URLEncoder.encode("$ISSUER:${user.nickname}", Charsets.UTF_8).replace("+", "%20")
            val uri = "otpauth://totp/$label?secret=$encoded&issuer=$ISSUER&algorithm=SHA1&digits=6&period=30"
            val qr = QrCode.encode(uri)
            val rows = (0 until qr.size).map { y ->
                buildString(qr.size) { for (x in 0 until qr.size) append(if (qr[x, y]) '1' else '0') }
            }
            return AdminEnrollment(encoded, uri, rows)
        }
    }

    /** Setting up, step 3: the first code of the app confirms it; logs in. */
    fun confirmEnrollment(request: AdminTotpRequest): AdminLogin {
        val (key, challenge) = challenge(request.challenge)
        val secret = synchronized(challenge) { challenge.newSecret }
            ?: throw GameException(ErrorCode.WRONG_STATE, "Enter the emailed code first")
        val user = staffUser(challenge.userId)
        val step = checkCode(challenge, secret, request.code)
        val now = clock.instant()
        transactions.executeWithoutResult {
            staff.saveTotp(user.id, box.seal(secret), step, now)
            audit.record(user.asStaff(now), AdminAction.ENROLL_TOTP, now, targetUserId = user.id)
        }
        challenges.remove(key)
        return newSession(user)
    }

    /**
     * The staff member of an admin session token (the cookie), on every admin request: 401 when the session is unknown,
     * idle for [AdminProperties.sessionIdle], older than [AdminProperties.sessionMax], or the account is no longer staff.
     * The first request [AdminProperties.sessionRotate] after the token last changed gets a new one
     * ([AdminAuthentication.newToken], for the cookie). The replaced token still works for
     * [AdminProperties.sessionRotateGrace], for the requests already on their way; coming back after that, it ends the
     * session: somebody else has it (a stolen cookie works only until one of the two uses the session again).
     */
    fun authenticate(token: String): AdminAuthentication {
        val hash = AccountKeys.tokenHash(token)
        val session = staff.findSession(hash) ?: throw sessionEnded()
        val now = clock.instant()
        val replaced = hash != session.tokenHash
        if (replaced && session.previousUntil?.isAfter(now) != true) {
            staff.deleteSession(session.tokenHash)
            log.warn("An admin session of {} ended: its replaced token came back", session.userId.value)
            throw sessionEnded()
        }
        val user = users.findById(session.userId)
        if (session.lastUsedAt <= now.minus(properties.sessionIdle) ||
            session.createdAt <= now.minus(properties.sessionMax) ||
            user == null ||
            !user.role.isStaff
        ) {
            staff.deleteSession(session.tokenHash)
            throw sessionEnded()
        }
        var currentHash = session.tokenHash
        var newToken: String? = null
        if (!replaced && Duration.between(session.rotatedAt, now) >= properties.sessionRotate) {
            val next = ids.token()
            val nextHash = AccountKeys.tokenHash(next)
            if (staff.rotateSession(hash, nextHash, now, now.plus(properties.sessionRotateGrace))) {
                currentHash = nextHash
                newToken = next
            }
        } else if (Duration.between(session.lastUsedAt, now) >= TOUCH_INTERVAL) {
            staff.touchSession(session.tokenHash, now)
        }
        val endsAt = session.createdAt.plus(properties.sessionMax)
        val member =
            Staff(user.id, user.nickname, user.role, currentHash, minOf(endsAt, now.plus(properties.sessionIdle)))
        return AdminAuthentication(member, newToken, Duration.between(now, endsAt))
    }

    fun logout(member: Staff) {
        staff.deleteSession(member.tokenHash)
    }

    private fun newSession(user: UserRecord): AdminLogin {
        val token = ids.token()
        val now = clock.instant()
        val hash = AccountKeys.tokenHash(token)
        val member = user.asStaff(now, hash)
        transactions.executeWithoutResult {
            staff.createSession(hash, user.id, now)
            audit.record(member, AdminAction.LOGIN, now)
        }
        return AdminLogin(token, member.toMe())
    }

    /** Fresh from the login: the session ends after the idle time unless used (or the maximum, if shorter). */
    private fun UserRecord.asStaff(now: Instant, tokenHash: String = "") =
        Staff(id, nickname, role, tokenHash, now.plus(minOf(properties.sessionIdle, properties.sessionMax)))

    private fun challenge(token: String): Pair<String, Challenge> {
        val key = AccountKeys.tokenHash(token)
        val challenge = challenges[key]
        if (challenge == null || challenge.expiresAt <= clock.instant()) {
            challenges.remove(key)
            throw GameException(ErrorCode.UNAUTHORIZED, "Log in again", ErrorReason.SESSION_EXPIRED)
        }
        return key to challenge
    }

    /** Still staff: the role may have been taken away between the steps. */
    private fun staffUser(userId: UserId): UserRecord = users.findById(userId)?.takeIf { it.role.isStaff }
        ?: throw GameException(ErrorCode.UNAUTHORIZED, "Log in again", ErrorReason.SESSION_EXPIRED)

    /** The time step [code] belongs to; counts the attempt per challenge and per staff member. */
    private fun checkCode(challenge: Challenge, secret: ByteArray, code: String): Long {
        synchronized(challenge) {
            if (challenge.attempts >= properties.challengeMaxAttempts) {
                challenges.values.remove(challenge)
                throw GameException(
                    ErrorCode.UNAUTHORIZED,
                    "Too many wrong codes: log in again",
                    ErrorReason.SESSION_EXPIRED,
                )
            }
            challenge.attempts++
        }
        rateLimiter.acquire(RateLimit.ADMIN_TOTP, challenge.userId.value)
        val digits = code.filter { !it.isWhitespace() }
        if (digits.length != CODE_DIGITS || !digits.all(Char::isDigit)) throw wrongCode()
        return Totp(secret).matchingStep(digits, clock.millis()) ?: throw wrongCode()
    }

    private companion object {
        const val ISSUER = "Hovanki"
        const val SECRET_BYTES = 20
        const val CODE_DIGITS = 6

        /** Idle time is checked to the minute: no write on every request. */
        val TOUCH_INTERVAL: Duration = Duration.ofMinutes(1)

        fun wrongCode() = GameException(ErrorCode.INVALID_CODE, "Wrong code")

        fun sessionEnded() =
            GameException(ErrorCode.UNAUTHORIZED, "The admin session ended: log in again", ErrorReason.SESSION_EXPIRED)
    }
}

/** `d•••@gmail.com`: enough to recognize an address, not to read it. */
object EmailMask {
    fun mask(email: String): String {
        val at = email.lastIndexOf('@')
        if (at <= 0) return "•••"
        return "${email.first()}•••${email.substring(at)}"
    }
}
