package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

// The radio lab's runs on the server (docs/adr/0017-radar-techniques-and-big-run.md §5, docs/radar-run.md step 1):
// test phones of the debug build join a run by its code, follow its plan by the server's clock and upload their lab
// logs; an admin runs the console and reads the report. No players, no accounts, no positions. The phone routes exist
// only while the server has [ServerFeature.RADIO_LAB] on; the plans themselves are data in `app.hovanki.shared.lab`
// (`LabRunScripts`), the wire carries only their id and version.

@Serializable
@JvmInline
value class LabRunId(val value: String)

@Serializable
enum class LabRunStatus { CREATED, RUNNING, PAUSED, FINISHED }

/** NEXT on CREATED starts step 0; NEXT after the last step finishes the run. REPEAT restarts the current step. */
@Serializable
enum class LabRunAction { NEXT, REPEAT, PAUSE, RESUME }

/** What the phone can do, sent at join; nothing personal, no positions. */
@Serializable
data class LabCapabilities(
    val platform: Platform = Platform.OTHER,
    val bluetooth: BluetoothState = BluetoothState.UNSUPPORTED,
    val uwb: Boolean = false,
    /** Android: how many advertising sets the chip has; null unknown. */
    val advertisingSets: Int? = null,
    val leCoded: Boolean? = null,
    val wifiAware: Boolean? = null,
    val locationPermission: Boolean? = null,
    val notifications: Boolean? = null,
)

/** A phone joins a run by its [code] as [label] (one of the run's labels); who it is, for the report. */
@Serializable
data class LabJoinRequest(
    val code: String,
    val label: String,
    val model: String? = null,
    val os: String? = null,
    val build: String? = null,
    val commit: String? = null,
    val capabilities: LabCapabilities = LabCapabilities(),
)

/** Where the run is, by the server's clock: every phone computes the same step from it. */
@Serializable
data class LabRunStateView(
    val runId: LabRunId,
    val status: LabRunStatus,
    /** -1 before the first step. */
    val stepIndex: Int,
    val stepStartedAtMillis: Long? = null,
    val pausedAtMillis: Long? = null,
    /** Grows with every control action (NEXT, REPEAT, PAUSE, RESUME, finish): a REPEAT keeps the index. */
    val revision: Long,
    val serverTimeMillis: Long,
)

@Serializable
data class LabJoinResponse(
    val runId: LabRunId,
    val deviceId: String,
    /** Bearer token of the phone routes; lives with the run. */
    val token: String,
    /** The token this device advertises (hider name / service data / probe). */
    val radarToken: String,
    /** Salt (hex) for hashing peer ids in the log while in this run. */
    val salt: String,
    val scenarioId: String,
    val scenarioVersion: Int,
    val labels: List<String>,
    val state: LabRunStateView,
)

@Serializable
data class LabAdvanceRequest(val action: LabRunAction)

/** The upload was stored: every event up to [ackedSeq] is on the server. */
@Serializable
data class LabEventsResponse(val ackedSeq: Long, val receivedAtMillis: Long)

/**
 * The upload's wire format ([ApiRoutes.LAB_EVENTS]): the lab log's lines as they are (JSONL), optionally gzipped
 * (`Content-Encoding: gzip`), the batch's bounds as query parameters.
 */
object LabUpload {
    const val CONTENT_TYPE = "application/x-ndjson"
    const val PARAM_SEQ_FROM = "seqFrom"
    const val PARAM_SEQ_TO = "seqTo"
    const val PARAM_COUNT = "count"
    const val PARAM_T_FROM = "tFrom"
    const val PARAM_T_TO = "tTo"
    const val MAX_EVENTS = 5_000

    /** Uncompressed. */
    const val MAX_BODY_BYTES = 4 * 1024 * 1024
}

// Admin side (docs/adr/0008-admin.md): admins only.

/** A plan of the catalog, for the admin's «new run» form. */
@Serializable
data class LabScenarioSummary(
    val id: String,
    val version: Int,
    val title: String,
    val labels: List<String>,
    val steps: Int,
    /** Null when a step waits for the button. */
    val totalSeconds: Int? = null,
)

@Serializable
data class LabStepView(
    val index: Int,
    val id: String,
    val title: String,
    val seconds: Int? = null,
    /** What each label does in this step, for the console. */
    val hints: Map<String, String> = emptyMap(),
)

@Serializable
data class AdminLabRunRequest(val title: String, val scenarioId: String, val reason: String)

@Serializable
data class AdminLabRun(
    val id: LabRunId,
    val code: String,
    /**
     * The QR of the code's payload ([app.hovanki.shared.lab.LabJoinCode.qrPayload]), rows of modules like
     * [AdminEnrollment.qr].
     */
    val qr: List<String>,
    val title: String,
    val scenarioId: String,
    val scenarioVersion: Int,
    val status: LabRunStatus,
    val createdByName: String,
    val createdAtMillis: Long,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val devices: Int = 0,
    val bytes: Long = 0,
    val reportReady: Boolean = false,
)

@Serializable
data class AdminLabRuns(val runs: List<AdminLabRun>, val scenarios: List<LabScenarioSummary>)

@Serializable
data class AdminLabDevice(
    val id: String,
    val label: String,
    val model: String? = null,
    val os: String? = null,
    val build: String? = null,
    val commit: String? = null,
    val capabilities: LabCapabilities = LabCapabilities(),
    val joinedAtMillis: Long,
    val lastChunkAtMillis: Long? = null,
    val lastSeq: Long? = null,
    val bytes: Long = 0,
    val events: Long = 0,
)

/** The live view (ADR 0017 §5.4), from the chunks received so far; memory only. */
@Serializable
data class LabLiveDevice(
    val deviceId: String,
    val label: String,
    val lastEventAtMillis: Long? = null,
    val clockOffsetMillis: Long? = null,
    val appState: String? = null,
    val bluetooth: String? = null,
    val batteryLevel: Double? = null,
    val stepIndex: Int? = null,
)

/** [from]: the label heard (by its radar token), [to]: the listener; [channel]: `api/via` as in the `rx` event. */
@Serializable
data class LabLivePair(
    val from: String,
    val to: String,
    val channel: String,
    val heardInLast10s: Int,
    val medianRssi: Int? = null,
)

@Serializable
data class LabLiveView(
    val atMillis: Long,
    val devices: List<LabLiveDevice> = emptyList(),
    val pairs: List<LabLivePair> = emptyList(),
)

@Serializable
data class AdminLabAdvanceRequest(val action: LabRunAction, val reason: String)

@Serializable
data class AdminLabRunView(
    val run: AdminLabRun,
    val state: LabRunStateView,
    val labels: List<String>,
    val steps: List<LabStepView>,
    val devices: List<AdminLabDevice>,
    val live: LabLiveView,
)
