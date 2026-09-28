package app.hovanki.server.api

import app.hovanki.server.MutableClock
import app.hovanki.server.account.AccountTestConfig
import app.hovanki.server.account.uniqueName
import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.SettingsRequest
import app.hovanki.shared.protocol.SpectatorSnapshot
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.WatchRequest
import app.hovanki.shared.protocol.WatchResponse
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.shrinkingZone
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Watching an open game over HTTP (docs/adr/0011-spectators-and-recordings.md): by code, with an account. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountTestConfig::class)
class SpectatorApiTest(@Autowired private val mvc: MockMvc, @Autowired private val clock: MutableClock) {
    private val park = GeoPoint(50.4501, 30.5234)
    private val settings = GameSettings(
        zone = shrinkingZone(park),
        hidingSeconds = 30,
        seekingSeconds = 120,
        openGame = true,
        spectatorDelaySeconds = 30,
    )

    @Test
    fun anAccountWatchesAnOpenGameByItsCodeTheDelayBehind() {
        val host = register()
        val fan = register()
        val created = createGame(host, settings)
        val seeker = join(created.snapshot.joinCode, token = null).session
        val code = created.snapshot.joinCode

        // Guests don't watch: an account is needed.
        post(ApiRoutes.WATCH, WatchRequest(code).toJson(), token = null).error(401, ErrorCode.UNAUTHORIZED)
        post(ApiRoutes.WATCH, WatchRequest("NOPE12").toJson(), fan.token).error(404, ErrorCode.NOT_FOUND)
        // The host plays: no watching their own game.
        post(ApiRoutes.WATCH, WatchRequest(code).toJson(), host.token)
            .error(403, ErrorCode.FORBIDDEN, ErrorReason.PLAYING_THIS_GAME)

        val watching = post(ApiRoutes.WATCH, WatchRequest(" ${code.lowercase()} ").toJson(), fan.token)
            .ok<WatchResponse>()
        val session = watching.session
        assertEquals(created.session.gameId, session.gameId)
        assertEquals(GamePhase.LOBBY, watching.snapshot.phase)
        assertEquals(2, watching.snapshot.players.size)
        assertEquals(1, sync(created.session, park).spectators, "the players see that somebody watches")

        start(created.session, seeker)
        // A fix newer than the lobby's one.
        clock.advance(Duration.ofSeconds(1))
        sync(created.session, park)
        clock.advance(Duration.ofSeconds(9))
        var view = get(ApiRoutes.spectate(session.gameId), session.token).ok<SpectatorSnapshot>()
        assertEquals(30, view.delaySeconds)
        assertEquals(GamePhase.LOBBY, view.phase, "30 s behind: the round had not started yet")
        assertTrue(view.players.all { it.location == null })
        clock.advance(Duration.ofSeconds(25))
        view = get(ApiRoutes.spectate(session.gameId), session.token).ok()
        assertEquals(GamePhase.HIDING, view.phase)
        assertNotNull(view.players.single { it.id == created.session.playerId }.location)

        // The spectator's token is for watching only; a player's token doesn't watch.
        post(ApiRoutes.sync(session.gameId), SyncRequest(emptyList()).toJson(), session.token)
            .error(401, ErrorCode.UNAUTHORIZED)
        get(ApiRoutes.spectate(session.gameId), created.session.token).error(401, ErrorCode.UNAUTHORIZED)

        post(ApiRoutes.spectateLeave(session.gameId), json = null, session.token).expect(204)
        get(ApiRoutes.spectate(session.gameId), session.token).error(401, ErrorCode.UNAUTHORIZED)
    }

    @Test
    fun closedGamesAreNotWatchedAndClosingOneEndsTheWatching() {
        val host = register()
        val fan = register()
        val closed = createGame(host, settings.copy(openGame = false))
        post(ApiRoutes.WATCH, WatchRequest(closed.snapshot.joinCode).toJson(), fan.token)
            .error(403, ErrorCode.FORBIDDEN, ErrorReason.GAME_NOT_OPEN)
        post(ApiRoutes.leave(closed.session.gameId), json = null, closed.session.token).expect(204)

        val open = createGame(host, settings)
        val session = post(ApiRoutes.WATCH, WatchRequest(open.snapshot.joinCode).toJson(), fan.token)
            .ok<WatchResponse>().session
        val snapshot = post(
            ApiRoutes.settings(open.session.gameId),
            SettingsRequest(settings.copy(openGame = false)).toJson(),
            open.session.token,
        ).ok<GameSnapshot>()
        assertNull(snapshot.spectators.takeIf { it > 0 }, "nobody watches any more")
        get(ApiRoutes.spectate(session.gameId), session.token).error(401, ErrorCode.UNAUTHORIZED)
    }

    private fun createGame(user: AccountSession, settings: GameSettings): SessionResponse =
        post(ApiRoutes.GAMES, CreateGameRequest("", settings).toJson(), user.token).ok()

    private fun join(joinCode: String, token: String?): SessionResponse =
        post(ApiRoutes.JOIN, JoinGameRequest(joinCode, "Guest").toJson(), token).ok()

    private fun start(host: PlayerSession, vararg seekers: PlayerSession) {
        val request = StartGameRequest(seekers.map { it.playerId })
        post(ApiRoutes.start(host.gameId), request.toJson(), host.token).expect(200)
    }

    private fun sync(session: PlayerSession, point: GeoPoint): GameSnapshot {
        val fix = LocationSample(point, accuracyMeters = 5.0, timestampMillis = clock.millis())
        return post(ApiRoutes.sync(session.gameId), SyncRequest(listOf(fix)).toJson(), session.token).ok()
    }

    private fun register(): AccountSession {
        val nickname = uniqueName("fan")
        return post(ApiRoutes.ACCOUNTS, RegisterRequest(nickname, "$nickname@example.com", PASSWORD).toJson()).ok()
    }

    private data class Response(val status: Int, val body: String) {
        fun expect(status: Int) = also { assertEquals(status, this.status, body) }

        inline fun <reified T> ok(): T = protocolJson.decodeFromString(expect(200).body)

        fun error(status: Int, code: ErrorCode, reason: ErrorReason? = null) {
            val error = protocolJson.decodeFromString<ApiError>(expect(status).body)
            assertEquals(code, error.code, body)
            if (reason != null) assertEquals(reason, error.reason, body)
        }
    }

    private inline fun <reified T> T.toJson(): String = protocolJson.encodeToString(this)

    private fun post(path: String, json: String?, token: String? = null): Response {
        val response = mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            if (json != null) content = json
            if (token != null) header("Authorization", "${ApiRoutes.AUTH_SCHEME} $token")
        }.andReturn().response
        return Response(response.status, response.getContentAsString(Charsets.UTF_8))
    }

    private fun get(path: String, token: String?): Response {
        val response = mvc.get(path) {
            if (token != null) header("Authorization", "${ApiRoutes.AUTH_SCHEME} $token")
        }.andReturn().response
        return Response(response.status, response.getContentAsString(Charsets.UTF_8))
    }

    private companion object {
        const val PASSWORD = "correct horse battery"
    }
}
