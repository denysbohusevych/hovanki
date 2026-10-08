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
 * The field test's own server (application-staging.yaml with application-field.yaml through the group `staging`,
 * docs/adr/0018-field-test-build.md): 60 players in a game, and the metrics on the management port, which the internet
 * never reaches (deploy/compose.yaml publishes the game's port only).
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

        // The server records a request's time just after its answer is out, so the first scrape can be a moment early.
        val text = scrapeUntil { text ->
            QUANTILES.all { quantile ->
                hasQuantile(text, "http_server_requests_seconds", quantile) &&
                    hasQuantile(text, "hovanki_game_sync_seconds", quantile, "transport=\"poll\"")
            }
        }
        // What the server holds in memory.
        for (gauge in listOf("hovanki_games", "hovanki_players", "hovanki_sockets")) {
            assertContains(text, "\n$gauge ", message = gauge)
        }
        // The latency of every route and of the sync itself, as quantiles.
        for (quantile in QUANTILES) {
            assertTrue(hasQuantile(text, "http_server_requests_seconds", quantile), "http.server.requests p$quantile")
            assertTrue(
                hasQuantile(text, "hovanki_game_sync_seconds", quantile, "transport=\"poll\""),
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

    /** The management port's metrics, scraped again for up to five seconds until [ready] likes them. */
    private fun scrapeUntil(ready: (String) -> Boolean): String {
        val deadline = System.nanoTime() + SCRAPE_PATIENCE_NANOS
        while (true) {
            val metrics = get(managementPort, "/actuator/prometheus")
            assertEquals(200, metrics.statusCode())
            if (ready(metrics.body()) || System.nanoTime() > deadline) return metrics.body()
            Thread.sleep(SCRAPE_PAUSE_MILLIS)
        }
    }

    private fun hasQuantile(text: String, metric: String, quantile: String, tag: String? = null): Boolean =
        text.lineSequence().any {
            it.startsWith("$metric{") && "quantile=\"$quantile\"" in it && (tag == null || tag in it)
        }

    private fun get(port: Int, path: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private companion object {
        val QUANTILES = listOf("0.5", "0.95", "0.99")
        const val SCRAPE_PATIENCE_NANOS = 5_000_000_000L
        const val SCRAPE_PAUSE_MILLIS = 50L
    }
}
