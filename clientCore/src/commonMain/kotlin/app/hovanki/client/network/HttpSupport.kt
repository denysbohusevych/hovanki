package app.hovanki.client.network

import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.protocolJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * What the HTTP APIs ([HttpGameApi], [HttpAccountApi], [HttpSocialApi]) share: absolute URLs from [serverUrl], the
 * token as `Authorization: Bearer` (a game or an account token, depending on the route), JSON bodies, and non-2xx
 * responses turned into [ApiException] with the server's [ApiError] body.
 */
internal class HttpSupport(private val client: HttpClient, private val serverUrl: ServerUrl) {
    /** GET [path]; the response body as [T]. */
    suspend inline fun <reified T> get(path: String, token: String?): T = send(HttpMethod.Get, path, token) {}.read()

    /** POST [path] without a body; the response body as [T] (`Unit` for 204 No Content). */
    suspend inline fun <reified T> post(path: String, token: String?): T = send(HttpMethod.Post, path, token) {}.read()

    /** POST [path] with [body] as JSON; the response body as [T] (`Unit` for 204 No Content). */
    suspend inline fun <reified B, reified T> post(path: String, token: String?, body: B): T =
        send(HttpMethod.Post, path, token) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.read()

    /** Sends the request; returns the response only if it is a 2xx, else throws [ApiException]. */
    suspend fun send(
        method: HttpMethod,
        path: String,
        token: String?,
        configure: HttpRequestBuilder.() -> Unit,
    ): HttpResponse {
        val response = client.request(serverUrl.value + path) {
            this.method = method
            if (token != null) header(HttpHeaders.Authorization, "${ApiRoutes.AUTH_SCHEME} $token")
            configure()
        }
        if (!response.status.isSuccess()) throw response.toApiException()
        return response
    }

    suspend inline fun <reified T> HttpResponse.read(): T = if (T::class == Unit::class) Unit as T else body()

    private suspend fun HttpResponse.toApiException(): ApiException {
        val error = try {
            protocolJson.decodeFromString(ApiError.serializer(), bodyAsText())
        } catch (e: IllegalArgumentException) {
            // Not our JSON (proxy error page, empty body): the status alone has to do.
            null
        }
        // Rate limits (429) say when to try again, in seconds.
        val retryAfter = headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        return ApiException(status.value, error, retryAfter)
    }
}
