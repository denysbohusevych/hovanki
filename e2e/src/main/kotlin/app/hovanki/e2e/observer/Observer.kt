package app.hovanki.e2e.observer

import app.hovanki.client.network.createHttpClient
import app.hovanki.shared.debug.DebugGameList
import app.hovanki.shared.debug.DebugGameState
import app.hovanki.shared.debug.DebugRoutes
import app.hovanki.shared.protocol.GameId
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

/**
 * Reads the full game state from the server's debug endpoint (Spring profile `e2e`): the ground truth
 * that scenarios check the players' views against.
 */
class Observer(serverUrl: String) : AutoCloseable {
    private val baseUrl = serverUrl.trimEnd('/')
    private val client = createHttpClient(OkHttp.create(), logRequests = false)

    suspend fun games(): DebugGameList = get(DebugRoutes.GAMES)

    suspend fun game(id: GameId): DebugGameState = get(DebugRoutes.game(id))

    private suspend inline fun <reified T> get(path: String): T {
        val response = client.get(baseUrl + path)
        if (response.status == HttpStatusCode.NotFound && path == DebugRoutes.GAMES) {
            error("$baseUrl has no observer endpoint: start the server with the Spring profile 'e2e'")
        }
        check(response.status.isSuccess()) { "Observer GET $path: HTTP ${response.status}" }
        return response.body()
    }

    override fun close() = client.close()
}
