package app.hovanki.shared.lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** One event of a lab log (docs/radio-lab.md §4.1), its time put on the common timeline ([t], server time). */
class LabEvent(val t: Long, val dev: String, val k: String, val app: String?, val mono: Long, val fields: JsonObject) {
    fun string(key: String): String? = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun long(key: String): Long? = (fields[key] as? JsonPrimitive)?.longOrNull

    fun int(key: String): Int? = (fields[key] as? JsonPrimitive)?.intOrNull

    fun double(key: String): Double? = (fields[key] as? JsonPrimitive)?.doubleOrNull

    fun boolean(key: String): Boolean? = (fields[key] as? JsonPrimitive)?.booleanOrNull

    fun ints(key: String): List<Int> =
        (fields[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()

    fun strings(key: String): List<String> =
        (fields[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
}

/** One device's log for [LabMerge]: its [name] (the file's, or the device's) and its [lines], read once. */
class LabLogSource(val name: String, val lines: Sequence<String>)

/** What a device's log said about itself, and what was wrong with it ([outside]: events out of the merge's window). */
data class LabLogDevice(
    val dev: String,
    val file: String,
    val model: String?,
    val os: String?,
    val commit: String?,
    val schema: Int?,
    val offsets: List<Long>,
    val badLines: Int,
    val events: Int,
    val build: String? = null,
    val outside: Int = 0,
)

/** Reading one device's lab log: its lines, and its events on the server's clock. */
object LabEvents {
    /** The lines of one file with their numbers; lines that are no lab events are only counted (the second value). */
    fun parse(text: String): Pair<List<Pair<JsonObject, Int>>, Int> {
        var bad = 0
        val parsed = text.lineSequence().withIndex().filter { it.value.isNotBlank() }.mapNotNull { (index, line) ->
            runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
                ?.takeIf { it[LabFields.K] is JsonPrimitive && it[LabFields.DT] is JsonPrimitive }
                ?.let { it to index }
                ?: run {
                    bad++
                    null
                }
        }.toList()
        return parsed to bad
    }

    /**
     * The events of one device on the server's clock: `t` as written, but before the device first measured its
     * offset, its own clock plus that first offset. [dev]: the device's label when the caller knows it better than
     * the log (the server knows who uploaded it); null: as the log says.
     */
    fun onServerTime(lines: List<Pair<JsonObject, Int>>, dev: String? = null): List<LabEvent> {
        val firstOffset = lines.firstNotNullOfOrNull { (json, _) ->
            json.takeIf { it.primitive(LabFields.K)?.content == "clock" }?.primitive("offset")?.longOrNull
        }
        var measured = false
        return lines.map { (json, _) ->
            val k = json.primitive(LabFields.K)?.content ?: "?"
            if (k == "clock" && json["offset"] != null) measured = true
            val dt = json.primitive(LabFields.DT)?.longOrNull ?: 0L
            val written = json.primitive(LabFields.T)?.longOrNull ?: dt
            LabEvent(
                t = if (!measured && firstOffset != null) dt + firstOffset else written,
                dev = dev ?: json.primitive(LabFields.DEV)?.content ?: "?",
                k = k,
                app = json.primitive(LabFields.APP)?.content,
                mono = json.primitive(LabFields.MONO)?.longOrNull ?: 0L,
                fields = json,
            )
        }
    }

    /**
     * One device's log read line by line into its events on the server's clock, as [parse] and [onServerTime] do, but
     * lean, for a whole run's logs in one process (the server's report): the fields every event has are kept only as
     * [LabEvent]'s own properties, the rest in a small array map, the keys and the short values shared through [pool].
     * Returns the events and the count of lines that are no lab events.
     */
    fun read(lines: Sequence<String>, dev: String? = null, pool: Pool = Pool()): Pair<List<LabEvent>, Int> {
        var bad = 0
        val events = ArrayList<LabEvent>()
        // The device's own clock of the events before its first measured offset: those are put right below.
        val beforeClock = ArrayList<Long>()
        var firstOffset: Long? = null
        var measured = false
        for (line in lines) {
            if (line.isBlank()) continue
            val json = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
            val kind = json?.primitive(LabFields.K)
            val dtField = json?.primitive(LabFields.DT)
            if (json == null || kind == null || dtField == null) {
                bad++
                continue
            }
            val k = kind.content
            if (k == "clock") {
                if (firstOffset == null) firstOffset = json.primitive("offset")?.longOrNull
                if (json["offset"] != null) measured = true
            }
            val dt = dtField.longOrNull ?: 0L
            if (!measured) beforeClock += dt
            events += LabEvent(
                t = json.primitive(LabFields.T)?.longOrNull ?: dt,
                dev = pool.text(dev ?: json.primitive(LabFields.DEV)?.content ?: "?"),
                k = pool.text(k),
                app = json.primitive(LabFields.APP)?.content?.let(pool::text),
                mono = json.primitive(LabFields.MONO)?.longOrNull ?: 0L,
                fields = pool.lean(json),
            )
        }
        val offset = firstOffset
        if (offset != null) {
            for ((index, dt) in beforeClock.withIndex()) {
                val event = events[index]
                events[index] = LabEvent(dt + offset, event.dev, event.k, event.app, event.mono, event.fields)
            }
        }
        return events to bad
    }

    /**
     * One device's log as [read] reads it, but lazily, an event at a time: for a whole game's logs, never all of them
     * in memory (the field report, [FieldReportStream]). The events before the device first measured its offset wait
     * for it (put on the server's clock as [read] does), at most [maxBeforeClock] of them: a log that never measured
     * its clock goes on with its own times. Lines that are no lab events are counted in [bad].
     */
    fun stream(
        lines: Sequence<String>,
        dev: String? = null,
        pool: Pool = Pool(),
        bad: (Int) -> Unit = {},
        maxBeforeClock: Int = MAX_BEFORE_CLOCK,
    ): Sequence<LabEvent> = sequence {
        // The events before the first offset, with their own clock.
        val waiting = ArrayList<Pair<LabEvent, Long>>()
        var measured = false
        for (line in lines) {
            if (line.isBlank()) continue
            val json = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
            val kind = json?.primitive(LabFields.K)
            val dtField = json?.primitive(LabFields.DT)
            if (json == null || kind == null || dtField == null) {
                bad(1)
                continue
            }
            val k = kind.content
            val dt = dtField.longOrNull ?: 0L
            val event = LabEvent(
                t = json.primitive(LabFields.T)?.longOrNull ?: dt,
                dev = pool.text(dev ?: json.primitive(LabFields.DEV)?.content ?: "?"),
                k = pool.text(k),
                app = json.primitive(LabFields.APP)?.content?.let(pool::text),
                mono = json.primitive(LabFields.MONO)?.longOrNull ?: 0L,
                fields = pool.lean(json),
            )
            if (measured) {
                yield(event)
                continue
            }
            if (k == "clock" && json["offset"] != null) {
                measured = true
                // The events before it on the server's clock: their own clock plus this first offset, as [read] does.
                val offset = json.primitive("offset")?.longOrNull
                for ((before, ownClock) in waiting) {
                    yield(
                        if (offset == null) {
                            before
                        } else {
                            LabEvent(ownClock + offset, before.dev, before.k, before.app, before.mono, before.fields)
                        },
                    )
                }
                waiting.clear()
                yield(event)
                continue
            }
            waiting += event to dt
            if (waiting.size >= maxBeforeClock) {
                measured = true
                for ((before, _) in waiting) yield(before)
                waiting.clear()
            }
        }
        for ((before, _) in waiting) yield(before)
    }

    /**
     * The keys and short values of the events [read] puts together, shared among them: a run's readings repeat the
     * same few tokens, APIs and channels. Holds at most [MAX_POOLED] of each, then stops sharing new ones.
     */
    class Pool {
        private val texts = HashMap<String, String>()
        private val values = HashMap<JsonPrimitive, JsonPrimitive>()

        fun text(value: String): String = shared(texts, value)

        /** [json] without the fields every event has ([COMMON]): its own keys and values, shared. */
        fun lean(json: JsonObject): JsonObject {
            val size = json.keys.count { it !in COMMON }
            val keys = arrayOfNulls<String>(size)
            val elements = arrayOfNulls<JsonElement>(size)
            var index = 0
            for ((key, value) in json) {
                if (key in COMMON) continue
                keys[index] = text(key)
                elements[index] = if (value is JsonPrimitive && value.content.length <= MAX_POOLED_LENGTH) {
                    shared(values, value)
                } else {
                    value
                }
                index++
            }
            @Suppress("UNCHECKED_CAST")
            return JsonObject(ArrayMap(keys as Array<String>, elements as Array<JsonElement>))
        }

        private fun <T : Any> shared(pool: HashMap<T, T>, value: T): T =
            pool[value] ?: value.also { if (pool.size < MAX_POOLED) pool[it] = it }
    }

    /** A few entries in two arrays, in their order: a lean event's fields. */
    private class ArrayMap(private val names: Array<String>, private val elements: Array<JsonElement>) :
        AbstractMap<String, JsonElement>() {
        override val size: Int get() = names.size

        override fun get(key: String): JsonElement? {
            for (index in names.indices) if (names[index] == key) return elements[index]
            return null
        }

        override fun containsKey(key: String): Boolean = names.contains(key)

        override val entries: Set<Map.Entry<String, JsonElement>>
            get() = names.indices.mapTo(LinkedHashSet(names.size)) { Entry(names[it], elements[it]) }

        private class Entry(override val key: String, override val value: JsonElement) :
            Map.Entry<String, JsonElement>
    }

    /** The fields of every event, which [LabEvent] has as its own properties. */
    val COMMON = setOf(
        LabFields.T,
        LabFields.DT,
        LabFields.MONO,
        LabFields.DEV,
        LabFields.K,
        LabFields.APP,
        LabFields.RUN,
        LabFields.SEQ,
    )

    /** [stream]: the events that wait for the device's first offset, at most (a log's header comes first). */
    const val MAX_BEFORE_CLOCK = 1_000

    private const val MAX_POOLED = 50_000
    private const val MAX_POOLED_LENGTH = 40

    private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
}
