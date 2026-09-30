package app.hovanki.client.lab

import app.hovanki.client.network.ApiException
import app.hovanki.shared.lab.LabPlanState
import app.hovanki.shared.lab.LabRunPlan
import app.hovanki.shared.lab.LabRunScript
import app.hovanki.shared.lab.LabRunScripts
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.LabEventsResponse
import app.hovanki.shared.protocol.LabJoinRequest
import app.hovanki.shared.protocol.LabJoinResponse
import app.hovanki.shared.protocol.LabRunAction
import app.hovanki.shared.protocol.LabRunId
import app.hovanki.shared.protocol.LabRunStateView

/**
 * The lab's server for the tests: one run of [script] with the plan on [serverNow], the admin's buttons ([press]),
 * uploads remembered ([uploads]) and failures on demand ([failures], [refusal]).
 */
internal class FakeLabApi(
    private val serverNow: () -> Long = { 0L },
    private val script: LabRunScript = LabRunScripts.E2E,
    private val scenarioId: String = script.id,
) : LabApi {
    val runId = LabRunId("run-1")
    var plan = LabPlanState()
        private set

    class Upload(val batch: LabBatch, val body: ByteArray, val gzip: Boolean) {
        val lines: List<String> get() = batch.jsonl.decodeToString().lines().filter { it.isNotEmpty() }
    }

    val uploads = mutableListOf<Upload>()
    val joins = mutableListOf<LabJoinRequest>()

    /** Label → UWB token, as the server lists them; this device posts as [uwbLabel]. */
    val uwbTokens = mutableMapOf<String, String>()
    var uwbLabel = "A"
    var polls = 0
        private set

    /** The next uploads that fail as if offline. */
    var failures = 0

    /** Every upload from now on is refused with this. */
    var refusal: ApiException? = null

    /** The highest seq the server has. */
    var ackedSeq = 0L
        private set

    override suspend fun join(request: LabJoinRequest): LabJoinResponse {
        joins += request
        if (request.code != CODE) throw ApiException(404, ApiError(ErrorCode.NOT_FOUND, "Not found"))
        return LabJoinResponse(
            runId = runId,
            deviceId = "device-1",
            token = TOKEN,
            radarToken = RADAR_TOKEN,
            salt = SALT,
            scenarioId = scenarioId,
            scenarioVersion = script.version,
            labels = script.labels,
            state = view(),
        )
    }

    override suspend fun state(runId: LabRunId, token: String): LabRunStateView {
        check(token == TOKEN)
        polls++
        return view()
    }

    override suspend fun advance(runId: LabRunId, token: String, action: LabRunAction): LabRunStateView {
        check(token == TOKEN)
        press(action)
        return view()
    }

    override suspend fun uwbToken(runId: LabRunId, token: String, uwbToken: String): LabRunStateView {
        check(token == TOKEN)
        uwbTokens[uwbLabel] = uwbToken
        return view()
    }

    override suspend fun upload(
        runId: LabRunId,
        token: String,
        batch: LabBatch,
        body: ByteArray,
        gzip: Boolean,
    ): LabEventsResponse {
        check(token == TOKEN)
        uploads += Upload(batch, body, gzip)
        refusal?.let { throw it }
        if (failures > 0) {
            failures--
            throw IllegalStateException("offline")
        }
        ackedSeq = maxOf(ackedSeq, batch.seqTo)
        return LabEventsResponse(ackedSeq, serverNow())
    }

    /** The admin's button. */
    fun press(action: LabRunAction) {
        plan = LabRunPlan.apply(script, plan, action, serverNow())
    }

    fun view(): LabRunStateView {
        plan = LabRunPlan.advanceByTime(script, plan, serverNow())
        return LabRunPlan.toView(plan, runId, serverNow()).copy(uwbTokens = uwbTokens.toMap())
    }

    companion object {
        const val CODE = "ABC234"
        const val TOKEN = "device-token"
        const val RADAR_TOKEN = "a1b2c3d4"
        const val SALT = "00ff00ff00ff00ff"

        fun closed() = ApiException(
            409,
            ApiError(ErrorCode.WRONG_STATE, "The run is closed", reason = ErrorReason.LAB_RUN_CLOSED),
        )
    }
}
