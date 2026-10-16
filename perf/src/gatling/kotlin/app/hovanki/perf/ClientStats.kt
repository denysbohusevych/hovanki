package app.hovanki.perf

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The client's numbers of a run without Gatling ([UdpGames]): every request's latency by second and name, the failures
 * by message, the players coming and going. They are what perf/report.py reads from Gatling's simulation.log, written
 * as `client.json` in the run's report directory (docs/perf.md, «UDP»). Latencies in whole milliseconds, as Gatling's.
 * Each thread records into its own [Recorder]; the hot path takes no shared lock.
 */
class ClientStats(val startMillis: Long) {
    private val names = ConcurrentHashMap<String, Int>()
    private val nextName = AtomicInteger()
    private val recorders = CopyOnWriteArrayList<Recorder>()
    private val local = ThreadLocal.withInitial { Recorder().also(recorders::add) }
    private val users = ConcurrentHashMap<Int, Int>()

    /** A request [name] sent at [sentMillis] (epoch): answered after [latencyMillis], or failed with [message]. */
    fun record(name: String, sentMillis: Long, latencyMillis: Long, ok: Boolean, message: String? = null) {
        val second = ((sentMillis - startMillis) / 1000).toInt().coerceAtLeast(0)
        val recorder = local.get()
        if (ok) {
            recorder.ok(key(second, id(name)), latencyMillis.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        } else {
            recorder.ko(key(second, id(name)), key(second, id(message ?: "KO")))
        }
    }

    /** A player came ([started]) or left at [atMillis]: the run's players over time. */
    fun user(atMillis: Long, started: Boolean) {
        val second = ((atMillis - startMillis) / 1000).toInt().coerceAtLeast(0)
        users.merge(second, if (started) 1 else -1, Int::plus)
    }

    private fun id(name: String): Int = names.computeIfAbsent(name) { nextName.getAndIncrement() }

    private fun key(second: Int, name: Int): Long = (second.toLong() shl 32) or name.toLong()

    /** `client.json` for perf/report.py's `ClientLog`; [extra] goes along under its keys. */
    fun write(file: Path, generator: String, extra: JsonObject) {
        val byId = names.entries.associate { (name, id) -> id to name }
        val ok = HashMap<Long, HashMap<Int, Long>>()
        val ko = HashMap<Long, Long>()
        val messages = HashMap<Long, Long>()
        for (recorder in recorders) recorder.mergeInto(ok, ko, messages)
        fun second(key: Long): Int = (key ushr 32).toInt()
        fun name(key: Long): String = byId.getValue((key and 0xffffffffL).toInt())
        val json = buildJsonObject {
            put("generator", generator)
            put("start_ms", startMillis)
            putJsonArray("ok") {
                for ((key, latencies) in ok.entries.sortedBy { it.key }) {
                    add(
                        buildJsonArray {
                            add(JsonPrimitive(second(key)))
                            add(JsonPrimitive(name(key)))
                            add(buildJsonObject { latencies.forEach { (ms, n) -> put(ms.toString(), n) } })
                        },
                    )
                }
            }
            putJsonArray("ko") {
                for ((key, n) in ko.entries.sortedBy { it.key }) {
                    add(triple(second(key), name(key), n))
                }
            }
            putJsonArray("ko_messages") {
                for ((key, n) in messages.entries.sortedBy { it.key }) {
                    add(triple(second(key), name(key), n))
                }
            }
            putJsonObject("users") { users.toSortedMap().forEach { (second, n) -> put(second.toString(), n) } }
            for ((key, value) in extra) put(key, value)
        }
        Files.createDirectories(file.parent)
        Files.writeString(file, json.toString())
    }

    private fun triple(second: Int, name: String, n: Long) = buildJsonArray {
        add(JsonPrimitive(second))
        add(JsonPrimitive(name))
        add(JsonPrimitive(n))
    }

    /** One thread's records; its lock is only ever contended by [write] at the end. */
    private class Recorder {
        private val ok = HashMap<Long, HashMap<Int, Long>>()
        private val ko = HashMap<Long, Long>()
        private val messages = HashMap<Long, Long>()

        @Synchronized
        fun ok(key: Long, latencyMillis: Int) {
            ok.getOrPut(key) { HashMap() }.merge(latencyMillis, 1L, Long::plus)
        }

        @Synchronized
        fun ko(key: Long, messageKey: Long) {
            ko.merge(key, 1L, Long::plus)
            messages.merge(messageKey, 1L, Long::plus)
        }

        @Synchronized
        fun mergeInto(
            allOk: HashMap<Long, HashMap<Int, Long>>,
            allKo: HashMap<Long, Long>,
            allMessages: HashMap<Long, Long>,
        ) {
            for ((key, latencies) in ok) {
                val into = allOk.getOrPut(key) { HashMap() }
                latencies.forEach { (ms, n) -> into.merge(ms, n, Long::plus) }
            }
            ko.forEach { (key, n) -> allKo.merge(key, n, Long::plus) }
            messages.forEach { (key, n) -> allMessages.merge(key, n, Long::plus) }
        }
    }
}
