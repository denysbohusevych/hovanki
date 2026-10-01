package app.hovanki.server.metrics

import app.hovanki.server.game.GameRegistry
import app.hovanki.server.live.GameSockets
import app.hovanki.shared.protocol.SocketClose
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
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
class ServerMetrics(private val registry: MeterRegistry) {
    // The same quantiles as http.server.requests (application.yaml): one server, so they need not be aggregated.
    private val syncTimers = SyncTransport.entries.associateWith { transport ->
        Timer.builder(SYNC_TIMER)
            .description("A game sync in the server: the wait for the game's lock, the work, the snapshot; no network")
            .tag("transport", transport.tag)
            .publishPercentiles(*PERCENTILES)
            .register(registry)
    }

    /** The syncs since the last [takeSyncWindow], while [windowed]: the field log's `srv` (ADR 0018 §2). */
    private val syncWindows = SyncTransport.entries.associateWith { SyncWindow() }

    /**
     * Whether [timeSync] keeps every sync's time for [takeSyncWindow]: only while somebody takes them (the field log's
     * `srv`, [app.hovanki.server.lab.FieldServerSampler]); off, nothing is kept.
     */
    @Volatile
    var windowed: Boolean = false

    private val socketCloses = ConcurrentHashMap<String, Counter>()

    /** [block] timed as one sync of [transport]; recorded when it throws too (a refused sync took time as well). */
    fun <T> timeSync(transport: SyncTransport, block: () -> T): T {
        if (!windowed) return syncTimers.getValue(transport).record(Supplier(block))
        val start = System.nanoTime()
        try {
            return syncTimers.getValue(transport).record(Supplier(block))
        } finally {
            syncWindows.getValue(transport).add(System.nanoTime() - start)
        }
    }

    /** The syncs of [transport] since the last call, their times in nanoseconds, and starts the next window. */
    fun takeSyncWindow(transport: SyncTransport): LongArray = syncWindows.getValue(transport).take()

    /**
     * A socket of the live channel closed with [code] (`SocketClose`, or the WebSocket's own): counted by code in
     * [SOCKET_CLOSE_COUNTER]. A code no side sends is counted as `other`: the tag's values stay few.
     */
    fun socketClosed(code: Int) {
        val tag = if (code in 1000..1015 || code in KNOWN_APP_CODES) code.toString() else "other"
        socketCloses.computeIfAbsent(tag) {
            Counter.builder(SOCKET_CLOSE_COUNTER)
                .description("Live channel sockets closed, by close code (docs/adr/0015-websockets.md)")
                .tag("code", it)
                .register(registry)
        }.increment()
    }

    /** At most [MAX] times kept per window: a window of 10 s never has as many syncs. */
    private class SyncWindow {
        private var times = LongArray(INITIAL)
        private var size = 0

        @Synchronized
        fun add(nanos: Long) {
            if (size == times.size) {
                if (size >= MAX) return
                times = times.copyOf(minOf(size * 2, MAX))
            }
            times[size++] = nanos
        }

        @Synchronized
        fun take(): LongArray = times.copyOf(size).also { size = 0 }

        companion object {
            const val INITIAL = 256
            const val MAX = 100_000
        }
    }

    companion object {
        const val SYNC_TIMER = "hovanki.game.sync"
        const val SOCKET_CLOSE_COUNTER = "hovanki.socket.close"
        val PERCENTILES = doubleArrayOf(0.5, 0.95, 0.99)

        /** The live channel's own close codes (`app.hovanki.shared.protocol.SocketClose`). */
        private val KNOWN_APP_CODES = setOf(
            SocketClose.SESSION_REJECTED,
            SocketClose.GAME_NOT_FOUND,
            SocketClose.TOO_MANY,
            SocketClose.POLL,
        )
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
