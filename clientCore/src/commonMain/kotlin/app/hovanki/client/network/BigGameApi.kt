package app.hovanki.client.network

import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.BigGameCard
import app.hovanki.shared.protocol.BigGameId
import app.hovanki.shared.protocol.BigGamesResponse
import io.ktor.client.HttpClient

/**
 * Big games (docs/adr/0010-big-games.md): the list and the sign-ups, with the account token. Coming into a big game's
 * lobby is [GameApi.joinBigGame]: it starts a game session.
 *
 * Throws [ApiException] when the server rejects a call, and I/O or serialization exceptions on network problems.
 */
interface BigGameApi {
    /** Ahead and going on, soonest first. */
    suspend fun list(token: String): BigGamesResponse

    suspend fun signUp(token: String, id: BigGameId): BigGameCard

    suspend fun cancelSignup(token: String, id: BigGameId): BigGameCard
}

/** [BigGameApi] over HTTP/JSON. */
class HttpBigGameApi(client: HttpClient, serverUrl: ServerUrl) : BigGameApi {
    private val http = HttpSupport(client, serverUrl)

    override suspend fun list(token: String): BigGamesResponse = http.get(ApiRoutes.BIG_GAMES, token)

    override suspend fun signUp(token: String, id: BigGameId): BigGameCard =
        http.post(ApiRoutes.bigGameSignup(id), token)

    override suspend fun cancelSignup(token: String, id: BigGameId): BigGameCard =
        http.post(ApiRoutes.bigGameSignupCancel(id), token)
}
