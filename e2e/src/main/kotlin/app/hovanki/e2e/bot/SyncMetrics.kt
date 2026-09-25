package app.hovanki.e2e.bot

import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/** Latency of `/sync` calls and failed requests, collected over many bots. Thread-safe. */
class SyncMetrics {
    private val syncMillis = ConcurrentLinkedQueue<Long>()
    private val failures = ConcurrentLinkedQueue<String>()
    private val requests = AtomicInteger()

    fun record(exchange: Exchange) {
        requests.incrementAndGet()
        val status = exchange.status
        if (status == null ||
            status >= 400
        ) {
            failures += "${exchange.method} ${exchange.path} -> ${status ?: "I/O error"}"
        }
        if (exchange.path.endsWith("/sync") && status != null) syncMillis += exchange.durationMillis
    }

    val requestCount: Int get() = requests.get()

    val syncCount: Int get() = syncMillis.size

    val errors: List<String> get() = failures.toList()

    fun syncPercentile(percent: Double): Long {
        val sorted = syncMillis.sorted()
        if (sorted.isEmpty()) return 0
        val index = (ceil(percent / 100 * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    fun summary(): String = String.format(
        Locale.ROOT,
        "requests=%d, sync=%d, p50=%d ms, p95=%d ms, p99=%d ms, max=%d ms, errors=%d",
        requestCount,
        syncCount,
        syncPercentile(50.0),
        syncPercentile(95.0),
        syncPercentile(99.0),
        syncPercentile(100.0),
        errors.size,
    )
}
