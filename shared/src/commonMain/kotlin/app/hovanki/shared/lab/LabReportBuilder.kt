package app.hovanki.shared.lab

import kotlin.math.roundToLong

/**
 * One device's log of a run as the server has it: [lines] its chunks' lines in order (read once, so the server never
 * holds a whole log as text), [radarToken] what it advertised.
 */
class LabReportInput(
    val label: String,
    val deviceId: String,
    val radarToken: String?,
    val lines: () -> Sequence<String>,
) {
    /** [jsonl]: the log as one text. */
    constructor(label: String, deviceId: String, radarToken: String?, jsonl: String) :
        this(label, deviceId, radarToken, { jsonl.lineSequence() })
}

/**
 * Computes a run's report (docs/adr/0017-radar-techniques-and-big-run.md §5.5) from its devices' logs, on [LabMerge]
 * (one file per device, the device's label as the server knows it). A sender is the device whose radar token it was,
 * else whoever said they advertised it (the `adv` events), else `?`: a token nobody of the run advertised may be a
 * real game's nearby, and the report, kept longer than the logs, never names it. The steps are the stretches between
 * the `step` events of any device (the earliest of each step and revision), or, in logs without them, between the
 * run's marks (`by` = `run`); before the first, a stretch of index -1. [window]: the events outside it on the server's
 * clock are left out (a problem says how many), so one log's clock far off can't stretch the report. Pure: the caller
 * gives the time.
 */
object LabReportBuilder {
    const val VERSION = 1

    /** The sender of a token nobody in the run advertised. */
    const val UNKNOWN_SENDER = "?"

    fun build(
        runId: String,
        script: LabRunScript?,
        logs: List<LabReportInput>,
        nowMillis: Long,
        window: LongRange? = null,
    ): LabReport {
        val sources = logs.asSequence().map { LabLogSource(it.label, it.lines()) }
        val merge = LabMerge(sources, logs.map { it.label }, window)
        val byToken = logs.mapNotNull { input -> input.radarToken?.let { it to input.label } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, labels) -> labels.distinct().sorted().joinToString("+") }
        val sender: (String?) -> String = { token ->
            token?.let { byToken[it] ?: merge.owners[it]?.sorted()?.joinToString("+") } ?: UNKNOWN_SENDER
        }
        return LabReport(
            version = VERSION,
            runId = runId,
            computedAtMillis = nowMillis,
            scenarioId = script?.id,
            devices = logs.zip(merge.devices) { input, device ->
                LabReportDevice(
                    label = input.label,
                    deviceId = input.deviceId,
                    model = device.model,
                    os = device.os,
                    build = device.build,
                    commit = device.commit,
                    schema = device.schema,
                    events = device.events,
                    clockOffsetsMillis = device.offsets,
                    radarToken = input.radarToken,
                )
            },
            problems = merge.problems(),
            steps = stretches(merge, script).map { stretch ->
                LabReportStep(
                    index = stretch.index,
                    id = stretch.id,
                    title = stretch.title,
                    startMillis = stretch.start,
                    endMillis = stretch.end,
                    directions = merge.directions(stretch.start, stretch.end, sender).map { it.toReport() },
                )
            },
            carry = merge.carryMatrices().flatMap { (label, matrix) ->
                matrix.flatMap { (truth, said) ->
                    said.map { (state, seconds) -> LabReportCarry(label, truth, state, seconds) }
                }
            },
            masks = merge.maskRows().groupBy { it.event.dev }.map { (label, rows) ->
                LabReportMask(
                    label = label,
                    frames = rows.size,
                    matched = rows.count { it.match == true },
                    decoded = rows.count { it.decoded.isNotEmpty() },
                )
            },
            haptics = merge.events.filter { it.k == "haptic" }
                .groupBy { it.dev to (it.string("kind") ?: "?") }
                .map { (key, events) ->
                    val results = events.map { it.string("result") }
                    LabReportHaptic(
                        label = key.first,
                        kind = key.second,
                        played = results.count { it == "played" },
                        errors = results.count { it == "error" },
                        skipped = results.count { it == "skipped" },
                        engineStopped = results.count { it == "engine_stopped" },
                    )
                },
            battery = merge.events.filter { it.k == "battery" }.groupBy { it.dev }.map { (label, events) ->
                val levels = events.mapNotNull { it.double("level") }
                LabReportBattery(label, levels.firstOrNull(), levels.lastOrNull(), events.size)
            },
            ticks = merge.events.filter { it.k == "tick" }.groupBy { it.dev }.map { (label, events) ->
                // By the monotonic clock: a gap is the app suspended, whatever the wall clock did meanwhile.
                val intervals = events.zipWithNext { a, b -> b.mono - a.mono }
                val gaps = intervals.count { it > LabSchema.TICK_GAP_MILLIS }
                LabReportTicks(label, events.size, gaps, intervals.maxOrNull() ?: 0L)
            },
        )
    }

    private class Stretch(val index: Int, val id: String, val title: String, val start: Long, var end: Long = 0)

    private fun stretches(merge: LabMerge, script: LabRunScript?): List<Stretch> {
        val events = merge.events
        if (events.isEmpty()) return emptyList()
        val starts = stepStarts(merge, script).ifEmpty { markStarts(merge, script) }
        val first = events.first().t
        val last = events.last().t + 1
        val result = ArrayList<Stretch>()
        if (starts.isEmpty() || starts.first().start > first) {
            result += Stretch(-1, BEFORE, "Before the first step", first)
        }
        result += starts
        for ((index, stretch) in result.withIndex()) {
            stretch.end = result.getOrNull(index + 1)?.start ?: last
        }
        return result.filter { it.end > it.start }
    }

    /** From the `step` events (schema 2): the earliest of every step and revision, any device's. */
    private fun stepStarts(merge: LabMerge, script: LabRunScript?): List<Stretch> = merge.events
        .filter { it.k == "step" && it.int("index") != null }
        .groupBy { it.int("index")!! to (it.long("revision") ?: 0L) }
        .map { (key, events) ->
            val first = events.minBy { it.t }
            val id = first.string("id") ?: script?.steps?.getOrNull(key.first)?.id ?: "step ${key.first + 1}"
            val title = first.string("title") ?: script?.steps?.getOrNull(key.first)?.title ?: id
            Stretch(key.first, id, title, first.t)
        }
        .sortedBy { it.start }

    /**
     * From the run's marks (`run: <id>`, `by` = `run`, with the step's number): the devices mark a step within moments
     * of each other, so marks of one step closer than [SAME_STEP_MILLIS] are one start; a later one is a repeat.
     */
    private fun markStarts(merge: LabMerge, script: LabRunScript?): List<Stretch> {
        val result = ArrayList<Stretch>()
        for (mark in merge.events.filter { it.k == "mark" && it.string("by") == "run" && it.int("step") != null }) {
            val index = mark.int("step")!! - 1
            val id = mark.string("label")?.removePrefix("run: ") ?: script?.steps?.getOrNull(index)?.id ?: "?"
            val previous = result.lastOrNull()
            if (previous != null && previous.index == index && previous.id == id &&
                mark.t - previous.start < SAME_STEP_MILLIS
            ) {
                continue
            }
            result += Stretch(index, id, script?.steps?.getOrNull(index)?.title ?: id, mark.t)
        }
        return result
    }

    private fun LabMerge.Direction.toReport() = LabReportDirection(
        from = from,
        to = to,
        channel = channel,
        readings = readings,
        perSecond = (perSecond * 100).roundToLong() / 100.0,
        medianRssi = medianRssi,
        p80Rssi = p80Rssi,
        minRssi = minRssi,
        maxRssi = maxRssi,
        longestGapMillis = longestGapMillis,
        during = during,
    )

    private const val BEFORE = "before"
    private const val SAME_STEP_MILLIS = 2_000L
}
