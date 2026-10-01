package app.hovanki.shared.lab

import app.hovanki.shared.rules.OverflowArea
import app.hovanki.shared.rules.OverflowCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Puts the lab logs of several devices on one timeline and reads them (docs/radio-lab.md §4.5): who is who by their
 * advertisements, a timeline, a summary per stretch between marks and per direction (who heard whom), the pocket's
 * truth against the carry monitor, the overflow masks and the vibration attempts. The files it writes are for a person
 * and for the session that reads the experiments; nothing here decides anything. [devs]: the label of each file when
 * the caller knows it better than the log (the server knows who uploaded it); null or missing: as the log says. The
 * server's report ([LabReportBuilder]) is built on it too: its [sources] are read once, line by line, into lean
 * events ([LabEvents.read]). [window]: the events on the server's clock outside it are left out (counted as
 * [LabLogDevice.outside]), so a log with a clock far off can't stretch the timeline; null: every event.
 */
class LabMerge(sources: Sequence<LabLogSource>, devs: List<String?> = emptyList(), window: LongRange? = null) {
    /** [files]: name and text of each log. */
    constructor(files: List<Pair<String, String>>, devs: List<String?> = emptyList(), window: LongRange? = null) :
        this(files.asSequence().map { (name, text) -> LabLogSource(name, text.lineSequence()) }, devs, window)

    val devices: List<LabLogDevice>
    val events: List<LabEvent>

    init {
        val devices = ArrayList<LabLogDevice>()
        val all = ArrayList<LabEvent>()
        val pool = LabEvents.Pool()
        for ((index, source) in sources.withIndex()) {
            val (read, bad) = LabEvents.read(source.lines, devs.getOrNull(index), pool)
            val deviceEvents = if (window == null) read else read.filter { it.t in window }
            val sessions = deviceEvents.filter { it.k == "session" }
            val last = sessions.lastOrNull()
            devices += LabLogDevice(
                dev = devs.getOrNull(index) ?: deviceEvents.firstOrNull()?.dev ?: source.name,
                file = source.name,
                model = last?.string("model"),
                os = last?.string("os"),
                commit = last?.string("commit"),
                schema = sessions.mapNotNull { it.int("schema") }.maxOrNull(),
                offsets = deviceEvents.filter { it.k == "clock" }.mapNotNull { it.long("offset") },
                badLines = bad,
                events = deviceEvents.size,
                build = last?.string("build"),
                outside = read.size - deviceEvents.size,
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

    /** Who sent [token], by the logs' advertisements: `A`, `A+B` when several did, `?token` when nobody said. */
    fun sender(token: String?): String =
        token?.let { owners[it]?.sorted()?.joinToString("+") } ?: token?.let { "?$it" } ?: "?"

    private val marks: List<LabEvent> = events.filter { it.k == "mark" }

    fun problems(): List<String> = buildList {
        for (device in devices) {
            if (device.schema == null || device.schema !in SCHEMAS) {
                add("${device.file}: schema ${device.schema}, expected ${SCHEMAS.joinToString(" or ")}")
            }
            if (device.offsets.isEmpty()) add("${device.file}: the clock was never measured, its times are its own")
            if (device.badLines > 0) add("${device.file}: ${device.badLines} lines that are not lab events")
            if (device.outside > 0) add("${device.file}: ${device.outside} events outside the run's time, left out")
        }
        val commits = devices.mapNotNull { it.commit }.distinct()
        if (commits.size > 1) add("different commits: ${commits.joinToString()}")
        for ((token, devs) in owners) if (devs.size > 1) add("token $token advertised by ${devs.joinToString()}")
    }

    /** One line per event, marks set apart. */
    fun timeline(): String = buildString {
        appendLine("time (UTC, server) | dev | app | event")
        for (event in events) {
            val time = LabSchema.formatUtc(event.t).substring(11)
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

        "mask" -> "mask bits ${maskBits(event).sorted()} decoded ${maskDecoded(event)} ${event.int("rssi")} dBm " +
            "${event.string("api")}"

        ServerKinds.BAND -> if (isServer(event)) {
            "server band ${event.string(ServerFields.HEARD)} → ${event.string(ServerFields.OBSERVER)} " +
                "${event.string(ServerFields.FROM)} → ${event.string(ServerFields.BAND)} " +
                "(shadow ${event.string(ServerFields.SHADOW_BAND)})"
        } else {
            generic(event)
        }

        ServerKinds.CLAIM -> if (isServer(event)) {
            listOfNotNull(
                "CLAIM ${event.string(ServerFields.SEEKER)} → ${event.string(ServerFields.HIDER)}",
                event.string(ServerFields.OUTCOME),
                event.double(ServerFields.DISTANCE)?.let { "GPS ≥ $it m" },
                event.boolean(ServerFields.SHADOW_ACCEPT)?.let {
                    "proximity in the shadow: ${if (it) "yes" else "no"}"
                },
            ).joinToString(" · ")
        } else {
            generic(event)
        }

        else -> generic(event)
    }

    private fun generic(event: LabEvent): String = "${event.k} " + event.fields
        .filterKeys { it !in COMMON }
        .entries.joinToString(" ") { (key, value) -> "$key=${text(value)}" }

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
        for ((token, devs) in owners.entries.sortedBy { it.key }) {
            appendLine("- `$token`: ${devs.sorted().joinToString()}")
        }
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
            appendLine("### ${LabSchema.formatUtc(segment.start).substring(11)} +${round1(seconds)} s: $title")
            appendLine()
            val directions = directions(segment.start, segment.end)
            if (directions.isEmpty()) {
                appendLine("No readings.")
                continue
            }
            appendLine("| heard → by | api/via | readings | per s | median | p80 | min | max | longest gap, s | then |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|")
            for (direction in directions) {
                val gap = round1(direction.longestGapMillis / 1000.0)
                appendLine(
                    "| ${direction.from} → ${direction.to} | ${direction.channel} | ${direction.readings} | " +
                        "${round1(direction.perSecond)} | ${direction.medianRssi} | ${direction.p80Rssi} | " +
                        "${direction.minRssi} | ${direction.maxRssi} | $gap | ${direction.during.ifEmpty { "-" }} |",
                )
            }
            val inSegment = events.filter { it.k == "band" && it.t >= segment.start && it.t < segment.end }
            // The phone's `band` is the lab's smoothing of a token; the server's, a game's pair (ADR 0018 §3.3).
            val (serverBands, bands) = inSegment.partition(::isServer)
            if (bands.isNotEmpty()) {
                appendLine()
                val last = bands.groupBy { it.dev to it.string("token") }.map { (key, list) ->
                    "${sender(key.second)} → ${key.first}: ${list.last().string("band")}"
                }
                appendLine("Bands at the end (the lab's smoothing): ${last.joinToString("; ")}")
            }
            if (serverBands.isNotEmpty()) {
                appendLine()
                val last = serverBands.groupBy {
                    it.string(ServerFields.HEARD) to it.string(ServerFields.OBSERVER)
                }.map { (key, list) ->
                    val band = list.last()
                    "${key.first} → ${key.second}: ${band.string(ServerFields.BAND)} " +
                        "(shadow ${band.string(ServerFields.SHADOW_BAND)})"
                }
                appendLine("Bands at the end (the game's server): ${last.joinToString("; ")}")
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

    /** Who heard whom in `[start, end)`: [from] (by [sender]) → [to] (the listener) over [channel] (`api/via`). */
    data class Direction(
        val from: String,
        val to: String,
        val channel: String,
        val readings: Int,
        val perSecond: Double,
        val medianRssi: Int,
        val p80Rssi: Int,
        val minRssi: Int,
        val maxRssi: Int,
        /** The longest stretch without a reading, the stretch's bounds included. */
        val longestGapMillis: Long,
        /** What the listener's app went through in that gap. */
        val during: String,
    )

    /**
     * Every direction heard in `[start, end)`, sorted by sender, listener and channel: how often, how loud, the
     * longest gap. [sender]: who a token is ([LabMerge.sender] by default).
     */
    fun directions(start: Long, end: Long, sender: (String?) -> String = ::sender): List<Direction> {
        val seconds = (end - start) / 1000.0
        val readings = events.filter { it.k == "rx" && it.t >= start && it.t < end && it.int("rssi") != null }
        val grouped = readings.groupBy {
            Triple(sender(it.string("token")), it.dev, "${it.string("api")}/${it.string("via")}")
        }
        val sorted = grouped.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }, { it.key.third }))
        return sorted.map { (key, list) ->
            val rssi = list.mapNotNull { it.int("rssi") }.sorted()
            val times = listOf(start) + list.map { it.t } + end
            val (gapStart, gapEnd) = times.zipWithNext { a, b -> a to b }.maxBy { (a, b) -> b - a }
            Direction(
                from = key.first,
                to = key.second,
                channel = key.third,
                readings = list.size,
                perSecond = list.size / seconds.coerceAtLeast(0.001),
                medianRssi = percentile(rssi, 50)!!,
                p80Rssi = percentile(rssi, 80)!!,
                minRssi = rssi.first(),
                maxRssi = rssi.last(),
                longestGapMillis = gapEnd - gapStart,
                during = lifeDuring(key.second, gapStart, gapEnd),
            )
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
        val suspended = "no ticks for ${round1(missed / 1000.0)} s".takeIf { missed > LabSchema.TICK_GAP_MILLIS }
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

    fun carrySeconds(): List<CarrySecond> = carrySecondSequence().toList()

    /**
     * [carrySeconds] one by one. A device's seconds go from its first event to its last, at most
     * [MAX_CARRY_SPAN_MILLIS] (a clock far off must not make millions of them).
     */
    private fun carrySecondSequence(): Sequence<CarrySecond> = sequence {
        for ((dev, own) in events.groupBy { it.dev }) {
            if (own.none { it.k == "carry" || it.k == "motion" }) continue
            if (own.first().t !in PLAUSIBLE_MILLIS || own.last().t !in PLAUSIBLE_MILLIS) continue
            var place: String? = null
            var action: String? = null
            var carry: String? = null
            var motion: LabEvent? = null
            var prox: LabEvent? = null
            var lux: Double? = null
            var app: String? = null
            var index = 0
            var second = ceil(own.first().t / 1000.0).toLong() * 1000
            val end = minOf(own.last().t, second + MAX_CARRY_SPAN_MILLIS)
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
                yield(
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
    fun carryMatrices(): Map<String, Map<String, Map<String, Int>>> {
        val matrices = LinkedHashMap<String, MutableMap<String, MutableMap<String, Int>>>()
        for (s in carrySecondSequence()) {
            if (s.truth == NO_TRUTH) continue
            val row = matrices.getOrPut(s.dev) { LinkedHashMap() }.getOrPut(s.truth) { LinkedHashMap() }
            val said = s.carry ?: "none"
            row[said] = (row[said] ?: 0) + 1
        }
        return matrices
    }

    fun carryCsv(): String = buildString {
        appendLine("t_utc,dev,place,action,truth,carry,std,orient,activity,near,raw_cm,lux,app")
        for (s in carrySeconds()) {
            appendLine(
                listOf(
                    LabSchema.formatUtc(s.t), s.dev, s.place, s.action, s.truth, s.carry, s.std, s.orient, s.activity,
                    s.near, s.raw, s.lux, s.app,
                ).joinToString(",") { it?.toString().orEmpty() },
            )
        }
    }

    /** A mask heard ([event]), with what a probe advertised then ([expected]: the last probe `adv` of any device). */
    class MaskRow(val event: LabEvent, val bits: Set<Int>, val expected: Set<Int>?, val expectedFrom: String?) {
        /** Every bit the probe sent stands; null: no probe advertised then. */
        val match: Boolean? get() = expected?.let { bits.containsAll(it) }
        val decoded: List<String> get() = OverflowCode.decode(bits)
    }

    fun maskRows(): List<MaskRow> = buildList {
        var expected: Set<Int>? = null
        var expectedFrom: String? = null
        for (event in events) {
            if (event.k == "adv" && event.string("mode") == "overflow_probe") {
                when (event.string("action")) {
                    "start" -> {
                        expected = event.string("payload")?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet()
                        expectedFrom = event.dev
                    }

                    "stop" -> expected = null
                }
            }
            if (event.k != "mask") continue
            add(MaskRow(event, maskBits(event), expected, expectedFrom.takeIf { expected != null }))
        }
    }

    /** Every mask heard, with what the probe advertised then ([maskRows]). */
    fun masksCsv(): String = buildString {
        appendLine(
            "t_utc,dev,app,api,rssi,peer,hex,bits,expected,expected_from,match,extra,missing,decoded",
        )
        for (row in maskRows()) {
            val event = row.event
            val wanted = row.expected
            appendLine(
                listOf(
                    LabSchema.formatUtc(event.t), event.dev, event.app, event.string("api"), event.int("rssi"),
                    event.string("peer"), event.string("hex"), row.bits.sorted().joinToString(" "),
                    wanted?.sorted()?.joinToString(" "),
                    row.expectedFrom,
                    row.match, wanted?.let { (row.bits - it).sorted().joinToString(" ") },
                    wanted?.let { (it - row.bits).sorted().joinToString(" ") }, row.decoded.joinToString(" "),
                ).joinToString(",") { it?.toString().orEmpty() },
            )
        }
    }

    fun hapticsCsv(): String = buildString {
        appendLine("t_utc,dev,app,kind,result,error,reason,group")
        for (event in events.filter { it.k == "haptic" }) {
            appendLine(
                listOf(
                    LabSchema.formatUtc(event.t),
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
        /**
         * A mask's bits: read again from its raw bytes when it has them (the air's order, [OverflowArea.bitsOf]), so
         * logs written before the bit order was measured (2026-09-29) come out right too; else as logged (an iPhone
         * lists table UUIDs, no order involved).
         */
        fun maskBits(event: LabEvent): Set<Int> = event.string("hex")
            ?.takeIf { it.length == OverflowArea.MASK_BYTES * 2 }
            ?.let { hex ->
                OverflowArea.bitsOf(ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() })
            }
            ?: event.ints("bits").toSet()

        /**
         * Whether [event] is the game server's own (docs/adr/0018-field-test-build.md §3.3): its kinds share names with
         * the phones' (`band`, `mark`…) and mean other things, so a reader tells them apart by the device.
         */
        fun isServer(event: LabEvent): Boolean = event.dev == FieldKinds.SERVER_DEVICE

        /** The tokens a mask carries, decoded again from [maskBits]. */
        fun maskDecoded(event: LabEvent): List<String> = OverflowCode.decode(maskBits(event))

        /** The schemas this merge reads: 2 is 1 plus `run`, `seq` and the kinds `step` and `net`. */
        val SCHEMAS = 1..LabSchema.VERSION

        private val COMMON = LabEvents.COMMON

        /** The longest a device's pocket seconds go ([carrySeconds]): two days, more than any run. */
        const val MAX_CARRY_SPAN_MILLIS = 48 * 3_600_000L

        /** Times a log can have (the years 1970 to about 2286): beyond them the clock is garbage. */
        private val PLAUSIBLE_MILLIS = 0L..10_000_000_000_000L
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

        /** Nearest rank; null for no values. */
        fun percentile(sorted: List<Int>, percent: Int): Int? {
            if (sorted.isEmpty()) return null
            val rank = ceil(percent / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }

        internal fun round1(value: Double): String = ((value * 10).roundToLong() / 10.0).toString()

        private fun text(value: JsonElement): String =
            if (value is JsonPrimitive && value.isString) value.content else value.toString()

        private fun csvText(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
    }
}
