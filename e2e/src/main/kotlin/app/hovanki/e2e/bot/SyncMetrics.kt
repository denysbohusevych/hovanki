package app.hovanki.e2e.bot

import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/**
 * Latency of `/sync` calls (over HTTP or the live channel's socket) and failed requests, collected over many bots.
 * Thread-safe. A failed request is an I/O error, a response lost on the way back, or any 4xx and 5xx (expected refusals
 * like `TOO_FAR` too); 5xx are also kept apart ([serverErrors]): a scenario never expects one.
 */
class SyncMetrics {
    private val syncMillis = ConcurrentLinkedQueue<Long>()
    private val failures = ConcurrentLinkedQueue<String>()
    private val serverFailures = ConcurrentLinkedQueue<String>()
    private val requests = AtomicInteger()
    private val uploads = AtomicInteger()

    fun record(exchange: Exchange) {
        requests.incrementAndGet()
        val status = exchange.status
        val request = "${exchange.method} ${exchange.path}"
        if (exchange.method == "POST" && exchange.path.endsWith("/events")) uploads.incrementAndGet()
        when {
            status == null -> failures += "$request -> I/O error"
            exchange.isResponseLost -> failures += "$request -> $status, response lost"
            status >= 400 -> failures += "$request -> $status"
        }
        if (status != null && status >= 500) serverFailures += "$request -> $status"
        val isSync = exchange.path.endsWith("/sync") || exchange.method == FakeNetwork.SOCKET_METHOD
        if (isSync && status != null && !exchange.isResponseLost) {
            syncMillis += exchange.durationMillis
        }
    }

    val requestCount: Int get() = requests.get()

    /** The field log's and the lab's uploads of events (`POST …/events`), sent so far. */
    val uploadCount: Int get() = uploads.get()

    val syncCount: Int get() = syncMillis.size

    /** Every `/sync` latency so far, roughly in the order they finished: the slice of a phase is a sublist. */
    val syncLatencies: List<Long> get() = syncMillis.toList()

    val errors: List<String> get() = failures.toList()

    /** Requests the server answered with a 5xx: a bug on the server, whatever the scenario does. */
    val serverErrors: List<String> get() = serverFailures.toList()

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
