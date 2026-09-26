package app.hovanki.server.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** The limits [RateLimiter] enforces; each is configured under `hovanki.rate-limits.<kebab-case name>`. */
enum class RateLimit {
    /** Failed logins per login (email or nickname), also failed password checks per account. */
    LOGIN_PER_LOGIN,

    /** Failed logins per client IP. */
    LOGIN_PER_IP,

    /** Registration attempts per client IP. */
    REGISTER_PER_IP,

    /** Emails with a code per account: the short limit ("send again" at most once a minute)... */
    EMAIL_PER_MINUTE,

    /** ... and the long one. */
    EMAIL_PER_HOUR,

    /** Password reset requests per email address, whether it has an account or not. */
    PASSWORD_RESET_PER_EMAIL,

    /** Password reset requests per client IP. */
    PASSWORD_RESET_PER_IP,

    /** Friend requests per account. */
    FRIEND_REQUESTS,

    /** Game invitations per account. */
    INVITES,

    /** Chat reports per account (or per player for guests). */
    REPORTS,
}

/** `hovanki.rate-limits.*`. Tests and the `e2e` profile turn them off ([enabled]); one test context turns them on. */
@ConfigurationProperties("hovanki.rate-limits")
data class RateLimitProperties(
    val enabled: Boolean = true,
    /** Memory bound: keys tracked at once; beyond it, the oldest windows are dropped. */
    val maxKeys: Int = 100_000,
    val loginPerLogin: Limit = Limit(10, Duration.ofMinutes(15)),
    val loginPerIp: Limit = Limit(30, Duration.ofMinutes(15)),
    val registerPerIp: Limit = Limit(20, Duration.ofHours(1)),
    val emailPerMinute: Limit = Limit(1, Duration.ofMinutes(1)),
    val emailPerHour: Limit = Limit(5, Duration.ofHours(1)),
    val passwordResetPerEmail: Limit = Limit(3, Duration.ofHours(1)),
    val passwordResetPerIp: Limit = Limit(10, Duration.ofHours(1)),
    val friendRequests: Limit = Limit(30, Duration.ofHours(1)),
    val invites: Limit = Limit(30, Duration.ofHours(1)),
    val reports: Limit = Limit(10, Duration.ofHours(1)),
) {
    /** At most [count] events per key within any [window]. */
    data class Limit(val count: Int, val window: Duration)

    fun limitOf(limit: RateLimit): Limit = when (limit) {
        RateLimit.LOGIN_PER_LOGIN -> loginPerLogin
        RateLimit.LOGIN_PER_IP -> loginPerIp
        RateLimit.REGISTER_PER_IP -> registerPerIp
        RateLimit.EMAIL_PER_MINUTE -> emailPerMinute
        RateLimit.EMAIL_PER_HOUR -> emailPerHour
        RateLimit.PASSWORD_RESET_PER_EMAIL -> passwordResetPerEmail
        RateLimit.PASSWORD_RESET_PER_IP -> passwordResetPerIp
        RateLimit.FRIEND_REQUESTS -> friendRequests
        RateLimit.INVITES -> invites
        RateLimit.REPORTS -> reports
    }
}
