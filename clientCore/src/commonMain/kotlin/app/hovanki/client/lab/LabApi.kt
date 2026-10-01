package app.hovanki.client.lab

import app.hovanki.client.network.ApiException
import app.hovanki.client.network.HttpSupport
import app.hovanki.client.network.ServerUrl
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.FieldJoinRequest
import app.hovanki.shared.protocol.FieldJoinResponse
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.LabAdvanceRequest
import app.hovanki.shared.protocol.LabEventsResponse
import app.hovanki.shared.protocol.LabJoinRequest
import app.hovanki.shared.protocol.LabJoinResponse
import app.hovanki.shared.protocol.LabRunAction
import app.hovanki.shared.protocol.LabRunId
import app.hovanki.shared.protocol.LabRunStateView
import app.hovanki.shared.protocol.LabUpload
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent

/**
 * The radio lab's runs on the server (docs/adr/0017-radar-techniques-and-big-run.md §5), the phone's side: join a run
 * by its code, follow its plan, press its buttons and upload the lab's log. The server answers 404 on every route while
 * its `RADIO_LAB` flag is off. [token]: the device token of the join ([LabJoinResponse.token]).
 *
 * Throws [ApiException] when the server rejects a call, and I/O or serialization exceptions on network problems.
 */
interface LabApi {
    /**
     * [accountToken]: the logged-in account's, sent along where there is one: a test server lets only staff join
     * (docs/adr/0018-field-test-build.md §4.D).
     */
    suspend fun join(request: LabJoinRequest, accountToken: String? = null): LabJoinResponse

    /**
     * The field log (docs/adr/0018-field-test-build.md §3.1): this phone joins the log of its game [gameId] with the
     * player's game token [playerToken]; the uploads then go through [upload] with the answer's token. 404 while the
     * server has FIELD_LOG off.
     */
    suspend fun fieldJoin(gameId: GameId, playerToken: String, request: FieldJoinRequest): FieldJoinResponse

    /**
     * This phone left the field log of its game [gameId] (the log stopped, or the tester took the consent back): the
     * server's own events of the game stop naming the player. 404 while the server has FIELD_LOG off.
     */
    suspend fun fieldLeave(gameId: GameId, playerToken: String) = Unit

    suspend fun state(runId: LabRunId, token: String): LabRunStateView

    suspend fun advance(runId: LabRunId, token: String, action: LabRunAction): LabRunStateView

    /** [body]: [batch]'s JSONL, gzipped when [gzip]; the batch's bounds go as the query's parameters. */
    suspend fun upload(
        runId: LabRunId,
        token: String,
        batch: LabBatch,
        body: ByteArray,
        gzip: Boolean,
    ): LabEventsResponse
}

/** [LabApi] over HTTP: JSON, and the upload as raw JSONL ([LabUpload.CONTENT_TYPE]). */
class HttpLabApi(client: HttpClient, serverUrl: ServerUrl) : LabApi {
    private val http = HttpSupport(client, serverUrl)

    override suspend fun join(request: LabJoinRequest, accountToken: String?): LabJoinResponse =
        http.post(ApiRoutes.LAB_JOIN, accountToken, request)

    override suspend fun fieldJoin(gameId: GameId, playerToken: String, request: FieldJoinRequest): FieldJoinResponse =
        http.post(ApiRoutes.gameFieldJoin(gameId), playerToken, request)

    override suspend fun fieldLeave(gameId: GameId, playerToken: String): Unit =
        http.post(ApiRoutes.gameFieldLeave(gameId), playerToken)

    override suspend fun state(runId: LabRunId, token: String): LabRunStateView =
        http.get(ApiRoutes.labState(runId), token)

    override suspend fun advance(runId: LabRunId, token: String, action: LabRunAction): LabRunStateView =
        http.post(ApiRoutes.labAdvance(runId), token, LabAdvanceRequest(action))

    override suspend fun upload(
        runId: LabRunId,
        token: String,
        batch: LabBatch,
        body: ByteArray,
        gzip: Boolean,
    ): LabEventsResponse = http.send(HttpMethod.Post, ApiRoutes.labEvents(runId), token) {
        parameter(LabUpload.PARAM_SEQ_FROM, batch.seqFrom)
        parameter(LabUpload.PARAM_SEQ_TO, batch.seqTo)
        parameter(LabUpload.PARAM_COUNT, batch.count)
        parameter(LabUpload.PARAM_T_FROM, batch.tFrom)
        parameter(LabUpload.PARAM_T_TO, batch.tTo)
        if (gzip) header(HttpHeaders.ContentEncoding, "gzip")
        // Bytes as they are: the JSON content negotiation leaves an OutgoingContent alone.
        setBody(ByteArrayContent(body, ContentType.parse(LabUpload.CONTENT_TYPE)))
    }.body()
}
