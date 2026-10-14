package app.hovanki.server.leaderboard

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `hovanki.leaderboard.*` (docs/adr/0020-leaderboard.md, section 5). */
@ConfigurationProperties("hovanki.leaderboard")
data class LeaderboardProperties(
    /** How long the world's and the cities' order is shown before it is computed again. */
    val freshFor: Duration = Duration.ofMinutes(1),
) {
    init {
        require(!freshFor.isNegative) { "hovanki.leaderboard.fresh-for must not be negative, not $freshFor" }
    }
}
