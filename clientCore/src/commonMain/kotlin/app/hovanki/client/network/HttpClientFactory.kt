package app.hovanki.client.network

import app.hovanki.shared.protocol.protocolJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.serialization.kotlinx.json.json

/**
 * The engine comes from the platform module (OkHttp on Android, Darwin on iOS) or a MockEngine in tests.
 * [logRequests] = false keeps the output of many headless e2e bots readable.
 */
fun createHttpClient(engine: HttpClientEngine, logRequests: Boolean = true): HttpClient = HttpClient(engine) {
    // Non-2xx responses are turned into ApiException by HttpGameApi, with the server's ApiError body.
    expectSuccess = false
    install(ContentNegotiation) {
        json(protocolJson)
    }
    install(HttpTimeout) {
        // Mobile networks stall; a hung request would block the poll loop, so give up and retry instead.
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 15_000
        socketTimeoutMillis = 15_000
    }
    if (logRequests) {
        install(Logging) {
            logger = Logger.SIMPLE
            // Method, URL and status only: headers would leak the session token into the logs.
            level = LogLevel.INFO
        }
    }
}
