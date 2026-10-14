package app.hovanki.perf

import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ClientFrame
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.SocketLimits
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.protocolJson
import io.gatling.javaapi.core.ChainBuilder
import io.gatling.javaapi.core.CoreDsl.StringBody
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.jsonPath
import io.gatling.javaapi.core.Session
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import io.gatling.javaapi.http.HttpDsl.ws
import io.gatling.javaapi.http.HttpProtocolBuilder
import io.gatling.javaapi.http.HttpRequestActionBuilder
import java.time.Duration

/** What a virtual player keeps in its Gatling session. */
object Keys {
    const val GAME_ID = "gameId"
    const val PLAYER_ID = "playerId"
    const val TOKEN = "token"
    const val ACCOUNT_TOKEN = "accountToken"
    const val PHASE = "phase"
    const val WALKER = "walker"

    /** The number of the player's last sync over the live channel: its answer carries it back. */
    const val SEQ = "seq"
}

/** The protocol's JSON, as the app sends it: the same DTOs and the same [protocolJson] as the app and the server. */
inline fun <reified T> json(value: T): String = protocolJson.encodeToString(value)

/**
 * Every player is a phone: gzip as OkHttp and NSURLSession ask for it, the connections as production has them. Through
 * Caddy (an `https` [LabConfig.baseUrls]) every phone keeps its own connection, as on production. Straight to the server
 * the players share a pool of a few connections, as Caddy keeps to it: one connection per player would stop at Tomcat's
 * 8 192 (`server.tomcat.max-connections`) and hold 16 KB of its buffers each, a ceiling and a heap production doesn't
 * have. A live channel's socket is one per player either way.
 */
fun hovankiHttp(): HttpProtocolBuilder = http.baseUrls(LabConfig.baseUrls)
    .wsBaseUrls(LabConfig.baseUrls.map { it.replaceFirst("http", "ws") })
    .let { if (LabConfig.throughCaddy) it else it.shareConnections() }
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
 * The player's live channel once it is in a game (`-Pperf.transport=socket`), as the app opens it; nothing for polling.
 * The upgrade is never refused: a bad token or the feature off closes the socket at once, and the first sync fails.
 */
fun connect(): ChainBuilder = when (LabConfig.transport) {
    Transport.POLL -> exec { session -> session }

    Transport.SOCKET -> exec { session -> session.set(Keys.SEQ, 0L) }.exec(
        ws("socket: open").connect { session -> ApiRoutes.socket(session.gameId()) }
            .header("Authorization") { session -> "${ApiRoutes.AUTH_SCHEME} ${session.getString(Keys.TOKEN)}" },
    )
}

/** Closes the player's live channel at the end of the run; nothing for polling. */
fun disconnect(): ChainBuilder = when (LabConfig.transport) {
    Transport.POLL -> exec { session -> session }
    Transport.SOCKET -> exec(ws("socket: close").close())
}

/**
 * One sync, as the app sends it every `GameRules.syncIntervalSeconds`: the fix since the last one and the chat cursor,
 * as `POST /sync` or as a frame of the live channel ([LabConfig.transport]). Named by the phase the player was in
 * (`sync LOBBY`, `sync SEEKING`): the snapshots differ, and so do their times.
 */
fun sync(): ChainBuilder = when (LabConfig.transport) {
    Transport.POLL -> pollSync()
    Transport.SOCKET -> socketSync()
}

private fun pollSync(): ChainBuilder = exec(
    http { session -> "sync ${session.phase()}" }
        .post { session -> ApiRoutes.sync(session.gameId()) }
        .asPlayer()
        .body(StringBody { session -> json(syncRequest(session)) })
        .check(status().shouldBe(200), jsonPath("$.phase").saveAs(Keys.PHASE)),
)

/**
 * The sync as a frame. Gatling reports the frame sent as `socket: send` (no time of its own) and its answer under the
 * same name as a polled sync, from the frame sent to the answer. The server's pokes ("something happened, sync now")
 * are left unanswered: the player syncs at its own pace, so both transports carry the same syncs.
 */
private fun socketSync(): ChainBuilder = exec { session -> session.set(Keys.SEQ, session.getLong(Keys.SEQ) + 1) }
    .exec(
        ws("socket: send")
            .sendText { session ->
                json<ClientFrame>(ClientFrame.Sync(session.getLong(Keys.SEQ), syncRequest(session)))
            }
            .await(Duration.ofMillis(SocketLimits.REPLY_TIMEOUT_MILLIS)).on(
                ws.checkTextMessage { session -> "sync ${session.phase()}" }
                    // The answer to this sync (a snapshot, or an error): a poke has no seq.
                    .matching(jsonPath("$.seq").ofLong().shouldBe { session -> session.getLong(Keys.SEQ) })
                    .check(
                        jsonPath("$.type").shouldBe("snapshot"),
                        jsonPath("$.snapshot.phase").saveAs(Keys.PHASE),
                    ),
            ),
    )

private fun syncRequest(session: Session): SyncRequest {
    val walker = checkNotNull(session.get<Walker>(Keys.WALKER)) { "No walker in the session" }
    return SyncRequest(samples = listOf(walker.fix(System.currentTimeMillis())), chatAfter = 0)
}
