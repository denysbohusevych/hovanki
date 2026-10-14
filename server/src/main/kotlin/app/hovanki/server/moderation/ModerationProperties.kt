package app.hovanki.server.moderation

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `hovanki.moderation.*`. */
@ConfigurationProperties("hovanki.moderation")
data class ModerationProperties(
    /** Reported chat messages (with their copied text) are deleted after this. */
    val reportRetention: Duration = Duration.ofDays(90),
    /** A user's active bans and chat bans are read from the database at most this often ([SanctionRepository]). */
    val sanctionCacheTtl: Duration = Duration.ofMinutes(1),
)
