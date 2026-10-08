package app.hovanki.server.game

import app.hovanki.shared.rules.BigGameLimits
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `hovanki.game.*`: what an ordinary game takes (a big game has its own limit: docs/adr/0010-big-games.md). The default
 * is [Game.MAX_PLAYERS]; the `field` profile raises it for the field test (docs/adr/0018-field-test-build.md).
 */
@ConfigurationProperties("hovanki.game")
data class GameLimitsProperties(
    /** How many players an ordinary game's lobby takes. */
    val maxPlayers: Int = Game.MAX_PLAYERS,
) {
    init {
        require(maxPlayers in MIN_PLAYERS..BigGameLimits.MAX_PLAYERS) {
            "hovanki.game.max-players must be $MIN_PLAYERS..${BigGameLimits.MAX_PLAYERS}, not $maxPlayers"
        }
    }

    private companion object {
        /** A round needs a seeker and a hider. */
        const val MIN_PLAYERS = 2
    }
}
