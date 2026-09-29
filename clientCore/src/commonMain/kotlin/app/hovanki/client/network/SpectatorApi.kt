package app.hovanki.client.network

import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.SpectatorSession
import app.hovanki.shared.protocol.SpectatorSnapshot
import app.hovanki.shared.protocol.StreetZoneResponse
import app.hovanki.shared.protocol.WatchRequest
import app.hovanki.shared.protocol.WatchResponse
import io.ktor.client.HttpClient

/**
 * Watching an open game without playing it (docs/adr/0011-spectators-and-recordings.md): [watch] takes the account
 * token, the other calls the spectator token it returns.
 *
 * Throws [ApiException] when the server rejects a call, and I/O or serialization exceptions on network problems.
 */
interface SpectatorApi {
    suspend fun watch(accountToken: String, request: WatchRequest): WatchResponse

    /** The game the game's delay behind; polled every few seconds while watching. */
    suspend fun spectate(session: SpectatorSession): SpectatorSnapshot

    /** The zone by streets, once per map revision, when the snapshot says it is ready. */
    suspend fun streetZone(session: SpectatorSession): StreetZoneResponse

    /** Stops watching: the spectator token stops working. */
    suspend fun leave(session: SpectatorSession)
}

/** [SpectatorApi] over HTTP/JSON. */
class HttpSpectatorApi(client: HttpClient, serverUrl: ServerUrl) : SpectatorApi {
    private val http = HttpSupport(client, serverUrl)

    override suspend fun watch(accountToken: String, request: WatchRequest): WatchResponse =
        http.post(ApiRoutes.WATCH, accountToken, request)

    override suspend fun spectate(session: SpectatorSession): SpectatorSnapshot =
        http.get(ApiRoutes.spectate(session.gameId), session.token)

    override suspend fun streetZone(session: SpectatorSession): StreetZoneResponse =
        http.get(ApiRoutes.spectateStreetZone(session.gameId), session.token)

    override suspend fun leave(session: SpectatorSession) {
        http.post<Unit>(ApiRoutes.spectateLeave(session.gameId), session.token)
    }
}
