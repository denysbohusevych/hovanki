package app.hovanki.shared.lab

import app.hovanki.shared.rules.OverflowCode
import app.hovanki.shared.rules.Smoothings
import kotlin.math.ceil
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
 *
 * Step 4 (docs/radar-run.md §4) adds what the logs say about the radar's competitors, recomputed from the `rx` events:
 * the pairs' distances from the script's steps and the hand marks ([LabDistances]); the touches ([TouchDetector]); the
 * calibrations ([Calibrations]) and the bands of every smoothing under every calibration against the distances
 * ([BandErrors]: one pass over the readings for each of the nine); the witness ([WitnessInference], on `smooth.ema` +
 * `calib.none`, three devices or more); «without X» ([WithoutChannel]) for every channel heard; the carry matrices of
 * both classifiers; and last the technique cards ([TechniqueCards]) from all of that.
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
        val stretches = stretches(merge, script)
        val radar = radar(
            merge,
            script,
            stretches,
            sender,
            models = logs.zip(merge.devices) { input, device -> input.label to device.model }.toMap(),
        )
        val report = LabReport(
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
            steps = stretches.map { stretch ->
                LabReportStep(
                    index = stretch.index,
                    id = stretch.id,
                    title = stretch.title,
                    startMillis = stretch.start,
                    endMillis = stretch.end,
                    directions = merge.directions(stretch.start, stretch.end, sender).map { it.toReport() },
                )
            },
            carry = merge.carryMatricesByTech().flatMap { (tech, matrices) ->
                matrices.flatMap { (label, matrix) ->
                    matrix.flatMap { (truth, said) ->
                        said.map { (state, seconds) -> LabReportCarry(label, truth, state, seconds, tech) }
                    }
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
            noise = merge.events.filter { it.k == "air" }.groupBy { it.dev }.map { (label, events) ->
                val frames = events.map { it.int("frames") ?: 0 }
                LabReportNoise(
                    label = label,
                    seconds = events.size,
                    frames = frames.sum(),
                    iBeacons = events.sumOf { it.int("ibeacons") ?: 0 },
                    masks = events.sumOf { it.int("masks") ?: 0 },
                    apple = events.sumOf { it.int("apple") ?: 0 },
                    maxFramesPerSecond = frames.maxOrNull() ?: 0,
                )
            },
            touches = radar.touches.map { touch ->
                LabReportTouch(
                    pair = touch.pairKey,
                    atMillis = touch.t,
                    rssi = touch.rssi,
                    peaksG = touch.peaksG.mapValues { round2(it.value) },
                    markAtMillis = touch.truthT,
                )
            },
            touchSpreads = radar.touches.spreads().map {
                LabReportTouchSpread(it.pairKey, it.direction, it.touches, it.spreadDb, it.driftDb)
            },
            missedTouches = radar.missedTouches,
            calibrations = radar.calibrations.filter { it.offsetsDb.isNotEmpty() }.map { calibration ->
                LabReportCalibration(calibration.id, calibration.offsetsDb.mapValues { round2(it.value) })
            },
            bands = radar.bands.map {
                LabReportBands(
                    it.smoothing,
                    it.calibration,
                    it.seconds,
                    it.exact,
                    it.oneOff,
                    it.wrong,
                    round2(it.meanError),
                )
            },
            without = radar.without.map { LabReportWithout(it.tech, it.seconds, it.same, it.onlyChannel) },
            witness = radar.witness?.let { LabReportWitness(it.seconds, it.inferred, it.right, it.wrong, it.pairs) },
        )
        val cards = TechniqueCards.build(report, cardFacts(merge, script, stretches, radar, sender))
        return report.copy(
            cards = cards.map { LabReportCard(it.tech, it.verdict.name, it.criterion, it.numbers, it.missing) },
        )
    }

    /** What the radar's step 4 computed over the logs, before it goes into the report. */
    private class Radar(
        val distances: LabDistances,
        val touches: List<Touch>,
        val missedTouches: Int,
        val calibrations: List<Calibration>,
        val bands: List<BandError>,
        val without: List<WithoutResult>,
        val witness: WitnessResult?,
        val techs: Set<String>,
        /** Some reading names its channel (`tech`): the logs are from step 3 on. */
        val readingTechs: Boolean,
    )

    private fun radar(
        merge: LabMerge,
        script: LabRunScript?,
        stretches: List<Stretch>,
        sender: (String?) -> String,
        models: Map<String, String?>,
    ): Radar {
        val events = merge.events
        val marks = events.filter { it.k == "mark" }
        val distances = LabDistances(
            stretches.map { stretch ->
                val meters = script?.steps?.getOrNull(stretch.index)?.distances.orEmpty()
                LabDistanceStretch(stretch.start, stretch.end, if (stretch.index < 0) emptyMap() else meters)
            },
            marks,
        )
        val readings = events.filter { it.k == "rx" && it.int("rssi") != null }
        val touches = TouchDetector.find(events, sender, marks)
        val calibrations = listOf(
            Calibrations.none(),
            Calibrations.model(readings, sender, distances, models),
            Calibrations.touch(touches),
        )
        val pairs = BandErrors.pairsOf(events)
        val seconds = secondsOf(events)
        var plain: BandTrack? = null
        val bands = Smoothings.ALL.flatMap { smoothing ->
            calibrations.map { calibration ->
                val track = BandTrack(smoothing, calibration)
                for (rx in readings) track.feed(rx, sender)
                if (smoothing == Smoothings.EMA && calibration.id == Calibrations.NONE) plain = track
                BandErrors.of(track, distances, pairs, seconds)
            }
        }
        val devices = events.map { it.dev }.distinct().sorted()
        val techs = readings.filter { sender(it.string("token")).let { from -> from != it.dev && from in devices } }
            .mapTo(LinkedHashSet()) { LabMerge.techOf(it) }
        return Radar(
            distances = distances,
            touches = touches,
            missedTouches = TouchDetector.missed(marks, touches).size,
            calibrations = calibrations,
            // Without a distance nothing was measured: no rows rather than nine of zeros.
            bands = bands.takeIf { list -> list.any { it.seconds > 0 } }.orEmpty(),
            without = WithoutChannel.all(events, sender, techs.sorted(), seconds, all = plain),
            witness = plain?.takeIf { devices.size >= WITNESS_DEVICES }
                ?.let { WitnessInference.run(it, devices, distances, seconds) },
            techs = techs,
            readingTechs = readings.any { it.string("tech") != null },
        )
    }

    /** The whole seconds from the first event to the last, at most [LabMerge.MAX_CARRY_SPAN_MILLIS] of them. */
    private fun secondsOf(events: List<LabEvent>): LongProgression {
        if (events.isEmpty()) return LongRange.EMPTY
        val first = ceil(events.first().t / 1000.0).toLong() * 1000
        return first..minOf(events.last().t, first + LabMerge.MAX_CARRY_SPAN_MILLIS) step 1000
    }

    /** The facts of the logs the cards read ([CardFacts]), by the report's steps: one pass over the events. */
    private fun cardFacts(
        merge: LabMerge,
        script: LabRunScript?,
        stretches: List<Stretch>,
        radar: Radar,
        sender: (String?) -> String,
    ): CardFacts {
        val events = merge.events
        val labels = events.map { it.dev }.distinct().sorted()
        val starts = LongArray(stretches.size) { stretches[it].start }
        fun stepOf(t: Long): Int = lastAtOrBefore(starts, t).takeIf { it >= 0 && t < stretches[it].end } ?: -1

        val distances = stretches.map { stretch ->
            val middle = (stretch.start + stretch.end) / 2
            labels.flatMapIndexed { index, a -> labels.drop(index + 1).map { b -> RunStep.pairKey(a, b) } }
                .mapNotNull { pair -> radar.distances.at(pair, middle)?.let { pair to it } }
                .toMap()
        }
        // Per step and device: the events with the app's state, and those on the screen.
        val appEvents = List(stretches.size) { HashMap<String, IntArray>() }
        val advertised = List(stretches.size) { HashMap<String, MutableSet<String>>() }
        val places = List(stretches.size) { HashMap<String, String>() }
        val active = HashMap<String, MutableSet<String>>()
        val lastPlace = HashMap<String, String>()
        val monitors = events.filter { it.k == "scan" && it.string("api") == REGION_API }.mapTo(HashSet()) { it.dev }
        val regionEvents = events.filter { it.k == "scan" && it.string("action") in REGION_ACTIONS }
        val regionWaits = ArrayList<RegionWait>()
        val beaconStarted = HashSet<Pair<Int, String>>()
        var current = -1
        fun closeStep(step: Int) {
            if (step < 0) return
            for (label in labels) {
                val place = script?.steps?.getOrNull(stretches[step].index)?.devices?.get(label)?.place
                    ?: lastPlace[label]
                if (place != null) places[step][label] = place
            }
        }
        for (event in events) {
            val step = stepOf(event.t)
            if (step != current) {
                closeStep(current)
                current = step
                if (step >= 0) for ((dev, techs) in active) advertised[step].getOrPut(dev) { HashSet() } += techs
            }
            if (event.k == "mark") event.string("place")?.let { lastPlace[event.dev] = it }
            if (step < 0) continue
            event.app?.let { app ->
                val counts = appEvents[step].getOrPut(event.dev) { IntArray(2) }
                counts[0]++
                if (app in TechniqueCards.ON_SCREEN) counts[1]++
            }
            if (event.k != "adv") continue
            val tech = advTech(event) ?: continue
            when (event.string("action")) {
                "start" -> {
                    active.getOrPut(event.dev) { HashSet() } += tech
                    advertised[step].getOrPut(event.dev) { HashSet() } += tech
                    if (tech == TechniqueCards.IBEACON && beaconStarted.add(step to event.dev)) {
                        regionWaits +=
                            regionWaitsOf(event, step, stretches[step], monitors, regionEvents, radar.distances)
                    }
                }

                "stop", "failed" -> active[event.dev]?.remove(tech)
            }
        }
        closeStep(current)

        val masks = List(stretches.size) { HashMap<String, Int>() }
        var tokenMasks = 0
        var tokenMasksDecoded = 0
        for (row in merge.maskRows()) {
            val step = stepOf(row.event.t)
            if (step >= 0) masks[step][row.event.dev] = (masks[step][row.event.dev] ?: 0) + 1
            val sent = row.expected?.let(OverflowCode::decode).orEmpty()
            if (row.match != true || sent.isEmpty()) continue
            tokenMasks++
            if (row.decoded.any { it in sent }) tokenMasksDecoded++
        }
        val advertisedTechs = advertised.flatMap { step -> step.values.flatten() }
        return CardFacts(
            distances = distances,
            onScreen = appEvents.map { byDev ->
                byDev.filterValues { (all, onScreen) -> all > 0 && onScreen >= ON_SCREEN_SHARE * all }.keys
            },
            advertised = advertised.map { byDev -> byDev.mapValues { it.value.toSet() } },
            places = places,
            masks = masks,
            regionWaits = regionWaits,
            lockedRanging = lockedRanging(merge, stretches, sender),
            tokenMasks = tokenMasks,
            tokenMasksDecoded = tokenMasksDecoded,
            // Logs before the channels name no channel of a reading: their advertisements alone would judge nothing.
            techs = if (radar.readingTechs) radar.techs + advertisedTechs else radar.techs,
        )
    }

    /**
     * The cases of `ble.ibeacon.region` when [start] (a seeker's iBeacon) began in [step]: every other device that
     * monitored the region and was not inside it already, with the wait for its first enter after [start] (within the
     * step, or [TechniqueCards.REGION_MAX_WAIT_MILLIS] twice over when the step is shorter).
     */
    private fun regionWaitsOf(
        start: LabEvent,
        step: Int,
        stretch: Stretch,
        monitors: Set<String>,
        regionEvents: List<LabEvent>,
        distances: LabDistances,
    ): List<RegionWait> = (monitors - start.dev).sorted().mapNotNull { listener ->
        val meters = distances.at(RunStep.pairKey(start.dev, listener), start.t) ?: return@mapNotNull null
        val own = regionEvents.filter { it.dev == listener }
        if (own.lastOrNull { it.t < start.t }?.string("action") == "region_enter") return@mapNotNull null
        val until = maxOf(stretch.end, start.t + 2 * TechniqueCards.REGION_MAX_WAIT_MILLIS)
        val enter = own.firstOrNull { it.t >= start.t && it.t < until && it.string("action") == "region_enter" }
        RegionWait(step, start.dev, listener, meters, start.t, enter?.let { it.t - start.t })
    }

    /**
     * The cases of `ble.ibeacon` on a locked iPhone ([LockedRanging]): every lock of an iPhone (`life` event
     * `protected_data_off`; `did_enter_background` in a log without protected-data events) while another device's
     * iBeacon was on, with its last CoreLocation ranging reading of that device until the iBeacon stopped or the
     * stretch ended.
     */
    private fun lockedRanging(
        merge: LabMerge,
        stretches: List<Stretch>,
        sender: (String?) -> String,
    ): List<LockedRanging> {
        val iPhones = merge.devices.filter { it.os?.startsWith("iOS") == true }.map { it.dev }.toSet()
        if (iPhones.isEmpty() || stretches.isEmpty()) return emptyList()
        val events = merge.events
        val beacons = events.filter { it.k == "adv" && advTech(it) == TechniqueCards.IBEACON }.groupBy { it.dev }
        if (beacons.isEmpty()) return emptyList()
        val result = ArrayList<LockedRanging>()
        for (listener in iPhones.sorted()) {
            val life = events.filter { it.dev == listener && it.k == "life" }
            val protectedData = life.any { it.string("event")?.startsWith(PROTECTED_DATA) == true }
            val lockEvent = if (protectedData) LOCK_EVENT else LOCK_FALLBACK_EVENT
            val locks = life.filter { it.string("event") == lockEvent }.map { it.t }
            if (locks.isEmpty()) continue
            val ranging = events.filter {
                it.k == "rx" && it.dev == listener && it.string("api") == RANGING_API && it.int("rssi") != null
            }
            for (lock in locks) {
                val stretchEnd = stretches.lastOrNull { it.start <= lock }?.end ?: continue
                for ((seeker, advs) in beacons) {
                    if (seeker == listener) continue
                    if (advs.lastOrNull { it.t <= lock }?.string("action") != "start") continue
                    val stop = advs.firstOrNull { it.t > lock && it.string("action") in STOPS }?.t ?: Long.MAX_VALUE
                    val end = minOf(stop, stretchEnd)
                    val last = ranging.lastOrNull { it.t in lock..end && sender(it.string("token")) == seeker }
                    result += LockedRanging(listener, seeker, lock, last?.t, end - lock)
                }
            }
        }
        return result
    }

    /** An `adv` event's channel: its `tech`, else by the older `mode`. */
    private fun advTech(event: LabEvent): String? = event.string("tech") ?: when (val mode = event.string("mode")) {
        null -> null
        "hider_name" -> TechniqueCards.NAME
        "hider_service_data" -> TechniqueCards.SERVICE_DATA
        "ibeacon" -> TechniqueCards.IBEACON
        "overflow_probe" -> TechniqueCards.OVERFLOW
        else -> mode
    }

    private fun round2(value: Double): Double = (value * 100).roundToLong() / 100.0

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
        tech = tech,
    )

    private const val BEFORE = "before"

    /** The `scan` api of iOS region monitoring, and its region events. */
    private const val REGION_API = "corelocation_region"
    private val REGION_ACTIONS = setOf("region_enter", "region_exit")

    /** A locked iPhone: its `life` event, or where the log has no protected-data events, the fallback. */
    private const val PROTECTED_DATA = "protected_data"
    private const val LOCK_EVENT = "protected_data_off"
    private const val LOCK_FALLBACK_EVENT = "did_enter_background"

    /** CoreLocation's ranging, the `rx` api of an iPhone hearing an iBeacon. */
    private const val RANGING_API = "corelocation_ranging"
    private val STOPS = setOf("stop", "failed")

    /** The witness needs a third phone. */
    private const val WITNESS_DEVICES = 3

    /** A listener is «on the screen» in a step when this share of its events says so. */
    private const val ON_SCREEN_SHARE = 0.9

    private const val SAME_STEP_MILLIS = 2_000L
}
