package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

// The radio lab's runs on the server (docs/adr/0017-radar-techniques-and-big-run.md §5, docs/radar-run.md step 1):
// test phones of the debug build join a run by its code, follow its plan by the server's clock and upload their lab
// logs; an admin runs the console and reads the report. No players, no accounts, no positions. The phone routes exist
// only while the server has [ServerFeature.RADIO_LAB] on; the plans themselves are data in `app.hovanki.shared.lab`
// (`LabRunScripts`), the wire carries only their id and version.
//
// The field log (docs/adr/0018-field-test-build.md §3) is the same log in a real game: the game is the run
// ([LabRunKind.GAME]), its phones (the field build, a tester who agreed) join it with their game token and upload
// through the same route; only there `gps` carries coordinates. Behind [ServerFeature.FIELD_LOG].

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

/**
 * What a run is (docs/adr/0018-field-test-build.md §3.1): a run of the lab that an admin made ([LAB]), or a real
 * game's field log ([GAME]): its phones are the game's players who agreed, and only its `gps` events carry coordinates.
 */
@Serializable
enum class LabRunKind { LAB, GAME }

/**
 * A phone of the field build (`preview`) joins its game's field log ([ApiRoutes.GAME_FIELD_JOIN], with the game's
 * player token; docs/adr/0018-field-test-build.md §3.1): who it is, for the report, and when its tester agreed to the
 * log ([consentAtMillis], server time, ADR 0018 §3.4). No consent, no join.
 */
@Serializable
data class FieldJoinRequest(
    val model: String? = null,
    val os: String? = null,
    val build: String? = null,
    val commit: String? = null,
    val capabilities: LabCapabilities = LabCapabilities(),
    /** When the tester agreed, server time; null: never (the server refuses). */
    val consentAtMillis: Long? = null,
)

/**
 * The game's field run took the phone: from now on it uploads its log to [runId] with the device [token]
 * ([ApiRoutes.LAB_EVENTS], as the lab does), every [uploadIntervalMillis], at most [maxEvents] events and
 * [maxBodyBytes] (uncompressed) per upload. [label]: the device's name in the run's log (the player's id), [salt]: for
 * hashing the peers' ids (hex), the same on every phone of the run. The field log is thinned on the phone (ADR 0018
 * §3.2): a reading of the radio once per [rxEveryMillis] per peer (their count, median and loudest), the raw frames
 * and the air once per [frameEveryMillis], a GPS fix at most once per [gpsEveryMillis].
 */
@Serializable
data class FieldJoinResponse(
    val runId: LabRunId,
    val deviceId: String,
    /** Bearer token of the upload; lives with the run. */
    val token: String,
    val label: String,
    val salt: String,
    val serverTimeMillis: Long,
    val uploadIntervalMillis: Long = FieldUpload.INTERVAL_MILLIS,
    val maxEvents: Int = LabUpload.MAX_EVENTS,
    val maxBodyBytes: Int = LabUpload.MAX_BODY_BYTES,
    val rxEveryMillis: Long = FieldUpload.RX_EVERY_MILLIS,
    val frameEveryMillis: Long = FieldUpload.FRAME_EVERY_MILLIS,
    val gpsEveryMillis: Long = FieldUpload.GPS_EVERY_MILLIS,
)

/** The field log's pace on the phone (docs/adr/0018-field-test-build.md §3.2), unless the server says otherwise. */
object FieldUpload {
    const val INTERVAL_MILLIS = 10_000L
    const val RX_EVERY_MILLIS = 1_000L
    const val FRAME_EVERY_MILLIS = 10_000L
    const val GPS_EVERY_MILLIS = 1_000L
}

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
    /** A lab run, or a game's field log (then [gameId], no code to join by and no plan). */
    val kind: LabRunKind = LabRunKind.LAB,
    val gameId: String? = null,
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
    /** A field log's device: when its tester agreed (docs/adr/0018-field-test-build.md §3.4); null in a lab run. */
    val consentAtMillis: Long? = null,
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
