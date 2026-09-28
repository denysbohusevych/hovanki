package app.hovanki.server.bigGames

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `hovanki.big-games.*` (docs/adr/0010-big-games.md). */
@ConfigurationProperties("hovanki.big-games")
data class BigGameProperties(
    /** The lobby opens this long before the start; the signed-up players see the invitation then. */
    val lobbyOpensBefore: Duration = Duration.ofMinutes(30),
    /** How often the scheduler looks at the big games: opens lobbies, starts rounds, notes their end. */
    val tick: Duration = Duration.ofSeconds(10),
    /** Fewer than two players this long after the start: the big game is cancelled. */
    val startPatience: Duration = Duration.ofHours(1),
    /** Big games and their sign-ups are deleted this long after their end (DataRetention). */
    val retention: Duration = Duration.ofDays(90),
)
