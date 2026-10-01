package app.hovanki.client.lab

import app.hovanki.client.network.ApiException
import app.hovanki.shared.lab.LabPlanState
import app.hovanki.shared.lab.LabRunPlan
import app.hovanki.shared.lab.LabRunScript
import app.hovanki.shared.lab.LabRunScripts
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.FieldJoinRequest
import app.hovanki.shared.protocol.FieldJoinResponse
import app.hovanki.shared.protocol.GameId
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

    class Upload(val batch: LabBatch, val body: ByteArray, val gzip: Boolean, val runId: LabRunId) {
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

    /** The field log's joins: the game, the player's token and what the phone said. */
    val fieldJoins = mutableListOf<Triple<GameId, String, FieldJoinRequest>>()

    /** The field log's leaves: the game and the player's token. */
    val fieldLeaves = mutableListOf<Pair<GameId, String>>()

    /** Every field join from now on is refused with this (404: the server has FIELD_LOG off). */
    var fieldRefusal: Exception? = null

    /** Each field join gets a run of its own (`field-<n>`), as every game has; else [runId]. */
    var fieldRunPerJoin = false

    /** The account tokens the lab's joins came with. */
    val joinAccounts = mutableListOf<String?>()

    override suspend fun join(request: LabJoinRequest, accountToken: String?): LabJoinResponse {
        joins += request
        joinAccounts += accountToken
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

    override suspend fun fieldLeave(gameId: GameId, playerToken: String) {
        fieldLeaves += gameId to playerToken
    }

    override suspend fun fieldJoin(gameId: GameId, playerToken: String, request: FieldJoinRequest): FieldJoinResponse {
        fieldJoins += Triple(gameId, playerToken, request)
        fieldRefusal?.let { throw it }
        return FieldJoinResponse(
            runId = if (fieldRunPerJoin) LabRunId("field-${fieldJoins.size}") else runId,
            deviceId = "device-${fieldJoins.size}",
            token = TOKEN,
            label = "player-1",
            salt = SALT,
            serverTimeMillis = serverNow(),
            uploadIntervalMillis = FIELD_UPLOAD_MILLIS,
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
        uploads += Upload(batch, body, gzip, runId)
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
        const val FIELD_UPLOAD_MILLIS = 10_000L

        fun closed() = ApiException(
            409,
            ApiError(ErrorCode.WRONG_STATE, "The run is closed", reason = ErrorReason.LAB_RUN_CLOSED),
        )
    }
}
