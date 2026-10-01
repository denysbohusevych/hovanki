package app.hovanki.shared.lab

import app.hovanki.shared.lab.LabMerge.Companion.round1
import app.hovanki.shared.rules.Smoothings

/** A technique card's verdict (ADR 0017 §7, section 2): «оставить», «выбросить», «мало данных». */
enum class Verdict { KEEP, DROP, INSUFFICIENT }

/**
 * A technique's card: [criterion] the catalog's words (docs/adr/0017-radar-techniques-and-big-run.md §2.3 and §3),
 * [numbers] what the run measured for it, [missing] what the run lacked for a verdict ([Verdict.INSUFFICIENT]).
 */
data class TechniqueCard(
    val tech: String,
    val verdict: Verdict,
    val criterion: String,
    val numbers: List<String> = emptyList(),
    val missing: String? = null,
)

/**
 * What the cards read besides the report's parts, put together by [LabReportBuilder] from the logs; every list goes by
 * the report's steps ([LabReport.steps], by position). [distances]: the pairs' distances in the step (pair → meters,
 * at its middle); [onScreen]: the labels whose app was on the screen (`active`, `screen_on`) through the step;
 * [advertised]: label → the channels it advertised in the step (the `adv` events; the overflow probe as
 * `ble.overflow`); [places]: label → where the phone was (the script's place, else its last mark's); [masks]: label →
 * the overflow masks it heard in the step. [regionWaits]: every case of `ble.ibeacon.region`; [tokenMasks] the masks
 * that matched a probe advertising a token, [tokenMasksDecoded] of them read as that token. [techs]: the channels the
 * logs name (heard or advertised). [lockedRanging]: every case of `ble.ibeacon` on a locked iPhone.
 */
data class CardFacts(
    val distances: List<Map<String, Double>> = emptyList(),
    val onScreen: List<Set<String>> = emptyList(),
    val advertised: List<Map<String, Set<String>>> = emptyList(),
    val places: List<Map<String, String>> = emptyList(),
    val masks: List<Map<String, Int>> = emptyList(),
    val regionWaits: List<RegionWait> = emptyList(),
    val lockedRanging: List<LockedRanging> = emptyList(),
    val tokenMasks: Int = 0,
    val tokenMasksDecoded: Int = 0,
    val techs: Set<String> = emptySet(),
)

/**
 * A case of `ble.ibeacon` on a locked iPhone (ADR 0017 §2.3, `ble.ibeacon.ranging`): [listener] (an iPhone) was locked
 * at [lockMillis] (its `life` event `protected_data_off`, or `did_enter_background` in a log without protected-data
 * events) while [seeker] advertised its iBeacon; [lastMillis] the last CoreLocation ranging reading of [seeker] it got
 * after the lock (null: none), within [windowMillis] after the lock (until the seeker stopped or the stretch ended).
 */
data class LockedRanging(
    val listener: String,
    val seeker: String,
    val lockMillis: Long,
    val lastMillis: Long?,
    val windowMillis: Long,
) {
    /** How long the readings went on after the lock. */
    val lastedMillis: Long get() = lastMillis?.let { it - lockMillis } ?: 0L
}

/**
 * A case of `ble.ibeacon.region`: [seeker] started its iBeacon at [startMillis] in the step [step] (by position) with
 * [listener], who monitors the region, [meters] away; the region's enter came [waitMillis] later (null: never, or not
 * within the step).
 */
data class RegionWait(
    val step: Int,
    val seeker: String,
    val listener: String,
    val meters: Double,
    val startMillis: Long,
    val waitMillis: Long?,
)

/**
 * The technique cards (ADR 0017 §7, section 2): each technique's criterion of the catalog (§2.3, §3) against what the
 * report measured. A card for every channel the logs name that has a criterion here, for the carry classifiers whose
 * answers are in the report, and always for the smoothings, the calibrations and `infer.witness`. Pure: its inputs are
 * the report's parts computed before ([LabReport.steps], [LabReport.bands], [LabReport.touchSpreads],
 * [LabReport.carry], [LabReport.witness], [LabReport.devices]) and the [CardFacts].
 *
 * The numbers are the plan's (the catalog's «Оставляем, если»), the thresholds below.
 */
object TechniqueCards {
    /** `ble.ibeacon`: a locked iPhone keeps getting the seeker's readings longer than this after the lock. */
    const val LOCKED_RANGING_MILLIS = 60_000L

    /** The service data's, the name's and the iBeacon's criterion: readings a second… */
    const val MIN_READINGS_PER_SECOND = 2.0

    /** …at this distance or closer, for every listener on the screen. */
    const val NEAR_METERS = 5.0

    /** `ble.ibeacon.region`: the enter within this after the seeker appeared… */
    const val REGION_MAX_WAIT_MILLIS = 30_000L

    /** …this close; and `ble.overflow`'s distance. */
    const val REGION_METERS = 10.0

    /** `ble.overflow`: at least one mask every this many seconds at an Android listener… */
    const val OVERFLOW_SECONDS_PER_FRAME = 3.0

    /** …and this share of the masks read right. */
    const val OVERFLOW_MIN_DECODED = 0.95

    /** A calibration keeps its place when it makes the exact bands this many percentage points more… */
    const val CALIBRATION_MIN_GAIN = 0.10

    /** …and the touches of a pair in one direction spread by at most this. */
    const val TOUCH_MAX_SPREAD_DB = 6

    /** The carry classifiers compete once each has this many seconds with a truth, as many of them in the pocket. */
    const val CARRY_MIN_SECONDS = 60

    /**
     * The smoothings and the calibrations are judged over at least this many distances: over one the band's error is
     * an offset, not the curve (the run of 2026-09-30: every second at 1 m, the three smoothings tied).
     */
    const val MIN_DISTANCES = 2

    /** `infer.witness`: at least this many inferred pair-seconds with a truth… */
    const val WITNESS_MIN_CASES = 10

    /** …of which this share right. */
    const val WITNESS_MIN_RIGHT = 0.8

    const val SERVICE_DATA = "ble.service_data"
    const val NAME = "ble.name"
    const val IBEACON = "ble.ibeacon"
    const val IBEACON_REGION = "ble.ibeacon.region"
    const val OVERFLOW = "ble.overflow"
    const val WITNESS = "infer.witness"

    /** The app states that are «on the screen». */
    val ON_SCREEN = setOf("active", "screen_on")

    private const val CRITERION_READINGS = "≥ 2 показаний/с в 5 м у каждого слушателя на экране"
    private const val CRITERION_SERVICE_DATA = "$CRITERION_READINGS; из раскладок — лучшая"
    private const val CRITERION_IBEACON =
        "заблокированный iPhone получает показания дольше 60 с после блокировки; $CRITERION_READINGS"
    private const val NO_LOCKED_IPHONE =
        "заблокированный iPhone: мало данных — ни один iPhone не блокировался дольше чем на 60 с, пока ищущий " +
            "вещал iBeacon"
    private const val CRITERION_NAME = "остаётся (слышен хоть кем-то)"
    private const val CRITERION_REGION = "вход ≤ 30 с после появления ищущего в 10 м"
    private const val CRITERION_OVERFLOW =
        "≥ 1 кадра за 3 с в 10 м у Android, телефон в кармане; ≥ 95 % разборов верны"
    private const val CRITERION_SMOOTHING =
        "ошибка полосы против отметок расстояния, пересчётом по журналам: из трёх остаётся лучшая"
    private const val CRITERION_NONE =
        "ошибка полосы против отметок расстояния: остаётся, если ни одна калибровка не лучше на 10 п. п."
    private const val CRITERION_MODEL =
        "ошибка полосы против отметок расстояния: точных секунд хотя бы на 10 п. п. больше, чем без калибровки"
    private const val CRITERION_TOUCH =
        "с касанием ошибка полосы меньше хотя бы на 10 процентных пунктов, а три касания расходятся не больше " +
            "чем на 6 dB"
    private const val CRITERION_CARRY = "совпадение с разметкой сценария по секундам: из двух — лучшее, ≥ 60 с у обоих"
    private const val CRITERION_WITNESS = "выведенная пара рядом по расстоянию в ≥ 80 % случаев, случаев ≥ 10"

    /** The distances of the pairs the run knew, one each. */
    private fun CardFacts.distinctDistances(): List<Double> = distances.flatMap { it.values }.distinct().sorted()

    /** Why the bands can't judge a smoothing or a calibration though they have seconds; null: they can. */
    private fun CardFacts.fewDistances(): String? = distinctDistances().takeIf { it.size < MIN_DISTANCES }?.let {
        val where = it.singleOrNull()?.let { m -> " (${round1(m)} м)" }.orEmpty()
        "все секунды на одном расстоянии$where: нужно хотя бы $MIN_DISTANCES"
    }

    fun build(report: LabReport, facts: CardFacts): List<TechniqueCard> = buildList {
        val channels = facts.techs + report.steps.flatMap { step -> step.directions.mapNotNull { it.tech } }
        val layouts = channels.filter { it == SERVICE_DATA || it.startsWith("$SERVICE_DATA.") }.sorted()
        addAll(serviceData(report, facts, layouts))
        if (NAME in channels) add(name(report))
        if (IBEACON in channels) add(iBeacon(report, facts))
        if (IBEACON_REGION in channels || facts.regionWaits.isNotEmpty()) add(region(report, facts))
        if (OVERFLOW in channels || report.masks.isNotEmpty()) add(overflow(report, facts))
        addAll(smoothings(report, facts))
        addAll(calibrations(report, facts))
        addAll(carry(report))
        add(witness(report.witness))
    }

    /** One channel's readings against [MIN_READINGS_PER_SECOND]: the slowest case, the cases. */
    private class Readings(val card: TechniqueCard, val slowest: Double?)

    private fun readings(report: LabReport, facts: CardFacts, tech: String, criterion: String): Readings {
        val cases = ArrayList<Pair<String, Double>>()
        for ((index, step) in report.steps.withIndex()) {
            val seconds = (step.endMillis - step.startMillis) / 1000.0
            if (seconds <= 0) continue
            val distances = facts.distances.getOrNull(index).orEmpty()
            val senders = facts.advertised.getOrNull(index).orEmpty().filterValues { tech in it }.keys
            val listeners = facts.onScreen.getOrNull(index).orEmpty()
                .filter { label -> step.directions.any { it.to == label } }
            for (from in senders) {
                for (to in listeners) {
                    if (to == from) continue
                    val meters = distances[RunStep.pairKey(from, to)] ?: continue
                    if (meters > NEAR_METERS) continue
                    val heard = step.directions.filter { it.from == from && it.to == to && it.tech == tech }
                        .sumOf { it.readings }
                    cases += "${step.id}: $from → $to, ${round1(meters)} м" to heard / seconds
                }
            }
        }
        if (cases.isEmpty()) {
            val card = TechniqueCard(
                tech,
                Verdict.INSUFFICIENT,
                criterion,
                missing = "нет шага, где канал вещали в 5 м от слушателя на экране (с расстояниями из сценария)",
            )
            return Readings(card, null)
        }
        val numbers = cases.map { (what, rate) -> "$what: ${round1(rate)} показаний/с" }
        val slowest = cases.minOf { it.second }
        val verdict = if (slowest >= MIN_READINGS_PER_SECOND) Verdict.KEEP else Verdict.DROP
        return Readings(TechniqueCard(tech, verdict, criterion, numbers), slowest)
    }

    /** The service data's layouts: each by the readings, then among those that pass the best (its slowest case). */
    private fun serviceData(report: LabReport, facts: CardFacts, layouts: List<String>): List<TechniqueCard> {
        val each = layouts.map { readings(report, facts, it, CRITERION_SERVICE_DATA) }
        val best = each.filter { it.card.verdict == Verdict.KEEP }.maxByOrNull { it.slowest!! } ?: return each.map {
            it.card
        }
        return each.map {
            if (it.card.verdict != Verdict.KEEP || it === best) {
                it.card
            } else {
                it.card.copy(
                    verdict = Verdict.DROP,
                    numbers = it.card.numbers + "лучшая раскладка — ${best.card.tech}: " +
                        "${round1(best.slowest!!)} против ${round1(it.slowest!!)} показаний/с в худшем случае",
                )
            }
        }
    }

    /**
     * `ble.ibeacon`: the catalog's criterion (a locked iPhone ranging the seeker longer than [LOCKED_RANGING_MILLIS]
     * after the lock) and the channels' on-screen readings, both: DROP when either fails. A lock whose window (until
     * the seeker stopped or the stretch ended) was no longer than the criterion can't say, and is only listed.
     */
    private fun iBeacon(report: LabReport, facts: CardFacts): TechniqueCard {
        val screen = readings(report, facts, IBEACON, CRITERION_IBEACON).card
        val heard = report.beaconsHeard()
        val (cases, unheard) = facts.lockedRanging.partition { it.seeker in heard }
        val (judged, short) = cases.partition { it.windowMillis > LOCKED_RANGING_MILLIS }
        val locked = when {
            judged.isEmpty() -> Verdict.INSUFFICIENT
            judged.all { it.lastedMillis > LOCKED_RANGING_MILLIS } -> Verdict.KEEP
            else -> Verdict.DROP
        }
        val lockedLines = judged.map {
            "заблокированный ${it.listener} слышал ${it.seeker} ${round1(it.lastedMillis / 1000.0)} с после блокировки"
        } + short.map {
            "заблокированный ${it.listener}, ${it.seeker}: окно ${round1(it.windowMillis / 1000.0)} с — не судим"
        } + unheard.map {
            "заблокированный ${it.listener}, ${it.seeker}: ${unheardLine(it.seeker)}"
        } + listOfNotNull(NO_LOCKED_IPHONE.takeIf { judged.isEmpty() && unheard.isEmpty() })
        val verdicts = listOf(locked, screen.verdict)
        val verdict = when {
            Verdict.DROP in verdicts -> Verdict.DROP
            Verdict.KEEP in verdicts -> Verdict.KEEP
            else -> Verdict.INSUFFICIENT
        }
        return screen.copy(
            verdict = verdict,
            numbers = lockedLines + screen.numbers,
            missing = screen.missing.takeIf { verdict == Verdict.INSUFFICIENT },
        )
    }

    private fun name(report: LabReport): TechniqueCard {
        val heard = report.steps.flatMap { it.directions }.filter { it.tech == NAME }
        val readings = heard.sumOf { it.readings }
        return if (readings > 0) {
            val pairs = heard.map { "${it.from} → ${it.to}" }.distinct().sorted()
            TechniqueCard(NAME, Verdict.KEEP, CRITERION_NAME, listOf("$readings показаний: ${pairs.joinToString()}"))
        } else {
            TechniqueCard(NAME, Verdict.DROP, CRITERION_NAME, listOf("никто не услышал имя"))
        }
    }

    /**
     * The senders whose iBeacon somebody heard in the run, by any API. A seeker nobody heard may not have been on the
     * air at all (the Mac's iBeacon on macOS 26, 2026-09-30): a silence of its listeners judges nothing.
     */
    private fun LabReport.beaconsHeard(): Set<String> = steps.flatMap { it.directions }
        .filter { it.tech == IBEACON || it.channel.endsWith("/ibeacon") }
        .mapTo(HashSet()) { it.from }

    private fun unheardLine(seeker: String) = "iBeacon $seeker не услышал никто за весь прогон — вещал ли он, не судим"

    private fun region(report: LabReport, facts: CardFacts): TechniqueCard {
        val heard = report.beaconsHeard()
        val (cases, unheard) = facts.regionWaits.filter { it.meters <= REGION_METERS }.partition { it.seeker in heard }
        if (cases.isEmpty()) {
            return TechniqueCard(
                IBEACON_REGION,
                Verdict.INSUFFICIENT,
                CRITERION_REGION,
                unheard.map { it.seeker }.distinct().map(::unheardLine),
                missing = "ищущий не начинал iBeacon в 10 м от телефона, следящего за регионом",
            )
        }
        val numbers = cases.map { case ->
            val wait = case.waitMillis?.let { "вход через ${round1(it / 1000.0)} с" } ?: "входа нет"
            "${case.seeker} → ${case.listener}, ${round1(case.meters)} м: $wait"
        }
        val passed = cases.all { it.waitMillis != null && it.waitMillis <= REGION_MAX_WAIT_MILLIS }
        return TechniqueCard(IBEACON_REGION, if (passed) Verdict.KEEP else Verdict.DROP, CRITERION_REGION, numbers)
    }

    private fun overflow(report: LabReport, facts: CardFacts): TechniqueCard {
        val androids = report.devices.filter { it.os?.startsWith("Android") == true }.map { it.label }.toSet()
        val cases = ArrayList<Pair<String, Double>>()
        for ((index, step) in report.steps.withIndex()) {
            val seconds = (step.endMillis - step.startMillis) / 1000.0
            if (seconds <= 0) continue
            val places = facts.places.getOrNull(index).orEmpty()
            val senders = facts.advertised.getOrNull(index).orEmpty()
                .filter { (label, techs) -> OVERFLOW in techs && places[label] in LabPlaces.CARRIED_HIDDEN }.keys
            val distances = facts.distances.getOrNull(index).orEmpty()
            for (from in senders) {
                for (to in androids - from) {
                    val meters = distances[RunStep.pairKey(from, to)] ?: continue
                    if (meters > REGION_METERS) continue
                    val frames = facts.masks.getOrNull(index)?.get(to) ?: 0
                    cases += "${step.id}: $from (${places[from]}) → $to, ${round1(meters)} м" to seconds / frames
                }
            }
        }
        val decoded = facts.tokenMasksDecoded.toDouble() / facts.tokenMasks.coerceAtLeast(1)
        val decodedLine = "разобрано верно ${facts.tokenMasksDecoded} из ${facts.tokenMasks} масок с жетоном " +
            "(${percent(decoded)})"
        val missing = listOfNotNull(
            "нет шага, где телефон в кармане вещал маску в 10 м от Android".takeIf { cases.isEmpty() },
            "ни одной маски с жетоном, совпавшей с пробой".takeIf { facts.tokenMasks == 0 },
        )
        val numbers = cases.map { (what, perFrame) ->
            "$what: " + if (perFrame.isInfinite()) "ни одного кадра" else "кадр за ${round1(perFrame)} с"
        } + decodedLine
        if (missing.isNotEmpty()) {
            return TechniqueCard(
                OVERFLOW,
                Verdict.INSUFFICIENT,
                CRITERION_OVERFLOW,
                numbers,
                missing.joinToString("; "),
            )
        }
        val passed = cases.all { it.second <= OVERFLOW_SECONDS_PER_FRAME } && decoded >= OVERFLOW_MIN_DECODED
        return TechniqueCard(OVERFLOW, if (passed) Verdict.KEEP else Verdict.DROP, CRITERION_OVERFLOW, numbers)
    }

    /** The share of exact seconds of [smoothing] under [calibration]; null: no seconds with a truth. */
    private fun LabReport.exactShare(smoothing: String, calibration: String): Double? =
        bands.firstOrNull { it.smoothing == smoothing && it.calibration == calibration }
            ?.takeIf { it.seconds > 0 }
            ?.let { it.exact.toDouble() / it.seconds }

    private fun LabReport.bandLine(smoothing: String, calibration: String): String? =
        bands.firstOrNull { it.smoothing == smoothing && it.calibration == calibration }?.takeIf { it.seconds > 0 }
            ?.let {
                "$smoothing + $calibration: точно ${percent(it.exact.toDouble() / it.seconds)}, " +
                    "на полосу мимо ${percent(it.oneOff.toDouble() / it.seconds)}, " +
                    "средняя ошибка ${round2(it.meanError)} полосы, ${it.seconds} пар·с"
            }

    private const val NO_DISTANCES = "нет секунд с расстоянием пары (шаги сценария с расстояниями или отметки)"

    private fun smoothings(report: LabReport, facts: CardFacts): List<TechniqueCard> {
        val none = Calibrations.NONE
        val shares = Smoothings.ALL.associateWith { report.exactShare(it, none) }
        if (shares.values.all { it == null }) {
            return Smoothings.ALL.map {
                TechniqueCard(it, Verdict.INSUFFICIENT, CRITERION_SMOOTHING, missing = NO_DISTANCES)
            }
        }
        facts.fewDistances()?.let { few ->
            return Smoothings.ALL.map {
                TechniqueCard(
                    it,
                    Verdict.INSUFFICIENT,
                    CRITERION_SMOOTHING,
                    listOfNotNull(report.bandLine(it, none)),
                    few,
                )
            }
        }
        // The first of the best: the game's EMA keeps its place on a tie.
        val best = Smoothings.ALL.filter { shares[it] != null }.maxBy { shares.getValue(it)!! }
        return Smoothings.ALL.map { id ->
            val line = report.bandLine(id, none)
            when {
                line == null -> TechniqueCard(id, Verdict.INSUFFICIENT, CRITERION_SMOOTHING, missing = NO_DISTANCES)

                id == best -> TechniqueCard(id, Verdict.KEEP, CRITERION_SMOOTHING, listOf(line))

                // No worse than the best: nothing to drop it for.
                shares[id] == shares[best] -> TechniqueCard(
                    id,
                    Verdict.INSUFFICIENT,
                    CRITERION_SMOOTHING,
                    listOf(line),
                    "ничья с $best",
                )

                else -> TechniqueCard(
                    id,
                    Verdict.DROP,
                    CRITERION_SMOOTHING,
                    listOfNotNull(line, report.bandLine(best, none)),
                )
            }
        }
    }

    private fun calibrations(report: LabReport, facts: CardFacts): List<TechniqueCard> {
        val ema = Smoothings.EMA
        val none = report.exactShare(ema, Calibrations.NONE)
        val noneLine = report.bandLine(ema, Calibrations.NONE)
        val offsets = report.calibrations.associate { it.id to it.offsetsDb }
        val few = facts.fewDistances()
        if (none != null && few != null) {
            val criteria = mapOf(
                Calibrations.NONE to CRITERION_NONE,
                Calibrations.MODEL to CRITERION_MODEL,
                Calibrations.TOUCH to CRITERION_TOUCH,
            )
            return criteria.map { (id, criterion) ->
                val line = report.bandLine(ema, id)
                TechniqueCard(id, Verdict.INSUFFICIENT, criterion, listOfNotNull(line, noneLine).distinct(), few)
            }
        }

        fun gains(id: String): Boolean? {
            val share = report.exactShare(ema, id) ?: return null
            return none != null && share >= none + CALIBRATION_MIN_GAIN - 1e-9
        }

        val model = when {
            none == null -> TechniqueCard(
                Calibrations.MODEL,
                Verdict.INSUFFICIENT,
                CRITERION_MODEL,
                missing = NO_DISTANCES,
            )

            offsets[Calibrations.MODEL].isNullOrEmpty() -> TechniqueCard(
                Calibrations.MODEL,
                Verdict.INSUFFICIENT,
                CRITERION_MODEL,
                listOfNotNull(noneLine),
                "нет показаний в 1 м, чтобы откалибровать пары моделей",
            )

            else -> {
                val numbers = listOfNotNull(report.bandLine(ema, Calibrations.MODEL), noneLine)
                val verdict = if (gains(Calibrations.MODEL) == true) Verdict.KEEP else Verdict.DROP
                TechniqueCard(Calibrations.MODEL, verdict, CRITERION_MODEL, numbers)
            }
        }

        val spreads = report.touchSpreads.filter { it.touches >= 2 }
        val spreadLines = report.touchSpreads.map {
            "${it.pair} ${it.direction}: касаний ${it.touches}, разброс ${it.spreadDb} dB, дрейф ${it.driftDb} dB"
        }
        val touch = when {
            none == null -> TechniqueCard(
                Calibrations.TOUCH,
                Verdict.INSUFFICIENT,
                CRITERION_TOUCH,
                spreadLines,
                NO_DISTANCES,
            )

            offsets[Calibrations.TOUCH].isNullOrEmpty() || spreads.isEmpty() -> TechniqueCard(
                Calibrations.TOUCH,
                Verdict.INSUFFICIENT,
                CRITERION_TOUCH,
                listOfNotNull(noneLine) + spreadLines,
                if (report.touches.isEmpty()) "касаний не найдено" else "ни одна пара не чокалась дважды",
            )

            else -> {
                val numbers = listOfNotNull(report.bandLine(ema, Calibrations.TOUCH), noneLine) + spreadLines
                val steady = spreads.all { it.spreadDb <= TOUCH_MAX_SPREAD_DB }
                val verdict = if (gains(Calibrations.TOUCH) == true && steady) Verdict.KEEP else Verdict.DROP
                TechniqueCard(Calibrations.TOUCH, verdict, CRITERION_TOUCH, numbers)
            }
        }

        val plain = when {
            none == null -> TechniqueCard(
                Calibrations.NONE,
                Verdict.INSUFFICIENT,
                CRITERION_NONE,
                missing = NO_DISTANCES,
            )

            else -> {
                val beaten = model.verdict == Verdict.KEEP || touch.verdict == Verdict.KEEP
                TechniqueCard(
                    Calibrations.NONE,
                    if (beaten) Verdict.DROP else Verdict.KEEP,
                    CRITERION_NONE,
                    listOfNotNull(noneLine),
                )
            }
        }
        return listOf(plain, model, touch)
    }

    /** The agreement of a carry classifier's answers with the truth: in the pocket is in the pocket, and so on. */
    private fun agrees(truth: String, said: String): Boolean = when (truth) {
        "in_pocket" -> said == "in_pocket"

        "in_hand" -> said == "in_hand"

        // On a table or in a locked hand: anything but «in the pocket» and nothing said.
        else -> said != "in_pocket" && said != "none"
    }

    private fun carry(report: LabReport): List<TechniqueCard> {
        val techs = listOf(LabMerge.CARRY_V1, LabMerge.CARRY_V2)
            .filter { tech -> report.carry.any { it.tech == tech && it.said != "none" } }
        if (techs.isEmpty()) return emptyList()
        class Score(val seconds: Int, val agreed: Int) {
            val share: Double get() = agreed.toDouble() / seconds.coerceAtLeast(1)
        }
        val scores = techs.associateWith { tech ->
            val rows = report.carry.filter { it.tech == tech }
            Score(rows.sumOf { it.seconds }, rows.filter { agrees(it.truth, it.said) }.sumOf { it.seconds })
        }
        val lines = scores.mapValues { (tech, score) ->
            "$tech: совпало ${score.agreed} из ${score.seconds} с (${percent(score.share)})"
        }
        val pocket = techs.associateWith { tech ->
            report.carry.filter { it.tech == tech && it.truth == "in_pocket" }.sumOf { it.seconds }
        }
        val ready = techs.size == 2 && scores.values.all { it.seconds >= CARRY_MIN_SECONDS } &&
            pocket.values.all { it >= CARRY_MIN_SECONDS }
        if (!ready) {
            val missing = when {
                techs.size < 2 -> "в журналах только ${techs.single()}: сравнивать не с чем"
                scores.values.any { it.seconds < CARRY_MIN_SECONDS } -> "меньше $CARRY_MIN_SECONDS с с разметкой"
                else -> "в кармане по разметке меньше $CARRY_MIN_SECONDS с (${pocket.values.min()} с)"
            }
            return techs.map {
                TechniqueCard(it, Verdict.INSUFFICIENT, CRITERION_CARRY, lines.values.toList(), missing)
            }
        }
        // v1 (the game's) keeps its place on a tie.
        val best = techs.maxBy { scores.getValue(it).share }
        return techs.map {
            TechniqueCard(it, if (it == best) Verdict.KEEP else Verdict.DROP, CRITERION_CARRY, lines.values.toList())
        }
    }

    private fun witness(result: LabReportWitness?): TechniqueCard {
        val judged = (result?.right ?: 0) + (result?.wrong ?: 0)
        val numbers = listOfNotNull(
            result?.let {
                "выведено ${it.inferred} пар·с за ${it.seconds} с; верно ${it.right}, неверно ${it.wrong}" +
                    it.pairs.takeIf { pairs -> pairs.isNotEmpty() }?.joinToString(prefix = "; пары ").orEmpty()
            },
        )
        if (result == null || judged < WITNESS_MIN_CASES) {
            return TechniqueCard(
                WITNESS,
                Verdict.INSUFFICIENT,
                CRITERION_WITNESS,
                numbers,
                "меньше $WITNESS_MIN_CASES выведенных пар·с с расстоянием (нужны три телефона и расстояния)",
            )
        }
        val right = result.right.toDouble() / judged
        val verdict = if (right >= WITNESS_MIN_RIGHT) Verdict.KEEP else Verdict.DROP
        return TechniqueCard(WITNESS, verdict, CRITERION_WITNESS, numbers + "верно ${percent(right)}")
    }

    private fun percent(share: Double): String = "${round1(share * 100)} %"

    private fun round2(value: Double): String = (kotlin.math.round(value * 100) / 100).toString()
}
