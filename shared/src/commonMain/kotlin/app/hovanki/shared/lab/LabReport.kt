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
