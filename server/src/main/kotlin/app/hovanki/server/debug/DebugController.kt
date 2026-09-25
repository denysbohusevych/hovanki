package app.hovanki.server.debug

import app.hovanki.server.game.GameException
import app.hovanki.server.game.GameRegistry
import app.hovanki.shared.debug.DebugGameList
import app.hovanki.shared.debug.DebugGameState
import app.hovanki.shared.debug.DebugGameSummary
import app.hovanki.shared.debug.DebugRoutes
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameId
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

/**
 * Observer for end-to-end tests: every game with every position, claim and reveal, without authentication.
 * Exists only with the Spring profile `e2e` (see docs/e2e.md); a normal server has no such routes.
 */
@RestController
@Profile(DebugController.PROFILE)
class DebugController(private val registry: GameRegistry, private val clock: Clock) {
    init {
        LoggerFactory.getLogger(javaClass)
            .warn(
                "Profile '{}' is active: {} exposes all player positions. Never use it in production.",
                PROFILE,
                DebugRoutes.GAMES,
            )
    }

    @GetMapping(DebugRoutes.GAMES)
    fun games(): DebugGameList {
        val now = clock.millis()
        val summaries = registry.all().map { game ->
            synchronized(game) {
                game.advance(now)
                val state = game.debugState(now)
                DebugGameSummary(state.gameId, state.joinCode, state.phase, state.hostId, state.players.map { it.name })
            }
        }
        return DebugGameList(now, summaries)
    }

    // Both catch up with time first, like every game request does, so the observer sees the current state.

    @GetMapping(DebugRoutes.GAME)
    fun game(@PathVariable gameId: String): DebugGameState {
        val game = registry.get(GameId(gameId)) ?: throw GameException(ErrorCode.NOT_FOUND, "No such game")
        return synchronized(game) {
            val now = clock.millis()
            game.advance(now)
            game.debugState(now)
        }
    }

    companion object {
        const val PROFILE = "e2e"
    }
}
