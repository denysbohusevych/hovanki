package app.hovanki.server.metrics

import app.hovanki.server.debug.DebugController
import app.hovanki.server.game.Game
import app.hovanki.server.game.GameException
import app.hovanki.server.game.GameLimitsProperties
import app.hovanki.server.game.GameService
import app.hovanki.server.game.PlayerRef
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.rules.shrinkingZone
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The field test's server (application-staging.yaml, docs/adr/0018-field-test-build.md): 60 players in a game, and the
 * metrics on the management port, which the internet never reaches (deploy/compose.yaml publishes the game's port only).
 * The management port is random here (`management.server.port=0`) so that the tests don't fight over 8081; that it is
 * the profile's own one is [ProfileConfigTest]'s.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.server.port=0"],
)
@ActiveProfiles("staging")
class StagingProfileTest(
    @LocalServerPort private val port: Int,
    @LocalManagementPort private val managementPort: Int,
    @Autowired private val games: GameService,
    @Autowired private val limits: GameLimitsProperties,
    @Autowired private val context: ApplicationContext,
) {
    private val http = HttpClient.newHttpClient()
    private val settings = GameSettings(zone = shrinkingZone(GeoPoint(50.4501, 30.5234)))

    @Test
    fun thirtyOnePlayersJoinAndSixtyOneAreRefused() {
        assertEquals(60, limits.maxPlayers)
        val host = games.create(CreateGameRequest("Host", settings))
        val code = host.snapshot.joinCode
        // The host and 30 more: the 31st player, whom the default limit refuses.
        repeat(Game.MAX_PLAYERS) { games.join(JoinGameRequest(code, "Player $it")) }
        repeat(60 - Game.MAX_PLAYERS - 1) { games.join(JoinGameRequest(code, "Late player $it")) }

        val error = assertFailsWith<GameException> { games.join(JoinGameRequest(code, "One too many")) }
        assertEquals(ErrorCode.WRONG_STATE, error.code)
    }

    @Test
    fun noObserverInTheStagingProfile() {
        assertTrue(context.getBeansOfType(DebugController::class.java).isEmpty())
        assertEquals(404, get(port, "/api/v1/debug/games").statusCode())
    }

    @Test
    fun prometheusAnswersOnTheManagementPortOnly() {
        // Traffic to measure: a request of every kind the metrics tell apart.
        val host = games.create(CreateGameRequest("Host", settings))
        val session = host.session
        games.sync(PlayerRef(session.gameId, session.playerId), session.gameId, SyncRequest())
        assertEquals(200, get(port, ApiRoutes.TIME).statusCode())

        val metrics = get(managementPort, "/actuator/prometheus")
        assertEquals(200, metrics.statusCode())
        val text = metrics.body()
        // What the server holds in memory.
        for (gauge in listOf("hovanki_games", "hovanki_players", "hovanki_sockets")) {
            assertContains(text, "\n$gauge ", message = gauge)
        }
        // The latency of every route and of the sync itself, as quantiles.
        assertContains(text, "http_server_requests_seconds{")
        for (quantile in listOf("0.5", "0.95")) {
            assertTrue(
                text.lineSequence().any {
                    it.startsWith("http_server_requests_seconds{") &&
                        "quantile=\"$quantile\"" in it
                },
                "http.server.requests p$quantile",
            )
            assertTrue(
                text.lineSequence().any {
                    it.startsWith("hovanki_game_sync_seconds{") && "transport=\"poll\"" in it &&
                        "quantile=\"$quantile\"" in it
                },
                "the sync's p$quantile",
            )
        }
        // The health and the update's hold moved to the management port with it.
        assertEquals(200, get(managementPort, "/actuator/health").statusCode())
        // Held or not depends on the big games other tests left in the shared database.
        val hold = get(managementPort, "/actuator/restarthold")
        assertEquals(200, hold.statusCode())
        assertContains(hold.body(), "\"held\":")

        // The game's port, the one Caddy proxies, serves none of it.
        for (path in listOf("/actuator/prometheus", "/actuator/health", "/actuator/restarthold", "/actuator")) {
            assertEquals(404, get(port, path).statusCode(), path)
        }
    }

    private fun get(port: Int, path: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )
}
