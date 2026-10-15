package app.hovanki.client.network.live

import app.hovanki.client.network.HttpSupport
import app.hovanki.client.network.ServerUrl
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.LiveOpenRequest
import app.hovanki.shared.protocol.LiveOpenResponse
import app.hovanki.shared.protocol.PlayerSession
import io.ktor.client.HttpClient

/** Opens a player's live session (docs/live-v2/spec.md §4): HTTPS and JSON, like every command. */
interface LiveApi {
    /** Throws [app.hovanki.client.network.ApiException] for the server's refusals (401, 404, 429). */
    suspend fun open(session: PlayerSession, request: LiveOpenRequest): LiveOpenResponse
}

class HttpLiveApi(client: HttpClient, serverUrl: ServerUrl) : LiveApi {
    private val http = HttpSupport(client, serverUrl)

    override suspend fun open(session: PlayerSession, request: LiveOpenRequest): LiveOpenResponse =
        http.post(ApiRoutes.LIVE_OPEN, session.token, request)
}
