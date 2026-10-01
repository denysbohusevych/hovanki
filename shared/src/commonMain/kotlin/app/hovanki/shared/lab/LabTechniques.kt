package app.hovanki.shared.lab

import app.hovanki.shared.protocol.RadarBand
import kotlin.math.ceil
import kotlin.math.roundToLong

/** A stretch of the run on the timeline: [index] of the script's steps (-1: before the first), `[start, end)`. */
data class LabSpan(val index: Int, val start: Long, val end: Long)

/**
 * The techniques that compete, recomputed from a run's journals (docs/adr/0017-radar-techniques-and-big-run.md §2.3,
 * §3, §7; docs/radar-run.md step 4): the touches (the button's truth, the [TouchDetector]), the calibrations
 * ([CalibrationVariant]) and smoothings ([SmoothingVariant]) by the band's error against the steps' distances
 * ([RunStep.distances], [BandTruth]), the band with every channel against the band without one, the witness
 * ([WitnessInference]), the pocket's classifiers in the shadow against the marks, and a card per technique against
 * its criterion. [events]: the merged run; [spans]: its steps; [sender]: whose a token is; [models]: each label's phone
 * model; [modelOffsets]: `radio_calibration`'s. Pure. The field log's `rx` is a second's summary (`n`, median, max):
 * the smoothings read it as one reading a second, a rough picture only.
 */
class LabTechniques(
    private val events: List<LabEvent>,
    private val spans: List<LabSpan>,
    private val script: LabRunScript?,
    sender: (String?) -> String,
    private val models: Map<String, String?> = emptyMap(),
    private val modelOffsets: ModelOffsets = ModelOffsets.NONE,
) {
    /** Every reading whose sender is one device of the run: `?token` and `A+B` say nothing of a pair. */
    private val readings: List<PairReading> = events.mapNotNull { event ->
        if (event.k != FieldKinds.RX) return@mapNotNull null
        val rssi = event.int(RxFields.RSSI) ?: return@mapNotNull null
        val from = sender(event.string(RxFields.TOKEN))
        if (from.startsWith("?") || '+' in from || from == event.dev) return@mapNotNull null
        val tech = event.string(RxFields.TECH) ?: "${event.string(RxFields.API)}/${event.string(RxFields.VIA)}"
        PairReading(from, event.dev, event.t, rssi, tech)
    }

    /** The loudest of a second where the field log has it: what the touch's peak looks for. */
    private val peaks: List<PairReading> = events.mapNotNull { event ->
        if (event.k != FieldKinds.RX) return@mapNotNull null
        val rssi = event.int(RxFields.MAX) ?: event.int(RxFields.RSSI) ?: return@mapNotNull null
        val from = sender(event.string(RxFields.TOKEN))
        if (from.startsWith("?") || '+' in from || from == event.dev) return@mapNotNull null
        PairReading(from, event.dev, event.t, rssi)
    }

    /** (from, to) → its readings in time order. */
    private val directions: Map<Pair<String, String>, List<PairReading>> =
        readings.groupBy { it.from to it.to }.mapValues { (_, list) -> list.sortedBy { it.t } }

    private val labels: List<String> = directions.keys.flatMap { listOf(it.first, it.second) }.distinct().sorted()

    // The touches

    /** The touches and the pairs' touch calibration. */
    class Touches(
        val rows: List<LabReportTouch>,
        val pairs: List<LabReportTouchPair>,
        val detector: LabReportTouchDetector?,
    ) {
        /** (from, to) → the dB to add from the pair's touches. */
        val offsets: Map<Pair<String, String>, Double> = buildMap {
            for (pair in pairs) {
                pair.offsetAToB?.let { put(pair.a to pair.b, it) }
                pair.offsetBToA?.let { put(pair.b to pair.a, it) }
            }
        }
    }

    val touches: Touches by lazy { findTouches() }

    private fun findTouches(): Touches {
        val touchEvents = events.filter { it.k == TouchKinds.TOUCH }
        val impacts = touchEvents.filter { it.string(TouchFields.SRC) == TouchFields.IMPACT }
            .mapNotNull { event -> event.double(TouchFields.G)?.let { TouchImpact(event.dev, event.t, it) } }
        val detected = TouchDetector.detect(impacts, peaks)
        val truths = buttonTouches(touchEvents)
        val index = TouchDetector.PairReadings(peaks)
        val matched = HashSet<Int>()
        val rows = ArrayList<LabReportTouch>()
        for (truth in truths) {
            val hit = detected.withIndex().firstOrNull { (i, found) ->
                i !in matched && found.pair == truth.first &&
                    found.t in truth.second - BUTTON_AFTER_MILLIS..truth.second + TouchDetector.PEAK_WINDOW_MILLIS
            }
            if (hit != null) {
                matched += hit.index
                rows += hit.value.toRow(LabReportTouch.BOTH)
            } else {
                // Pressed after the touch: the peak is in the seconds before the press.
                val near = index.between(truth.first, truth.second - BUTTON_AFTER_MILLIS, truth.second + 1_000)
                rows += LabReportTouch(
                    a = truth.first.a,
                    b = truth.first.b,
                    atMillis = truth.second,
                    source = LabReportTouch.BUTTON,
                    rssiAToB = near.filter { it.from == truth.first.a }.maxOfOrNull { it.rssi },
                    rssiBToA = near.filter { it.from == truth.first.b }.maxOfOrNull { it.rssi },
                )
            }
        }
        for ((i, found) in detected.withIndex()) if (i !in matched) rows += found.toRow(LabReportTouch.DETECTOR)
        rows.sortBy { it.atMillis }
        val pairs = rows.groupBy { LabPair.of(it.a, it.b) }.map { (pair, touches) ->
            val aToB = touches.mapNotNull { it.rssiAToB }
            val bToA = touches.mapNotNull { it.rssiBToA }
            val drifts = listOfNotNull(Calibration.drift(aToB), Calibration.drift(bToA))
            LabReportTouchPair(
                a = pair.a,
                b = pair.b,
                touches = touches.size,
                offsetAToB = Calibration.touchOffset(aToB)?.let(::round1),
                offsetBToA = Calibration.touchOffset(bToA)?.let(::round1),
                spreadDb = listOfNotNull(Calibration.spread(aToB), Calibration.spread(bToA)).maxOrNull(),
                driftDb = drifts.takeIf { it.isNotEmpty() }?.let { round1(it.average()) },
            )
        }
        val detector = if (truths.isEmpty() && detected.isEmpty()) {
            null
        } else {
            LabReportTouchDetector(truths.size, matched.size, detected.size - matched.size)
        }
        return Touches(rows, pairs, detector)
    }

    /**
     * The button's touches: a press of X with the partner Y and one of Y with X within [BUTTON_PAIR_MILLIS] are one
     * touch (at the first press); a press the partner never matched is one too.
     */
    private fun buttonTouches(touchEvents: List<LabEvent>): List<Pair<LabPair, Long>> {
        val presses = touchEvents.filter { it.string(TouchFields.SRC) == TouchFields.BUTTON }
            .mapNotNull { event -> event.string(TouchFields.PARTNER)?.takeIf { it != event.dev }?.let { event to it } }
        val used = HashSet<Int>()
        val result = ArrayList<Pair<LabPair, Long>>()
        for ((i, press) in presses.withIndex()) {
            if (i in used) continue
            used += i
            val (event, partner) = press
            val answer = presses.withIndex().firstOrNull { (j, other) ->
                j !in used && other.first.dev == partner && other.second == event.dev &&
                    other.first.t - event.t in -BUTTON_PAIR_MILLIS..BUTTON_PAIR_MILLIS
            }
            if (answer != null) used += answer.index
            result += LabPair.of(event.dev, partner) to minOf(event.t, answer?.value?.first?.t ?: event.t)
        }
        return result.sortedBy { it.second }
    }

    private fun DetectedTouch.toRow(source: String) = LabReportTouch(
        a = pair.a,
        b = pair.b,
        atMillis = t,
        source = source,
        rssiAToB = rssiAToB,
        rssiBToA = rssiBToA,
        impactA = impactA?.let(::round2),
        impactB = impactB?.let(::round2),
        skewMillis = skewMillis,
    )

    // The band against the distances

    /** The band the step's distance says for [pair] at [t]; null: the step has no distance for it. */
    private fun truthAt(pair: LabPair, t: Long): RadarBand? {
        val span = spans.firstOrNull { t >= it.start && t < it.end } ?: return null
        if (span.index < 0) return null
        val meters = script?.steps?.getOrNull(span.index)?.distances?.get(pair.key) ?: return null
        return BandTruth.of(meters)
    }

    /** The seconds of [pair] with a truth: every second of its steps with a distance, after the first few. */
    private fun truthSeconds(pair: LabPair): List<Long> = spans.filter { span ->
        span.index >= 0 && script?.steps?.getOrNull(span.index)?.distances?.containsKey(pair.key) == true
    }.flatMap { span ->
        val first = span.start + SETTLE_MILLIS
        if (first >= span.end) emptyList() else (first until span.end step 1_000L).toList()
    }

    /** The error of the game's smoothing with each calibration. */
    fun calibration(): List<LabReportBandError> = CalibrationVariant.entries.map { variant ->
        var uncalibrated = 0
        bandError(variant.id, SmoothingVariant.EMA) { from, to ->
            val offset = when (variant) {
                CalibrationVariant.NONE -> 0.0
                CalibrationVariant.MODEL -> modelOffsets.of(models[to], models[from])
                CalibrationVariant.TOUCH -> touches.offsets[from to to]
            }
            offset ?: 0.0.also { uncalibrated++ }
        }.copy(uncalibrated = uncalibrated)
    }

    /** The error of each smoothing, without calibration. */
    fun smoothing(): List<LabReportBandError> =
        SmoothingVariant.entries.map { variant -> bandError(variant.id, variant) { _, _ -> 0.0 } }

    private fun bandError(
        tech: String,
        smoothing: SmoothingVariant,
        offset: (String, String) -> Double,
    ): LabReportBandError {
        var seconds = 0
        var wrong = 0
        var compared = 0
        for ((direction, list) in directions) {
            val pair = LabPair.of(direction.first, direction.second)
            val times = truthSeconds(pair)
            if (times.isEmpty()) continue
            compared++
            val shift = offset(direction.first, direction.second)
            val bands = Smoothing.bands(smoothing, list.map { TimedRssi(it.t, it.rssi + shift) }, times)
            for ((index, time) in times.withIndex()) {
                val truth = truthAt(pair, time) ?: continue
                seconds++
                if (bands[index] != truth) wrong++
            }
        }
        return LabReportBandError(tech, seconds, wrong, percent(wrong, seconds), compared)
    }

    // Without one channel

    /** For every channel: the band with every channel against the band without it, where either heard something. */
    fun without(): List<LabReportWithout> {
        class Count(var directions: Int = 0, var seconds: Int = 0, var equal: Int = 0, var alone: Int = 0)
        val counts = HashMap<String, Count>()
        for ((_, list) in directions) {
            val techs = list.mapNotNull { it.tech }.distinct()
            val times = secondsOf(list)
            if (times.isEmpty()) continue
            val all = Smoothing.bands(SmoothingVariant.EMA, list.map(::timed), times)
            for (tech in techs) {
                val count = counts.getOrPut(tech) { Count() }
                count.directions++
                val rest = list.filter { it.tech != tech }
                val without = Smoothing.bands(SmoothingVariant.EMA, rest.map(::timed), times)
                var next = 0
                val inSecond = ArrayList<PairReading>()
                for ((index, time) in times.withIndex()) {
                    inSecond.clear()
                    while (next < list.size && list[next].t <= time) inSecond += list[next++]
                    if (inSecond.isNotEmpty() && inSecond.all { it.tech == tech }) count.alone++
                    if (all[index] == RadarBand.NONE && without[index] == RadarBand.NONE) continue
                    count.seconds++
                    if (all[index] == without[index]) count.equal++
                }
            }
        }
        return counts.entries.sortedBy { it.key }.map { (tech, count) ->
            LabReportWithout(tech, count.directions, count.seconds, percent(count.equal, count.seconds), count.alone)
        }
    }

    /** Every second from a direction's first reading to its last one's life, at most [MAX_SECONDS]. */
    private fun secondsOf(list: List<PairReading>): List<Long> {
        if (list.isEmpty()) return emptyList()
        val first = ceil(list.first().t / 1000.0).toLong() * 1000
        val last = minOf(list.last().t + LAST_HELD_MILLIS, first + MAX_SECONDS * 1000L)
        return if (first > last) emptyList() else (first..last step 1_000L).toList()
    }

    // The witness

    /** The witness's guesses and how many the steps' distances confirm; null: fewer than three phones heard. */
    fun witness(): LabReportWitness? {
        if (labels.size < 3 || labels.size > MAX_WITNESS_LABELS) return null
        val all = readings.sortedBy { it.t }
        val times = secondsOf(all)
        if (times.isEmpty()) return null
        val bands = directions.mapValues { (_, list) -> Smoothing.bands(SmoothingVariant.EMA, list.map(::timed), times) }
        var inferred = 0
        var withTruth = 0
        var right = 0
        for ((index, time) in times.withIndex()) {
            val pairBands = HashMap<LabPair, RadarBand>()
            for ((direction, list) in bands) {
                val pair = LabPair.of(direction.first, direction.second)
                pairBands[pair] = maxOf(pairBands[pair] ?: RadarBand.NONE, list[index])
            }
            for ((pair, band) in WitnessInference.infer(labels, pairBands)) {
                inferred++
                val truth = truthAt(pair, time) ?: continue
                withTruth++
                if (truth == band) right++
            }
        }
        return LabReportWitness(inferred, withTruth, right)
    }

    // The pocket

    /**
     * Every classifier of the pocket that wrote (the game's `carry` events and the shadow's `carry.v1`, `carry.v2`)
     * against the marks' truth, second by second.
     */
    fun carryClassifiers(): List<LabReportClassifier> {
        val result = ArrayList<LabReportClassifier>()
        for ((dev, own) in events.groupBy { it.dev }) {
            val stated = own.any {
                it.k == "carry" || (it.k == LabRadarKinds.SHADOW && it.string(ShadowFields.STATE) != null)
            }
            if (!stated || own.none { it.k == "mark" && it.string("place") != null }) continue
            val seconds = HashMap<String, IntArray>()
            val states = HashMap<String, String>()
            var truth = LabMerge.NO_TRUTH
            var index = 0
            var second = ceil(own.first().t / 1000.0).toLong() * 1000
            val end = minOf(own.last().t, second + LabMerge.MAX_CARRY_SPAN_MILLIS)
            while (second <= end) {
                while (index < own.size && own[index].t <= second) {
                    val event = own[index++]
                    when {
                        event.k == "mark" -> event.string("place")?.let { truth = LabMerge.truthOf(it) }
                        event.k == "carry" -> event.string("state")?.let { states[CarryTechs.V1] = it }
                        event.k == LabRadarKinds.SHADOW -> {
                            val tech = event.string(ShadowFields.TECH)
                            val state = event.string(ShadowFields.STATE)
                            if (tech in CarryTechs.ALL && state != null) states[tech!!] = state
                        }
                    }
                }
                if (truth != LabMerge.NO_TRUTH) {
                    for ((tech, state) in states) {
                        val count = seconds.getOrPut(tech) { IntArray(2) }
                        count[0]++
                        if (agrees(state, truth)) count[1]++
                    }
                }
                second += 1000
            }
            for ((tech, count) in seconds.entries.sortedBy { it.key }) {
                result += LabReportClassifier(dev, tech, count[0], count[1], percent(count[1], count[0]))
            }
        }
        return result
    }

    private fun agrees(state: String, truth: String): Boolean = when (truth) {
        "in_hand" -> state == "in_hand"
        "in_pocket" -> state == "in_pocket"
        else -> state != "in_pocket" && state != "in_hand"
    }

    // The cards

    /** A card per technique against its criterion (ADR 0017 §2.3, §3). */
    fun cards(
        calibration: List<LabReportBandError> = calibration(),
        smoothing: List<LabReportBandError> = smoothing(),
        witness: LabReportWitness? = witness(),
        carry: List<LabReportClassifier> = carryClassifiers(),
    ): List<LabReportCard> = buildList {
        addAll(channelCards())
        addAll(smoothingCards(smoothing))
        addAll(calibrationCards(calibration))
        add(detectorCard())
        addAll(carryCards(carry))
        add(witnessCard(witness))
    }

    private fun channelCards(): List<LabReportCard> {
        val advertised = HashMap<String, MutableSet<String>>()
        for (event in events) {
            if (event.k != LabRadarKinds.ADV || event.string("action") != "start") continue
            for (tech in event.string("tech")?.split(',').orEmpty()) {
                if (tech.startsWith(SERVICE_DATA)) advertised.getOrPut(tech) { mutableSetOf() } += event.dev
            }
        }
        return advertised.entries.sortedBy { it.key }.map { (tech, senders) ->
            val rates = ArrayList<Pair<String, Double>>()
            for (sender in senders) {
                for (listener in labels - sender) {
                    val pair = LabPair.of(sender, listener)
                    val near = spans.filter { span ->
                        val meters = script?.steps?.getOrNull(span.index)?.distances?.get(pair.key)
                        span.index >= 0 && meters != null && meters <= NEAR_METERS
                    }
                    val seconds = near.sumOf { it.end - it.start } / 1000.0
                    if (seconds < 1) continue
                    val heard = directions[sender to listener].orEmpty()
                        .count { reading -> reading.tech == tech && near.any { reading.t >= it.start && reading.t < it.end } }
                    rates += "$sender→$listener" to heard / seconds
                }
            }
            val verdict = when {
                rates.isEmpty() -> LabReportCard.TOO_LITTLE
                rates.all { it.second >= MIN_RATE } -> LabReportCard.KEEP
                else -> LabReportCard.DROP
            }
            LabReportCard(
                id = tech,
                group = "channel",
                verdict = verdict,
                criterion = "≥ $MIN_RATE readings/s within $NEAR_METERS m at every listener",
                numbers = rates.joinToString { (direction, rate) -> "$direction ${round2(rate)}/s" }
                    .ifEmpty { "no step with a distance of ${NEAR_METERS} m or less" },
            )
        }
    }

    private fun smoothingCards(errors: List<LabReportBandError>): List<LabReportCard> {
        val enough = errors.all { it.seconds >= MIN_SECONDS }
        val best = errors.mapNotNull { it.errorPercent }.minOrNull()
        return errors.map { error ->
            val verdict = when {
                !enough || best == null || error.errorPercent == null -> LabReportCard.TOO_LITTLE
                error.errorPercent <= best + TIE_POINTS -> LabReportCard.KEEP
                else -> LabReportCard.DROP
            }
            LabReportCard(error.tech, "smoothing", verdict, "the least band error against the steps' distances", error.describe())
        }
    }

    private fun calibrationCards(errors: List<LabReportBandError>): List<LabReportCard> {
        val none = errors.first { it.tech == CalibrationVariant.NONE.id }
        val spread = touches.pairs.mapNotNull { it.spreadDb }.maxOrNull()
        return errors.map { error ->
            val gain = if (none.errorPercent != null && error.errorPercent != null) {
                none.errorPercent - error.errorPercent
            } else {
                null
            }
            val calibrated = error.directions - error.uncalibrated
            val verdict = when {
                error.tech == CalibrationVariant.NONE.id -> if (none.seconds >= MIN_SECONDS) {
                    LabReportCard.KEEP
                } else {
                    LabReportCard.TOO_LITTLE
                }

                error.seconds < MIN_SECONDS || gain == null || calibrated <= 0 -> LabReportCard.TOO_LITTLE

                error.tech == CalibrationVariant.TOUCH.id && spread == null -> LabReportCard.TOO_LITTLE

                gain >= Calibration.MIN_GAIN_POINTS &&
                    (error.tech != CalibrationVariant.TOUCH.id || spread!! <= Calibration.MAX_SPREAD_DB) ->
                    LabReportCard.KEEP

                else -> LabReportCard.DROP
            }
            val criterion = when (error.tech) {
                CalibrationVariant.NONE.id -> "the baseline"
                CalibrationVariant.TOUCH.id ->
                    "band error ≥ ${Calibration.MIN_GAIN_POINTS.toInt()} points lower than calib.none, " +
                        "the first three touches within ${Calibration.MAX_SPREAD_DB.toInt()} dB"
                else -> "band error ≥ ${Calibration.MIN_GAIN_POINTS.toInt()} points lower than calib.none"
            }
            val numbers = listOfNotNull(
                error.describe(),
                gain?.let { "${round1(it)} points better than calib.none" },
                "spread ${spread?.let(::round1)} dB".takeIf { error.tech == CalibrationVariant.TOUCH.id },
                "${error.uncalibrated} of ${error.directions} directions without an offset".takeIf {
                    error.uncalibrated > 0
                },
            ).joinToString("; ")
            LabReportCard(error.tech, "calibration", verdict, criterion, numbers)
        }
    }

    private fun detectorCard(): LabReportCard {
        val detector = touches.detector
        val verdict = when {
            detector == null || detector.buttonTouches < MIN_TOUCHES -> LabReportCard.TOO_LITTLE
            detector.found >= DETECTOR_FOUND * detector.buttonTouches &&
                detector.falseAlarms <= DETECTOR_FALSE * detector.buttonTouches -> LabReportCard.KEEP
            else -> LabReportCard.DROP
        }
        return LabReportCard(
            id = "touch.detector",
            group = "calibration",
            verdict = verdict,
            criterion = "finds ≥ ${(DETECTOR_FOUND * 100).toInt()} % of the button's touches, " +
                "false alarms ≤ ${(DETECTOR_FALSE * 100).toInt()} % of them",
            numbers = detector?.let { "${it.found} of ${it.buttonTouches} found, ${it.falseAlarms} false" }
                ?: "no touches",
        )
    }

    private fun carryCards(rows: List<LabReportClassifier>): List<LabReportCard> {
        val byTech = CarryTechs.ALL.associateWith { tech ->
            val own = rows.filter { it.tech == tech }
            own.sumOf { it.seconds } to own.sumOf { it.agree }
        }
        val enough = byTech.values.all { it.first >= MIN_SECONDS }
        val best = byTech.values.mapNotNull { percent(it.second, it.first) }.maxOrNull()
        return byTech.map { (tech, count) ->
            val agree = percent(count.second, count.first)
            val verdict = when {
                !enough || agree == null || best == null -> LabReportCard.TOO_LITTLE
                agree >= best - TIE_POINTS -> LabReportCard.KEEP
                else -> LabReportCard.DROP
            }
            LabReportCard(
                id = tech,
                group = "carry",
                verdict = verdict,
                criterion = "the most seconds agreeing with the scenario's marks",
                numbers = "${count.second} of ${count.first} s agree" + (agree?.let { " ($it %)" } ?: ""),
            )
        }
    }

    private fun witnessCard(witness: LabReportWitness?): LabReportCard {
        val right = witness?.let { percent(it.right, it.withTruth) }
        val verdict = when {
            witness == null || witness.withTruth < MIN_WITNESS_SECONDS || right == null -> LabReportCard.TOO_LITTLE
            right >= WITNESS_RIGHT_PERCENT -> LabReportCard.KEEP
            else -> LabReportCard.DROP
        }
        return LabReportCard(
            id = "infer.witness",
            group = "inference",
            verdict = verdict,
            criterion = "≥ ${WITNESS_RIGHT_PERCENT.toInt()} % of its guesses right in ≥ $MIN_WITNESS_SECONDS pair-seconds " +
                "with a distance",
            numbers = witness?.let {
                "${it.inferredSeconds} pair-seconds guessed, ${it.right} of ${it.withTruth} with a distance right"
            } ?: "fewer than three phones heard each other",
        )
    }

    private fun LabReportBandError.describe(): String =
        "$wrong of $seconds s wrong" + (errorPercent?.let { " ($it %)" } ?: "") + " in $directions directions"

    companion object {
        /** The seconds at a step's start the smoothing needs to settle: not counted. */
        const val SETTLE_MILLIS = 3_000L

        /** A press of the button after the touch: the peak is up to this long before it. */
        const val BUTTON_AFTER_MILLIS = 5_000L

        /** Two presses of a pair this close are one touch. */
        const val BUTTON_PAIR_MILLIS = 10_000L

        /** A verdict needs this many seconds with a truth. */
        const val MIN_SECONDS = 60
        const val MIN_TOUCHES = 3
        const val MIN_WITNESS_SECONDS = 30
        const val TIE_POINTS = 1.0
        const val DETECTOR_FOUND = 0.8
        const val DETECTOR_FALSE = 0.1
        const val WITNESS_RIGHT_PERCENT = 80.0
        const val MIN_RATE = 2.0
        const val NEAR_METERS = 5.0

        /** The longest stretch of seconds one direction or the witness counts: six hours. */
        const val MAX_SECONDS = 6 * 3_600

        /** The witness's work grows with the cube of the phones: a lab run has a handful. */
        const val MAX_WITNESS_LABELS = 12

        private const val SERVICE_DATA = "ble.service_data"

        /** After a direction's last reading its band holds this long (the signal's life). */
        private const val LAST_HELD_MILLIS = 10_000L

        private fun percent(part: Int, whole: Int): Double? =
            if (whole <= 0) null else (part * 1000.0 / whole).roundToLong() / 10.0

        private fun timed(reading: PairReading) = TimedRssi(reading.t, reading.rssi.toDouble())

        private fun round1(value: Double): Double = (value * 10).roundToLong() / 10.0

        private fun round2(value: Double): Double = (value * 100).roundToLong() / 100.0
    }
}
