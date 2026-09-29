package app.hovanki.server.admin

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `hovanki.admin.*` (docs/adr/0008-admin.md). */
@ConfigurationProperties("hovanki.admin")
data class AdminProperties(
    /**
     * Base64 of 32 random bytes (`openssl rand -base64 32`): encrypts the staff's authenticator secrets in the
     * database. Empty: the admin is off, every `/api/v1/admin` route answers 404.
     */
    val secretKey: String = "",
    /** An admin session ends after this long without a request... */
    val sessionIdle: Duration = Duration.ofHours(24),
    /** ... and this long after the login in any case. */
    val sessionMax: Duration = Duration.ofDays(7),
    /** The session's token (the cookie) is replaced by the first request this long after it last changed... */
    val sessionRotate: Duration = Duration.ofMinutes(15),
    /** ... and the replaced one still works this long, for the requests already on their way. */
    val sessionRotateGrace: Duration = Duration.ofMinutes(1),
    /** Between the password and the code of the authenticator app. */
    val challengeTtl: Duration = Duration.ofMinutes(5),
    /** Wrong codes per login attempt; then the password again. */
    val challengeMaxAttempts: Int = 5,
    /** The audit log, and bans and chat bans after they ended, are deleted after this. */
    val auditRetention: Duration = Duration.ofDays(365),
) {
    val enabled: Boolean get() = secretKey.isNotBlank()
}
