package app.hovanki.shared.lab

import kotlinx.serialization.Serializable

// The report of a radio lab's run (docs/adr/0017-radar-techniques-and-big-run.md §5.5), computed by the server from the
// logs the run's devices uploaded ([LabReportBuilder]) and shown in the admin. Numbers, labels and phone models only:
// no coordinates, no players. Version 2 adds the techniques that compete (docs/adr/0017-radar-techniques-and-big-run.md
// §2.3, §3, §7; docs/radar-run.md step 4): the touches, the calibrations, the smoothings, «without X», the witness, the
// pocket's classifiers and a card per technique; a version 1 report has them empty.

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
    val touches: List<LabReportTouch> = emptyList(),
    val touchPairs: List<LabReportTouchPair> = emptyList(),
    val touchDetector: LabReportTouchDetector? = null,
    val calibration: List<LabReportBandError> = emptyList(),
    val smoothing: List<LabReportBandError> = emptyList(),
    val without: List<LabReportWithout> = emptyList(),
    val witness: LabReportWitness? = null,
    val carryClassifiers: List<LabReportClassifier> = emptyList(),
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
)

/** The pocket: for [seconds] the marks' truth was [truth] and the carry monitor said [said]. */
@Serializable
data class LabReportCarry(val label: String, val truth: String, val said: String, val seconds: Int)

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
 * A touch of [a] and [b] at [atMillis] ([source]: [BUTTON] both pressed «We touched», [DETECTOR] the jolts and the
 * signal found it, [BOTH]): the loudest each heard of the other then ([rssiAToB]: what b heard of a), the jolts (g)
 * and how far apart they were.
 */
@Serializable
data class LabReportTouch(
    val a: String,
    val b: String,
    val atMillis: Long,
    val source: String,
    val rssiAToB: Int? = null,
    val rssiBToA: Int? = null,
    val impactA: Double? = null,
    val impactB: Double? = null,
    val skewMillis: Long? = null,
) {
    companion object {
        const val BUTTON = "button"
        const val DETECTOR = "detector"
        const val BOTH = "both"
    }
}

/**
 * The touch calibration of the pair [a]–[b] (ADR 0017 §3): its [touches], the offset each way from the first ones
 * ([offsetAToB]: dB added to what b hears of a), how far the first three spread and the last against them (drift), dB.
 */
@Serializable
data class LabReportTouchPair(
    val a: String,
    val b: String,
    val touches: Int,
    val offsetAToB: Double? = null,
    val offsetBToA: Double? = null,
    val spreadDb: Double? = null,
    val driftDb: Double? = null,
)

/** The detector against the button: of [buttonTouches], [found]; [falseAlarms]: found where nobody pressed. */
@Serializable
data class LabReportTouchDetector(val buttonTouches: Int, val found: Int, val falseAlarms: Int)

/**
 * A technique's band against the truth of the run's distances ([BandTruth]): [seconds] of the [directions] compared,
 * [wrong] of them; [uncalibrated]: directions the calibration had no offset for (taken as heard).
 */
@Serializable
data class LabReportBandError(
    val tech: String,
    val seconds: Int,
    val wrong: Int,
    val errorPercent: Double? = null,
    val directions: Int = 0,
    val uncalibrated: Int = 0,
)

/**
 * The band with every channel against the band without the channel [tech] (ADR 0017 §7): of the [seconds] either
 * heard something, [equalPercent] gave the same band; [aloneSeconds]: seconds only [tech] heard the direction.
 */
@Serializable
data class LabReportWithout(
    val tech: String,
    val directions: Int,
    val seconds: Int,
    val equalPercent: Double? = null,
    val aloneSeconds: Int = 0,
)

/**
 * `infer.witness`: [inferredSeconds] pair-seconds a witness said better of a pair that heard nothing; [withTruth] of
 * them in a step with the pair's distance, [right] the band the distance says.
 */
@Serializable
data class LabReportWitness(val inferredSeconds: Int, val withTruth: Int, val right: Int)

/** The pocket's classifier [tech] on [label]: of the [seconds] with the marks' truth, [agree] said the truth. */
@Serializable
data class LabReportClassifier(
    val label: String,
    val tech: String,
    val seconds: Int,
    val agree: Int,
    val agreePercent: Double? = null,
)

/**
 * A technique's card (ADR 0017 §2.1): its [id] and [group], the [criterion] written before the measurements, the
 * [numbers] of this run and the [verdict]: [KEEP], [DROP] or [TOO_LITTLE] data.
 */
@Serializable
data class LabReportCard(
    val id: String,
    val group: String,
    val verdict: String,
    val criterion: String,
    val numbers: String,
) {
    companion object {
        const val KEEP = "keep"
        const val DROP = "drop"
        const val TOO_LITTLE = "too_little_data"
    }
}
