package app.hovanki.client.network

import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.GameHistoryResponse
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameRoute
import app.hovanki.shared.protocol.PlayerStats
import io.ktor.client.HttpClient

/**
 * The logged-in player's own history (docs/adr/0007-game-history-and-routes.md): statistics, games and saved routes.
 * Every call takes the account token and is about its owner only. Saving routes is turned on and off with
 * [AccountApi.setSaveRoutes].
 *
 * Throws [ApiException] when the server rejects a call, and I/O or serialization exceptions on network problems.
 */
interface HistoryApi {
    suspend fun stats(token: String): PlayerStats

    /** Newest first; [before] (from [GameHistoryResponse.nextBefore], a `finishedAtMillis`) for the next page. */
    suspend fun games(token: String, before: Long? = null): GameHistoryResponse

    /** The saved route of [gameId]; an [ApiException] with 404 when none is saved. */
    suspend fun route(token: String, gameId: GameId): GameRoute

    /** Deletes the saved route of [gameId]; the game stays in the history. */
    suspend fun deleteRoute(token: String, gameId: GameId)
}

/** [HistoryApi] over HTTP/JSON. */
class HttpHistoryApi(client: HttpClient, serverUrl: ServerUrl) : HistoryApi {
    private val http = HttpSupport(client, serverUrl)

    override suspend fun stats(token: String): PlayerStats = http.get(ApiRoutes.ME_STATS, token)

    override suspend fun games(token: String, before: Long?): GameHistoryResponse =
        http.get(ApiRoutes.meGames(before), token)

    override suspend fun route(token: String, gameId: GameId): GameRoute =
        http.get(ApiRoutes.meGameRoute(gameId), token)

    override suspend fun deleteRoute(token: String, gameId: GameId) {
        http.post<Unit>(ApiRoutes.meGameRouteDelete(gameId), token)
    }
}
