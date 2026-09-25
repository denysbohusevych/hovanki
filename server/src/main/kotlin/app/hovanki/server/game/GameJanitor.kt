package app.hovanki.server.game

import app.hovanki.server.config.GameProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/** Deletes finished and abandoned games, including all location data (see GDPR notes in the ADR). */
@Component
class GameJanitor(
    private val registry: GameRegistry,
    private val properties: GameProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${hovanki.games.cleanup-interval:PT1M}")
    fun removeExpiredGames() {
        val now = clock.millis()
        val removed = registry.removeIf { game ->
            synchronized(game) {
                game.isExpired(now, properties.finishedRetention.toMillis(), properties.idleRetention.toMillis())
            }
        }
        if (removed > 0) log.info("Removed {} expired games, {} left", removed, registry.size())
    }
}
