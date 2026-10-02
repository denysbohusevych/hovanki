package app.hovanki.server.metrics

import app.hovanki.server.game.GameService
import app.hovanki.server.live.GameSockets
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.rules.shrinkingZone
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A server without the `staging` profile (production, local development): the metrics are collected but nobody can read
 * them over HTTP, and the health and the update's hold stay on the game's port (docs/adr/0018-field-test-build.md, §2).
 */
@SpringBootTest
@AutoConfigureMockMvc
class MetricsDefaultsTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val games: GameService,
    @Autowired private val sockets: GameSockets,
    @Autowired private val meters: MeterRegistry,
) {
    @Test
    fun noMetricsOverHttpWithoutTheStagingProfile() {
        mvc.get("/actuator/prometheus").andExpect { status { isNotFound() } }
        mvc.get("/actuator/metrics").andExpect { status { isNotFound() } }
        mvc.get("/actuator/health").andExpect { status { isOk() } }
        mvc.get("/actuator/restarthold").andExpect { status { isOk() } }
    }

    @Test
    fun theGaugesFollowWhatTheServerHolds() {
        val gamesBefore = gauge("hovanki.games")
        val playersBefore = gauge("hovanki.players")

        val settings = GameSettings(zone = shrinkingZone(GeoPoint(50.4501, 30.5234)))
        val host = games.create(CreateGameRequest("Host", settings))
        games.join(JoinGameRequest(host.snapshot.joinCode, "Guest"))

        assertEquals(gamesBefore + 1, gauge("hovanki.games"))
        assertEquals(playersBefore + 2, gauge("hovanki.players"))
        assertEquals(sockets.count().toDouble(), gauge("hovanki.sockets"))
    }

    private fun gauge(name: String): Double = meters.get(name).gauge().value()
}
