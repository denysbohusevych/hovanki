package app.hovanki.server.metrics

import app.hovanki.server.game.GameRegistry
import app.hovanki.server.live.GameSockets
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.util.function.Supplier

/** How a phone asks [app.hovanki.server.game.GameService.sync]: a request of its own, or a frame of its live channel. */
enum class SyncTransport(val tag: String) {
    POLL("poll"),
    SOCKET("socket"),
}

/**
 * The server's own meters (docs/adr/0018-field-test-build.md, section 2); the HTTP ones (`http.server.requests`, the
 * latencies and status codes of every route) come from Spring Boot. Nothing here carries a game, a player, a token or a
 * position: counts and times only. Read at `/actuator/prometheus` (the `staging` profile, on the management port only)
 * and in the process through the [MeterRegistry].
 */
@Component
class ServerMetrics(registry: MeterRegistry) {
    // The same quantiles as http.server.requests (application.yaml): one server, so they need not be aggregated.
    private val syncTimers = SyncTransport.entries.associateWith { transport ->
        Timer.builder(SYNC_TIMER)
            .description("A game sync in the server: the wait for the game's lock, the work, the snapshot; no network")
            .tag("transport", transport.tag)
            .publishPercentiles(*PERCENTILES)
            .register(registry)
    }

    /** [block] timed as one sync of [transport]; recorded when it throws too (a refused sync took time as well). */
    fun <T> timeSync(transport: SyncTransport, block: () -> T): T =
        syncTimers.getValue(transport).record(Supplier(block))

    companion object {
        const val SYNC_TIMER = "hovanki.game.sync"
        val PERCENTILES = doubleArrayOf(0.5, 0.95, 0.99)
    }
}

/** The gauges of what the server holds in memory. */
@Configuration(proxyBeanMethods = false)
class ServerGauges {
    @Bean
    fun gameGauges(registry: GameRegistry, sockets: GameSockets): MeterBinder = MeterBinder { meters ->
        Gauge.builder("hovanki.games", registry) { it.size().toDouble() }
            .description("Games in memory, in any phase")
            .register(meters)
        Gauge.builder("hovanki.players", registry) { it.playerCount().toDouble() }
            .description("Players in the games in memory")
            .register(meters)
        Gauge.builder("hovanki.sockets", sockets) { it.count().toDouble() }
            .description("Open live channel sockets (docs/adr/0015-websockets.md)")
            .register(meters)
    }
}
