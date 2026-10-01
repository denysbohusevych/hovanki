package app.hovanki.server.lab

import app.hovanki.server.features.FeatureFlags
import app.hovanki.server.metrics.ServerMetrics
import app.hovanki.server.metrics.SyncTransport
import app.hovanki.shared.lab.SrvFields
import app.hovanki.shared.protocol.ServerFeature
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory
import java.time.Clock
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The server's numbers in every field run of a game in memory (docs/adr/0018-field-test-build.md §2, event `srv`):
 * every [FieldProperties.srvEvery], while the field log is on ([ServerFeature.FIELD_LOG]). The latencies of
 * `GameService.sync` in the window (from [ServerMetrics]), the 5xx and 429 answers since the last `srv` (Spring's
 * `http.server.requests`), the games, players and sockets in memory, the heap and the process's CPU. Off, it keeps
 * nothing: the syncs' times are not even collected.
 */
@Component
class FieldServerSampler(
    private val features: FeatureFlags,
    private val metrics: ServerMetrics,
    private val meters: MeterRegistry,
    private val writer: FieldEventWriter,
    private val clock: Clock,
) {
    private var lastMillis: Long? = null
    private var last5xx: Long? = null
    private var last429: Long? = null

    @Scheduled(fixedDelayString = "\${hovanki.field.srv-every:PT10S}")
    fun sample() {
        val on = features.isEnabled(ServerFeature.FIELD_LOG)
        metrics.windowed = on
        if (!on) {
            // The next window starts when the log is on again: no syncs or errors of the time it was off.
            lastMillis = null
            last5xx = null
            last429 = null
            SyncTransport.entries.forEach { metrics.takeSyncWindow(it) }
            return
        }
        val now = clock.millis()
        writer.srv(now, numbers(now))
    }

    /** The numbers of the window that ends [nowMillis]; starts the next one. */
    internal fun numbers(nowMillis: Long): JsonObject {
        val windowStart = lastMillis
        lastMillis = nowMillis
        val poll = metrics.takeSyncWindow(SyncTransport.POLL)
        val socket = metrics.takeSyncWindow(SyncTransport.SOCKET)
        val (errors5xx, errors429) = httpErrors()
        val since5xx = last5xx?.let { (errors5xx - it).coerceAtLeast(0) }
        val since429 = last429?.let { (errors429 - it).coerceAtLeast(0) }
        last5xx = errors5xx
        last429 = errors429
        val memory = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val cpu = (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.processCpuLoad?.takeIf { it >= 0 }
        return buildJsonObject {
            windowStart?.let { put(SrvFields.WINDOW, nowMillis - it) }
            val all = poll + socket
            put(SrvFields.SYNCS, all.size)
            percentiles(all, SrvFields.SYNC_P50, SrvFields.SYNC_P95)
            percentiles(poll, SrvFields.POLL_P50, SrvFields.POLL_P95)
            percentiles(socket, SrvFields.SOCKET_P50, SrvFields.SOCKET_P95)
            // The first window after the start has no count before it: the totals so far would say nothing.
            since5xx?.let { put(SrvFields.ERRORS_5XX, it) }
            since429?.let { put(SrvFields.ERRORS_429, it) }
            gauge("hovanki.sockets")?.let { put(SrvFields.SOCKETS, it) }
            gauge("hovanki.games")?.let { put(SrvFields.GAMES, it) }
            gauge("hovanki.players")?.let { put(SrvFields.PLAYERS, it) }
            put(SrvFields.HEAP_MB, memory.used / MB)
            if (memory.max > 0) put(SrvFields.HEAP_MAX_MB, memory.max / MB)
            cpu?.let { put(SrvFields.CPU, (it * 1000).roundToInt() / 1000.0) }
            put(SrvFields.DROPPED, writer.takeDropped())
        }
    }

    /** All the 5xx and all the 429 answers since the start, of every route. */
    private fun httpErrors(): Pair<Long, Long> {
        var errors5xx = 0L
        var errors429 = 0L
        for (timer in meters.find(HTTP_REQUESTS).timers()) {
            val status = timer.id.getTag("status") ?: continue
            when {
                status.startsWith("5") -> errors5xx += timer.count()
                status == "429" -> errors429 += timer.count()
            }
        }
        return errors5xx to errors429
    }

    private fun gauge(name: String): Int? = meters.find(name).gauge()?.value()?.takeIf { !it.isNaN() }?.roundToInt()

    private fun JsonObjectBuilder.percentiles(nanos: LongArray, p50: String, p95: String) {
        if (nanos.isEmpty()) return
        nanos.sort()
        put(p50, millis(nanos, 0.5))
        put(p95, millis(nanos, 0.95))
    }

    /** The nearest-rank percentile [p] of the sorted [nanos], in ms with a decimal. */
    private fun millis(nanos: LongArray, p: Double): Double {
        val rank = ceil(p * nanos.size).toInt().coerceIn(1, nanos.size)
        return (nanos[rank - 1] / 100_000.0).roundToInt() / 10.0
    }

    private companion object {
        const val HTTP_REQUESTS = "http.server.requests"
        const val MB = 1024 * 1024
    }
}
