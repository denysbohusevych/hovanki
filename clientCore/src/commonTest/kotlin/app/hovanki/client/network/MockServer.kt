package app.hovanki.client.network

import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.protocolJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/** A Ktor [MockEngine] as the server: records what the app sent, [handler] answers. */
class MockServer(private val handler: MockRequestHandler) {
    /** One request as the server received it. */
    data class Recorded(
        val method: HttpMethod,
        val path: String,
        val authorization: String?,
        val contentType: String?,
        val body: String,
    )

    val recorded = mutableListOf<Recorded>()

    val client: HttpClient = createHttpClient(
        MockEngine { request ->
            recorded += Recorded(
                method = request.method,
                path = request.url.encodedPath,
                authorization = request.headers[HttpHeaders.Authorization],
                contentType = request.body.contentType?.toString(),
                body = request.body.toByteArray().decodeToString(),
            )
            handler(this, request)
        },
        logRequests = false,
    )

    val serverUrl = ServerUrl("http://game.test:8080/")
}

fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

inline fun <reified T> MockRequestHandleScope.jsonOf(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = json(protocolJson.encodeToString(value), status)

fun MockRequestHandleScope.noContent(): HttpResponseData = respond("", HttpStatusCode.NoContent)

/** The server's error answer; [retryAfter] in seconds like a rate limit's `Retry-After`. */
fun MockRequestHandleScope.apiError(
    status: HttpStatusCode,
    error: ApiError,
    retryAfter: Long? = null,
): HttpResponseData {
    val headers = if (retryAfter == null) {
        headersOf(HttpHeaders.ContentType, "application/json")
    } else {
        headersOf(
            HttpHeaders.ContentType to listOf("application/json"),
            HttpHeaders.RetryAfter to listOf(retryAfter.toString()),
        )
    }
    return respond(protocolJson.encodeToString(ApiError.serializer(), error), status, headers)
}
