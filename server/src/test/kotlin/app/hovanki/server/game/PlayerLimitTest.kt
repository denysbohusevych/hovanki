package app.hovanki.server.game

import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.rules.shrinkingZone
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** How many players an ordinary game takes with the server's default settings (`hovanki.game.max-players`: 30). */
@SpringBootTest
class PlayerLimitTest(@Autowired private val games: GameService, @Autowired private val limits: GameLimitsProperties) {
    private val settings = GameSettings(zone = shrinkingZone(GeoPoint(50.4501, 30.5234)))

    @Test
    fun theDefaultIsThirty() {
        assertEquals(Game.MAX_PLAYERS, limits.maxPlayers)
        assertEquals(30, limits.maxPlayers)
    }

    @Test
    fun theThirtyFirstPlayerIsRefused() {
        val host = games.create(CreateGameRequest("Host", settings))
        val code = host.snapshot.joinCode
        repeat(Game.MAX_PLAYERS - 1) { games.join(JoinGameRequest(code, "Player $it")) }

        val error = assertFailsWith<GameException> { games.join(JoinGameRequest(code, "One too many")) }
        assertEquals(ErrorCode.WRONG_STATE, error.code)
    }
}
