package app.hovanki.server.metrics

import app.hovanki.server.debug.DebugController
import app.hovanki.server.features.FeatureFlags
import app.hovanki.server.game.GameLimitsProperties
import app.hovanki.shared.protocol.ServerFeature
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The main server on the field test's days (application-field.yaml alone, docs/deploy.md): the field log may be switched
 * on, 60 players in a game, the two rules in the shadow, and everything else production's: the health on the game's
 * port, no observer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("field")
class FieldProfileTest(
    @LocalServerPort private val port: Int,
    @Autowired private val flags: FeatureFlags,
    @Autowired private val limits: GameLimitsProperties,
    @Autowired private val context: ApplicationContext,
) {
    private val http = HttpClient.newHttpClient()

    @Test
    fun theFieldLogMayBeOnAndTheTwoRulesStayInTheShadow() {
        assertEquals(60, limits.maxPlayers)
        assertTrue(flags.isAllowed(ServerFeature.FIELD_LOG))
        assertTrue(flags.isShadowOnly(ServerFeature.PROXIMITY_CATCH))
        assertTrue(flags.isShadowOnly(ServerFeature.POCKET_STEALTH))
        assertFalse(flags.isShadowOnly(ServerFeature.FIELD_LOG))
    }

    @Test
    fun theHealthStaysOnTheGamesPortWithoutMetricsOrObserver() {
        assertEquals(200, get("/actuator/health").statusCode())
        assertEquals(404, get("/actuator/prometheus").statusCode())
        assertTrue(context.getBeansOfType(DebugController::class.java).isEmpty())
        assertEquals(404, get("/api/v1/debug/games").statusCode())
    }

    private fun get(path: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )
}
