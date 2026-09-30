package app.hovanki.client.lab

import app.hovanki.client.network.ApiException
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.createHttpClient
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.LabEventsResponse
import app.hovanki.shared.protocol.LabRunId
import app.hovanki.shared.protocol.LabUpload
import app.hovanki.shared.protocol.protocolJson
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HttpLabApiTest {
    private val batch = LabBatch(3, 7, 1_000, 2_000, 5, "{\"k\":\"tick\"}\n".encodeToByteArray())

    private class Server(private val status: HttpStatusCode, private val answer: String) {
        val requests = mutableListOf<Pair<HttpRequestData, ByteArray>>()
        val api = HttpLabApi(
            createHttpClient(
                MockEngine { request ->
                    requests += request to request.body.toByteArray()
                    respond(answer, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
                logRequests = false,
            ),
            ServerUrl("http://lab.test:8080/"),
        )
    }

    @Test
    fun theUploadIsRawJsonlWithItsBoundsInTheQuery() = runTest {
        val server = Server(HttpStatusCode.OK, protocolJson.encodeToString(LabEventsResponse(7, 42)))
        val body = byteArrayOf(1, 2, 3)
        val answer = server.api.upload(LabRunId("run-1"), "device-token", batch, body, gzip = true)
        assertEquals(LabEventsResponse(7, 42), answer)

        val (request, sent) = server.requests.single()
        assertEquals("/api/v1/lab/runs/run-1/events", request.url.encodedPath)
        assertEquals("3", request.url.parameters[LabUpload.PARAM_SEQ_FROM])
        assertEquals("7", request.url.parameters[LabUpload.PARAM_SEQ_TO])
        assertEquals("5", request.url.parameters[LabUpload.PARAM_COUNT])
        assertEquals("1000", request.url.parameters[LabUpload.PARAM_T_FROM])
        assertEquals("2000", request.url.parameters[LabUpload.PARAM_T_TO])
        assertEquals("Bearer device-token", request.headers[HttpHeaders.Authorization])
        assertEquals("gzip", request.headers[HttpHeaders.ContentEncoding])
        assertEquals(LabUpload.CONTENT_TYPE, request.body.contentType?.toString())
        assertContentEquals(body, sent)

        server.api.upload(LabRunId("run-1"), "device-token", batch, batch.jsonl, gzip = false)
        assertNull(server.requests.last().first.headers[HttpHeaders.ContentEncoding])
    }

    @Test
    fun aRefusalIsAnApiException() = runTest {
        val error = ApiError(ErrorCode.WRONG_STATE, "closed", reason = ErrorReason.LAB_RUN_CLOSED)
        val server = Server(HttpStatusCode.Conflict, protocolJson.encodeToString(error))
        val refused = assertFailsWith<ApiException> {
            server.api.upload(LabRunId("run-1"), "device-token", batch, batch.jsonl, gzip = false)
        }
        assertEquals(409, refused.status)
        assertEquals(ErrorReason.LAB_RUN_CLOSED, refused.reason)
        assertEquals("409 LAB_RUN_CLOSED", LabUploader.describe(refused))
    }
}
