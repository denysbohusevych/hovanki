package app.hovanki.e2e.lab

import app.hovanki.client.lab.LabFields
import app.hovanki.client.lab.LabLog
import app.hovanki.client.lab.LabPlaces
import app.hovanki.shared.rules.OverflowArea
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.ceil

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

/** What a device's file said about itself, and what was wrong with it. */
data class LabDevice(
    val dev: String,
    val file: String,
    val model: String?,
    val os: String?,
    val commit: String?,
    val schema: Int?,
    val offsets: List<Long>,
    val badLines: Int,
    val events: Int,
)

/**
 * Puts the lab logs of several devices on one timeline and reads them (docs/radio-lab.md §4.5): who is who by their
 * advertisements, a timeline, a summary per stretch between marks and per direction (who heard whom), the pocket's
 * truth against the carry monitor, the overflow masks and the vibration attempts. The files it writes are for a person
 * and for the session that reads the experiments; nothing here decides anything.
 */
class LabMerge(files: List<Pair<String, String>>) {
    val devices: List<LabDevice>
    val events: List<LabEvent>

    init {
        val devices = ArrayList<LabDevice>()
        val all = ArrayList<LabEvent>()
        for ((name, text) in files) {
            val (parsed, bad) = parse(text)
            val deviceEvents = onServerTime(parsed)
            val sessions = deviceEvents.filter { it.k == "session" }
            val last = sessions.lastOrNull()
            devices += LabDevice(
                dev = deviceEvents.firstOrNull()?.dev ?: name,
                file = name,
                model = last?.string("model"),
                os = last?.string("os"),
                commit = last?.string("commit"),
                schema = sessions.mapNotNull { it.int("schema") }.maxOrNull(),
                offsets = deviceEvents.filter { it.k == "clock" }.mapNotNull { it.long("offset") },
                badLines = bad,
                events = deviceEvents.size,
            )
            all += deviceEvents
        }
        this.devices = devices
        events = all.sortedWith(compareBy({ it.t }, { it.dev }, { it.mono }))
    }

    /** Token → the devices that advertised it (the lab's `adv` with a token; the bench notes too). */
    val owners: Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
        for (event in events) {
            val token = when (event.k) {
                "adv" -> event.string("token")
                "note" -> BENCH_TOKEN.find(event.string("text").orEmpty())?.groupValues?.get(1)
                else -> null
            } ?: continue
            getOrPut(token) { mutableSetOf() } += event.dev
        }
    }

    private fun sender(token: String?): String =
        token?.let { owners[it]?.sorted()?.joinToString("+") } ?: token?.let { "?$it" } ?: "?"

    private val marks: List<LabEvent> = events.filter { it.k == "mark" }

    fun problems(): List<String> = buildList {
        for (device in devices) {
            if (device.schema !=
                LabLog.SCHEMA
            ) {
                add("${device.file}: schema ${device.schema}, expected ${LabLog.SCHEMA}")
            }
            if (device.offsets.isEmpty()) add("${device.file}: the clock was never measured, its times are its own")
            if (device.badLines > 0) add("${device.file}: ${device.badLines} lines that are not lab events")
        }
        val commits = devices.mapNotNull { it.commit }.distinct()
        if (commits.size > 1) add("different commits: ${commits.joinToString()}")
        for ((token, devs) in owners) if (devs.size > 1) add("token $token advertised by ${devs.joinToString()}")
    }

    /** One line per event, marks set apart. */
    fun timeline(): String = buildString {
        appendLine("time (UTC, server) | dev | app | event")
        for (event in events) {
            val time = LabLog.formatUtc(event.t).substring(11)
            val what = describe(event)
            if (event.k == "mark") {
                appendLine("==== $time | ${event.dev} | ${event.app ?: "-"} | $what ====")
            } else {
                appendLine("$time | ${event.dev} | ${event.app ?: "-"} | $what")
            }
        }
    }

    private fun describe(event: LabEvent): String = when (event.k) {
        "mark" -> listOfNotNull(
            "MARK ${event.string("label")}",
            event.string("place"),
            event.string("action"),
            event.double("distance")?.let { "$it m" },
            event.string("by")?.let { "by $it" },
        ).joinToString(" · ")

        "rx" -> "rx ${event.string("token") ?: "-"} from ${sender(event.string("token"))} " +
            "${event.int("rssi")} dBm ${event.string("api")}/${event.string("via")}"

        "mask" -> "mask bits ${event.ints("bits")} decoded ${event.strings("decoded")} ${event.int("rssi")} dBm " +
            "${event.string("api")}"

        else ->
            "${event.k} " + event.fields
                .filterKeys { it !in COMMON }
                .entries.joinToString(" ") { (key, value) -> "$key=${text(value)}" }
    }

    /** The stretches between marks, and in each, every direction: who heard whom, how often, how loud, the gaps. */
    fun summary(): String = buildString {
        appendLine("# Radio lab: merged logs")
        appendLine()
        appendLine("## Devices")
        appendLine()
        appendLine("| dev | file | model | os | commit | clock offsets, ms | events |")
        appendLine("|---|---|---|---|---|---|---|")
        for (device in devices) {
            val offsets = device.offsets.takeIf { it.isNotEmpty() }?.let { "${it.min()}…${it.max()}" } ?: "never"
            appendLine(
                "| ${device.dev} | ${device.file} | ${device.model ?: "-"} | ${device.os ?: "-"} | " +
                    "${device.commit ?: "-"} | $offsets | ${device.events} |",
            )
        }
        appendLine()
        appendLine("## Who is who")
        appendLine()
        if (owners.isEmpty()) appendLine("No device said what it advertised.")
        for ((token, devs) in owners.toSortedMap()) appendLine("- `$token`: ${devs.sorted().joinToString()}")
        val problems = problems()
        if (problems.isNotEmpty()) {
            appendLine()
            appendLine("## Problems")
            appendLine()
            problems.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("## By stretch")
        for (segment in segments()) {
            appendLine()
            val title = segment.mark?.let(::describe) ?: "before the first mark"
            val seconds = (segment.end - segment.start) / 1000.0
            appendLine("### ${LabLog.formatUtc(segment.start).substring(11)} +${round1(seconds)} s: $title")
            appendLine()
            val readings = events.filter { it.k == "rx" && it.t >= segment.start && it.t < segment.end }
            if (readings.isEmpty()) {
                appendLine("No readings.")
                continue
            }
            appendLine("| heard → by | api/via | readings | per s | median | p80 | min | max | longest gap, s | then |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|")
            val directions = readings.groupBy {
                Triple(sender(it.string("token")), it.dev, "${it.string("api")}/${it.string("via")}")
            }
            for ((key, list) in directions.toSortedMap(compareBy({ it.first }, { it.second }, { it.third }))) {
                val rssi = list.mapNotNull { it.int("rssi") }.sorted()
                val times = listOf(segment.start) + list.map { it.t } + segment.end
                val gaps = times.zipWithNext { a, b -> a to b }
                val (gapStart, gapEnd) = gaps.maxBy { (a, b) -> b - a }
                val during = lifeDuring(key.second, gapStart, gapEnd)
                appendLine(
                    "| ${key.first} → ${key.second} | ${key.third} | ${list.size} | " +
                        "${round1(list.size / seconds.coerceAtLeast(0.001))} | ${percentile(rssi, 50)} | " +
                        "${percentile(rssi, 80)} | ${rssi.first()} | ${rssi.last()} | " +
                        "${round1((gapEnd - gapStart) / 1000.0)} | ${during.ifEmpty { "-" }} |",
                )
            }
            val bands = events.filter { it.k == "band" && it.t >= segment.start && it.t < segment.end }
            if (bands.isNotEmpty()) {
                appendLine()
                val last = bands.groupBy { it.dev to it.string("token") }.map { (key, list) ->
                    "${sender(key.second)} → ${key.first}: ${list.last().string("band")}"
                }
                appendLine("Bands at the end (the lab's smoothing): ${last.joinToString("; ")}")
            }
        }
        appendLine()
        appendLine("## The pocket: the marks' truth against the carry monitor, seconds")
        appendLine()
        for ((dev, matrix) in carryMatrices()) {
            appendLine("**$dev**")
            appendLine()
            val states = CARRY_STATES
            appendLine("| truth \\ said | ${states.joinToString(" | ")} |")
            appendLine("|---|${states.joinToString("") { "---|" }}")
            for (truth in TRUTHS) {
                val row = matrix[truth].orEmpty()
                appendLine("| $truth | ${states.joinToString(" | ") { (row[it] ?: 0).toString() }} |")
            }
            appendLine()
        }
    }

    /** What the listener's app went through in a gap: its life events and the ticks it missed (it was suspended). */
    private fun lifeDuring(dev: String, from: Long, to: Long): String {
        val life = events.filter {
            it.dev == dev && it.k == "life" && it.t in from..to
        }.mapNotNull { it.string("event") }
        // A device that never ticked says nothing about being suspended.
        val allTicks = ticks[dev] ?: return life.joinToString(", ")
        val inGap = allTicks.filter { it in from..to }
        val missed = (listOf(from) + inGap + to).zipWithNext { a, b -> b - a }.max()
        val suspended = "no ticks for ${round1(missed / 1000.0)} s".takeIf { missed > LabLog.TICK_GAP_MILLIS }
        return (life + listOfNotNull(suspended)).joinToString(", ")
    }

    private val ticks: Map<String, List<Long>> by lazy {
        events.filter { it.k == "tick" }.groupBy({ it.dev }, { it.t })
    }

    private class Segment(val start: Long, val end: Long, val mark: LabEvent?)

    private fun segments(): List<Segment> {
        if (events.isEmpty()) return emptyList()
        val first = events.first().t
        val last = events.last().t + 1
        val starts = marks.map { it.t to it }
        val result = ArrayList<Segment>()
        if (starts.isEmpty() || starts.first().first > first) {
            result += Segment(first, starts.firstOrNull()?.first ?: last, null)
        }
        for ((index, start) in starts.withIndex()) {
            result += Segment(start.first, starts.getOrNull(index + 1)?.first ?: last, start.second)
        }
        return result.filter { it.end > it.start }
    }

    /** A second of a device: the truth from its last mark with a place, and what its sensors said by then. */
    data class CarrySecond(
        val t: Long,
        val dev: String,
        val place: String?,
        val action: String?,
        val truth: String,
        val carry: String?,
        val std: Double?,
        val orient: String?,
        val activity: String?,
        val near: Boolean?,
        val raw: Double?,
        val lux: Double?,
        val app: String?,
    )

    fun carrySeconds(): List<CarrySecond> = buildList {
        for ((dev, own) in events.groupBy { it.dev }) {
            if (own.none { it.k == "carry" || it.k == "motion" }) continue
            var place: String? = null
            var action: String? = null
            var carry: String? = null
            var motion: LabEvent? = null
            var prox: LabEvent? = null
            var lux: Double? = null
            var app: String? = null
            var index = 0
            var second = ceil(own.first().t / 1000.0).toLong() * 1000
            val end = own.last().t
            while (second <= end) {
                while (index < own.size && own[index].t <= second) {
                    val event = own[index++]
                    app = event.app ?: app
                    when (event.k) {
                        "mark" -> {
                            event.string("place")?.let { place = it }
                            event.string("action")?.let { action = it }
                        }

                        "carry" -> carry = event.string("state")

                        "motion" -> motion = event

                        "prox" -> prox = event

                        "light" -> lux = event.double("lux")
                    }
                }
                add(
                    CarrySecond(
                        t = second,
                        dev = dev,
                        place = place,
                        action = action,
                        truth = truthOf(place),
                        carry = carry,
                        std = motion?.double("std"),
                        orient = motion?.string("orient"),
                        activity = motion?.string("activity"),
                        near = prox?.boolean("near"),
                        raw = prox?.double("raw"),
                        lux = lux,
                        app = app,
                    ),
                )
                second += 1000
            }
        }
    }

    /** Device → truth → what the carry monitor said → seconds; only the seconds with a truth. */
    fun carryMatrices(): Map<String, Map<String, Map<String, Int>>> = carrySeconds()
        .filter { it.truth != NO_TRUTH }
        .groupBy { it.dev }
        .mapValues { (_, seconds) ->
            seconds.groupBy { it.truth }.mapValues { (_, list) -> list.groupingBy { it.carry ?: "none" }.eachCount() }
        }

    fun carryCsv(): String = buildString {
        appendLine("t_utc,dev,place,action,truth,carry,std,orient,activity,near,raw_cm,lux,app")
        for (s in carrySeconds()) {
            appendLine(
                listOf(
                    LabLog.formatUtc(s.t), s.dev, s.place, s.action, s.truth, s.carry, s.std, s.orient, s.activity,
                    s.near, s.raw, s.lux, s.app,
                ).joinToString(",") { it?.toString().orEmpty() },
            )
        }
    }

    /** Every mask heard, with what the probe advertised then ([expected]: the last probe `adv` of any device). */
    fun masksCsv(): String = buildString {
        appendLine(
            "t_utc,dev,app,api,rssi,peer,hex,bits,bits_msb_first,expected,expected_from,match,extra,missing,decoded",
        )
        var expected: List<Int>? = null
        var expectedFrom: String? = null
        for (event in events) {
            if (event.k == "adv" && event.string("mode") == "overflow_probe") {
                when (event.string("action")) {
                    "start" -> {
                        expected = event.string("payload")?.split(',')?.mapNotNull { it.toIntOrNull() }
                        expectedFrom = event.dev
                    }

                    "stop" -> expected = null
                }
            }
            if (event.k != "mask") continue
            val bits = event.ints("bits").toSet()
            val msb = event.string("hex")?.let { hex ->
                val bytes = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
                OverflowArea.bitsOfMsbFirst(bytes)
            }
            val wanted = expected?.toSet()
            val match = wanted?.let { bits.containsAll(it) }
            appendLine(
                listOf(
                    LabLog.formatUtc(event.t), event.dev, event.app, event.string("api"), event.int("rssi"),
                    event.string("peer"), event.string("hex"), bits.sorted().joinToString(" "),
                    msb?.sorted()?.joinToString(" "),
                    wanted?.sorted()?.joinToString(" "),
                    expectedFrom.takeIf { wanted != null },
                    match, wanted?.let { (bits - it).sorted().joinToString(" ") },
                    wanted?.let { (it - bits).sorted().joinToString(" ") }, event.strings("decoded").joinToString(" "),
                ).joinToString(",") { it?.toString().orEmpty() },
            )
        }
    }

    fun hapticsCsv(): String = buildString {
        appendLine("t_utc,dev,app,kind,result,error,reason,group")
        for (event in events.filter { it.k == "haptic" }) {
            appendLine(
                listOf(
                    LabLog.formatUtc(event.t),
                    event.dev,
                    event.app,
                    event.string("kind"),
                    event.string("result"),
                    event.string("error")?.let(::csvText),
                    event.string("reason")?.let(::csvText),
                    event.int("group"),
                ).joinToString(",") { it?.toString().orEmpty() },
            )
        }
    }

    companion object {
        private val COMMON = setOf(LabFields.T, LabFields.DT, LabFields.MONO, LabFields.DEV, LabFields.K, LabFields.APP)
        private val BENCH_TOKEN = Regex("bench radio on as \\w+, token ([0-9a-f]{8})")
        const val NO_TRUTH = "-"
        private val TRUTHS = listOf("in_hand", "in_pocket", "not_pocket")
        private val CARRY_STATES = listOf("in_hand", "in_pocket", "unknown", "none")

        /** The truth of a mark's place for the carry monitor. */
        fun truthOf(place: String?): String = when (place) {
            null -> NO_TRUTH
            LabPlaces.HAND -> "in_hand"
            in LabPlaces.CARRIED_HIDDEN -> "in_pocket"
            else -> "not_pocket"
        }

        /** The lines of one file; lines that are no JSON objects are counted, not read. */
        private fun parse(text: String): Pair<List<Pair<JsonObject, Int>>, Int> {
            var bad = 0
            val parsed = text.lineSequence().withIndex().filter { it.value.isNotBlank() }.mapNotNull { (index, line) ->
                runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
                    ?.takeIf { it[LabFields.K] != null && it[LabFields.DT] != null }
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
         * offset, its own clock plus that first offset.
         */
        private fun onServerTime(lines: List<Pair<JsonObject, Int>>): List<LabEvent> {
            val firstOffset = lines.firstNotNullOfOrNull { (json, _) ->
                json.takeIf { it[LabFields.K]?.jsonPrimitive?.content == "clock" }
                    ?.get("offset")?.jsonPrimitive?.longOrNull
            }
            var measured = false
            return lines.map { (json, _) ->
                val k = json[LabFields.K]!!.jsonPrimitive.content
                if (k == "clock" && json["offset"] != null) measured = true
                val dt = json[LabFields.DT]!!.jsonPrimitive.longOrNull ?: 0L
                val written = json[LabFields.T]?.jsonPrimitive?.longOrNull ?: dt
                LabEvent(
                    t = if (!measured && firstOffset != null) dt + firstOffset else written,
                    dev = json[LabFields.DEV]?.jsonPrimitive?.content ?: "?",
                    k = k,
                    app = json[LabFields.APP]?.jsonPrimitive?.content,
                    mono = json[LabFields.MONO]?.jsonPrimitive?.longOrNull ?: 0L,
                    fields = json,
                )
            }
        }

        /** Nearest rank; null for no values. */
        fun percentile(sorted: List<Int>, percent: Int): Int? {
            if (sorted.isEmpty()) return null
            val rank = ceil(percent / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }

        private fun round1(value: Double): String = (Math.round(value * 10) / 10.0).toString()

        private fun text(value: JsonElement): String =
            if (value is JsonPrimitive && value.isString) value.content else value.toString()

        private fun csvText(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
    }
}
