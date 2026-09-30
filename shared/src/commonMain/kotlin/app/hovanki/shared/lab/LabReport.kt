package app.hovanki.shared.lab

import kotlinx.serialization.Serializable

// The report of a radio lab's run (docs/adr/0017-radar-techniques-and-big-run.md §5.5), computed by the server from the
// logs the run's devices uploaded ([LabReportBuilder]) and shown in the admin. Numbers, labels and phone models only:
// no coordinates, no players.

@Serializable
data class LabReport(
    val version: Int = 1,
    val runId: String,
    val computedAtMillis: Long,
    val scenarioId: String? = null,
    val devices: List<LabReportDevice> = emptyList(),
    val problems: List<String> = emptyList(),
    val steps: List<LabReportStep> = emptyList(),
    val carry: List<LabReportCarry> = emptyList(),
    val masks: List<LabReportMask> = emptyList(),
    val haptics: List<LabReportHaptic> = emptyList(),
    val battery: List<LabReportBattery> = emptyList(),
    val ticks: List<LabReportTicks> = emptyList(),
    val noise: List<LabReportNoise> = emptyList(),
    // Step 4 (docs/radar-run.md §4): the touches, the bands against the distances, «without X», the witness, the cards.
    val touches: List<LabReportTouch> = emptyList(),
    val touchSpreads: List<LabReportTouchSpread> = emptyList(),
    /** The touches marked by hand (`mark` with `action = touch`) the detector didn't find. */
    val missedTouches: Int = 0,
    val calibrations: List<LabReportCalibration> = emptyList(),
    val bands: List<LabReportBands> = emptyList(),
    val without: List<LabReportWithout> = emptyList(),
    val witness: LabReportWitness? = null,
    val cards: List<LabReportCard> = emptyList(),
)

/** A device of the run, as its log said: [clockOffsetsMillis] every offset to the server's clock it measured. */
@Serializable
data class LabReportDevice(
    val label: String,
    val deviceId: String? = null,
    val model: String? = null,
    val os: String? = null,
    val build: String? = null,
    val commit: String? = null,
    val schema: Int? = null,
    val events: Int,
    val clockOffsetsMillis: List<Long> = emptyList(),
    val radarToken: String? = null,
)

/** A stretch of the run: a step (from the `step` events / the run's marks), or «before the first step» (index -1). */
@Serializable
data class LabReportStep(
    val index: Int,
    val id: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val directions: List<LabReportDirection> = emptyList(),
)

/**
 * Who heard whom: [from] the sender (a label, or `?token` when nobody advertised it), [to] the listener, [channel]
 * `api/via`. [longestGapMillis]: the longest stretch without a reading, the step's bounds included; [during]: what the
 * listener's app went through meanwhile.
 */
@Serializable
data class LabReportDirection(
    val from: String,
    val to: String,
    val channel: String,
    val readings: Int,
    val perSecond: Double,
    val medianRssi: Int,
    val p80Rssi: Int,
    val minRssi: Int,
    val maxRssi: Int,
    val longestGapMillis: Long,
    val during: String = "",
    /** The radar's channel (the `rx` events' `tech`: `ble.name`…); null in logs written before the channels. */
    val tech: String? = null,
)

/**
 * The pocket: for [seconds] the marks' truth was [truth] and the classifier [tech] said [said]: `carry.v1` the carry
 * monitor of the game, `carry.v2` the classifier in the shadow (docs/radio-lab.md §7.3).
 */
@Serializable
data class LabReportCarry(
    val label: String,
    val truth: String,
    val said: String,
    val seconds: Int,
    val tech: String = LabMerge.CARRY_V1,
)

/** The overflow masks [label] heard: [matched] had every bit a probe sent then, [decoded] carried a token. */
@Serializable
data class LabReportMask(val label: String, val frames: Int, val matched: Int, val decoded: Int)

/** The vibration attempts of [label] by [kind] (`core_haptics`, `vibrator`…): what came of them. */
@Serializable
data class LabReportHaptic(
    val label: String,
    val kind: String,
    val played: Int,
    val errors: Int,
    val skipped: Int,
    val engineStopped: Int,
)

/** The battery's level (0…1) at [label]'s first and last sample. */
@Serializable
data class LabReportBattery(
    val label: String,
    val firstLevel: Double? = null,
    val lastLevel: Double? = null,
    val samples: Int,
)

/**
 * [label]'s ticks (one a second while the app lives) and its [gaps], silences longer than
 * [LabSchema.TICK_GAP_MILLIS]: the app suspended. [longestGapMillis]: the longest time between two ticks.
 */
@Serializable
data class LabReportTicks(val label: String, val ticks: Int, val gaps: Int, val longestGapMillis: Long)

/**
 * The street's noise [label] heard: the frames no channel of the radar read (the log's `air` events, one a second
 * while it listened), in [seconds] of them; [iBeacons] of any UUID, overflow [masks], [apple] frames with Apple's
 * manufacturer data; [maxFramesPerSecond] the busiest second.
 */
@Serializable
data class LabReportNoise(
    val label: String,
    val seconds: Int,
    val frames: Int,
    val iBeacons: Int,
    val masks: Int,
    val apple: Int,
    val maxFramesPerSecond: Int,
)

/**
 * A touch the detector found (docs/adr/0017-radar-techniques-and-big-run.md §3): the pair [pair] (`A|B`) at [atMillis]
 * (server time), [rssi] the loudest reading of each direction (`from|to` → dBm) around it, [peaksG] each phone's
 * impact (label → g), [markAtMillis] the hand mark «чокнулись» it matched, if any.
 */
@Serializable
data class LabReportTouch(
    val pair: String,
    val atMillis: Long,
    val rssi: Map<String, Int> = emptyMap(),
    val peaksG: Map<String, Double> = emptyMap(),
    val markAtMillis: Long? = null,
)

/**
 * The touches of [pair] heard in [direction] (`from|to`): [touches] of them, [spreadDb] the loudest minus the quietest,
 * [driftDb] the last against the first. The criterion of `calib.touch`: a spread of at most 6 dB.
 */
@Serializable
data class LabReportTouchSpread(
    val pair: String,
    val direction: String,
    val touches: Int,
    val spreadDb: Int,
    val driftDb: Int,
)

/** A calibration [id] (`calib.none`, `calib.model`, `calib.touch`): the offset of each direction (`from|to` → dB). */
@Serializable
data class LabReportCalibration(val id: String, val offsetsDb: Map<String, Double> = emptyMap())

/**
 * The bands of [smoothing] under [calibration] against the truth of the distances, over the [seconds] (pair × second)
 * with a distance: [exact], [oneOff] one band away, [wrong] further; [meanError] the mean distance in bands.
 */
@Serializable
data class LabReportBands(
    val smoothing: String,
    val calibration: String,
    val seconds: Int,
    val exact: Int,
    val oneOff: Int,
    val wrong: Int,
    val meanError: Double,
)

/**
 * «Без X» (ADR 0017 §7, section 3): over [seconds] (pair × second where either had a band), the pair's band with every
 * channel and without [tech] were the [same]; in [onlyChannel] of them [tech] was the only channel the pair heard.
 */
@Serializable
data class LabReportWithout(val tech: String, val seconds: Int, val same: Int, val onlyChannel: Int)

/**
 * `infer.witness` over [seconds]: [inferred] (pair × second) the pair's silence was read through a third phone as
 * WARM; the truth of the distances agreed in [right], not in [wrong] (the rest had no distance); [pairs] inferred.
 */
@Serializable
data class LabReportWitness(
    val seconds: Int,
    val inferred: Int,
    val right: Int,
    val wrong: Int,
    val pairs: List<String> = emptyList(),
)

/**
 * A technique's card (ADR 0017 §7, section 2): [tech], [verdict] (`KEEP`, `DROP`, `INSUFFICIENT`), [criterion] the
 * catalog's words, [numbers] what the run measured, [missing] what the run lacked for a verdict.
 */
@Serializable
data class LabReportCard(
    val tech: String,
    val verdict: String,
    val criterion: String,
    val numbers: List<String> = emptyList(),
    val missing: String? = null,
)
