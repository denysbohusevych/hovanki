package app.hovanki.server.account

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `hovanki.accounts.*` (docs/adr/0004-accounts-friends-chat.md). */
@ConfigurationProperties("hovanki.accounts")
data class AccountProperties(
    /** BCrypt cost: 10 takes ~100 ms per check; tests use 4. */
    val bcryptStrength: Int = 10,
    /** How long an emailed code works. */
    val codeTtl: Duration = Duration.ofMinutes(15),
    /** Wrong entries of one code before it is used up. */
    val codeMaxAttempts: Int = 5,
    /** Logged-in devices unused for this long are logged out (and their sessions deleted by DataRetention). */
    val sessionIdleRetention: Duration = Duration.ofDays(180),
    /** Accounts whose email was never confirmed are deleted after this, freeing the nickname and the email. */
    val unverifiedRetention: Duration = Duration.ofDays(7),
    /** Unanswered friend requests are deleted after this. */
    val requestRetention: Duration = Duration.ofDays(90),
)
