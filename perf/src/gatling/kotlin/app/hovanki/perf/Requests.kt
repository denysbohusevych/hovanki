package app.hovanki.perf

import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.protocolJson
import io.gatling.javaapi.core.ChainBuilder
import io.gatling.javaapi.core.CoreDsl.StringBody
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.jsonPath
import io.gatling.javaapi.core.Session
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import io.gatling.javaapi.http.HttpProtocolBuilder
import io.gatling.javaapi.http.HttpRequestActionBuilder

/** What a virtual player keeps in its Gatling session. */
object Keys {
    const val GAME_ID = "gameId"
    const val PLAYER_ID = "playerId"
    const val TOKEN = "token"
    const val ACCOUNT_TOKEN = "accountToken"
    const val PHASE = "phase"
    const val WALKER = "walker"
}

/** The protocol's JSON, as the app sends it: the same DTOs and the same [protocolJson] as the app and the server. */
inline fun <reified T> json(value: T): String = protocolJson.encodeToString(value)

/** Every player is a phone of its own: its own connections, gzip as OkHttp and NSURLSession ask for it. */
fun hovankiHttp(): HttpProtocolBuilder = http.baseUrl(LabConfig.baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .acceptEncodingHeader("gzip")
    .userAgentHeader("hovanki-perf")

/** The player's game token, as the app sends it with every game request. */
fun HttpRequestActionBuilder.asPlayer(): HttpRequestActionBuilder =
    header("Authorization") { session -> "${ApiRoutes.AUTH_SCHEME} ${session.getString(Keys.TOKEN)}" }

/** The player's account token (accounts, big games). */
fun HttpRequestActionBuilder.asAccount(): HttpRequestActionBuilder =
    header("Authorization") { session -> "${ApiRoutes.AUTH_SCHEME} ${session.getString(Keys.ACCOUNT_TOKEN)}" }

fun Session.gameId(): GameId = GameId(checkNotNull(getString(Keys.GAME_ID)) { "No game in the session" })

fun Session.phase(): GamePhase = getString(Keys.PHASE)?.let(GamePhase::valueOf) ?: GamePhase.LOBBY

/**
 * One sync, as the app sends it every `GameRules.syncIntervalSeconds`: the fix since the last one and the chat cursor.
 * Named by the phase the player was in (`sync LOBBY`, `sync SEEKING`): the snapshots differ, and so do their times.
 */
fun sync(): ChainBuilder = exec(
    http { session -> "sync ${session.phase()}" }
        .post { session -> ApiRoutes.sync(session.gameId()) }
        .asPlayer()
        .body(
            StringBody { session ->
                val walker = checkNotNull(session.get<Walker>(Keys.WALKER)) { "No walker in the session" }
                json(SyncRequest(samples = listOf(walker.fix(System.currentTimeMillis())), chatAfter = 0))
            },
        )
        .check(status().shouldBe(200), jsonPath("$.phase").saveAs(Keys.PHASE)),
)
