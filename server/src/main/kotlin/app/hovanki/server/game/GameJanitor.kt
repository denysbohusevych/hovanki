package app.hovanki.server.game

import app.hovanki.server.config.GameProperties
import app.hovanki.server.history.HistoryWriter
import app.hovanki.server.social.InviteRegistry
import app.hovanki.shared.protocol.GamePhase
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Deletes finished and abandoned games, including all location data (see GDPR notes in the ADR), and the invitations
 * that expired or whose games left the lobby. Brings every game up to date first: a game whose time ran out while
 * nobody asked finishes, and its history is saved ([HistoryWriter], docs/adr/0007-game-history-and-routes.md).
 */
@Component
class GameJanitor(
    private val registry: GameRegistry,
    private val properties: GameProperties,
    private val clock: Clock,
    private val invites: InviteRegistry,
    private val history: HistoryWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${hovanki.games.cleanup-interval:PT1M}")
    fun removeExpiredGames() {
        val now = clock.millis()
        val finished = ArrayList<GameRecord>()
        val removed = registry.removeIf { game ->
            synchronized(game) {
                game.advance(now)
                game.takeFinishedRecord()?.let(finished::add)
                game.isExpired(now, properties.finishedRetention.toMillis(), properties.idleRetention.toMillis())
            }
        }
        finished.forEach(history::save)
        if (removed > 0) log.info("Removed {} expired games, {} left", removed, registry.size())
        invites.sweep(now) { gameId ->
            val game = registry.get(gameId)
            game != null && synchronized(game) { game.phase == GamePhase.LOBBY }
        }
    }
}
