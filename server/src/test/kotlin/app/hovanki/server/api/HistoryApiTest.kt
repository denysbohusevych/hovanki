package app.hovanki.server.api

import app.hovanki.server.MutableClock
import app.hovanki.server.account.AccountTestConfig
import app.hovanki.server.account.uniqueName
import app.hovanki.server.history.HistoryWriter
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.DeleteAccountRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameHistoryResponse
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameRecording
import app.hovanki.shared.protocol.GameRoute
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.PlayerStats
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PrivacyRequest
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.shrinkingZone
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Game history, statistics and saved routes over HTTP (docs/adr/0007-game-history-and-routes.md): what is kept after a
 * game, only with consent for routes, and only ever for the player themselves.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountTestConfig::class)
class HistoryApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired private val writer: HistoryWriter,
    @Autowired private val jdbc: JdbcClient,
) {
    private val park = GeoPoint(50.4501, 30.5234)
    private val settings = GameSettings(zone = shrinkingZone(park), hidingSeconds = 30, seekingSeconds = 120)

    @Test
    fun afterAGameEachPlayerSeesTheirOwnHistoryAndOnlyTheConsentingOneTheirRoute() {
        val alice = register()
        val bob = register()
        assertFalse(alice.user.saveRoutes, "off by default")
        val profile = setSaveRoutes(alice, true).ok<UserProfile>()
        assertTrue(profile.saveRoutes)
        assertEquals(profile, get(ApiRoutes.ME, alice.token).ok<UserProfile>())

        val game = play(host = alice, seeker = bob)

        val aliceGames = get(ApiRoutes.ME_GAMES, alice.token).ok<GameHistoryResponse>()
        val aliceEntry = aliceGames.games.single()
        assertNull(aliceGames.nextBefore)
        assertEquals(game, aliceEntry.gameId)
        assertEquals(Role.HIDER, aliceEntry.role)
        assertEquals(PlayerStatus.ACTIVE, aliceEntry.status)
        assertTrue(aliceEntry.won)
        assertEquals(3, aliceEntry.players)
        assertEquals(settings.seekingSeconds, aliceEntry.survivedSeconds)
        assertTrue(aliceEntry.distanceMeters in 80.0..130.0, "${aliceEntry.distanceMeters} m")
        assertTrue(aliceEntry.hasRoute)

        val bobEntry = get(ApiRoutes.ME_GAMES, bob.token).ok<GameHistoryResponse>().games.single()
        assertEquals(Role.SEEKER, bobEntry.role)
        assertFalse(bobEntry.won)
        assertFalse(bobEntry.hasRoute, "bob never agreed to keep routes")

        val route = get(ApiRoutes.meGameRoute(game), alice.token).ok<GameRoute>()
        assertEquals(Role.HIDER, route.role)
        assertEquals(settings.zone, route.zone)
        assertTrue(route.points.size >= 10, "${route.points.size} points")
        // Alice's own walk east of the park, and nobody else's.
        assertTrue(route.points.all { it.lon >= park.lon }, "${route.points}")
        val saved = route.expiresAtMillis - Duration.ofDays(90).toMillis()
        assertTrue(saved >= route.finishedAtMillis, "kept 90 days from saving")

        // Bob has no route of this game, and can't get alice's: there is no way to ask for someone else's.
        get(ApiRoutes.meGameRoute(game), bob.token).error(404, ErrorCode.NOT_FOUND)
        get(ApiRoutes.meGameRoute(game), token = null).error(401, ErrorCode.UNAUTHORIZED)

        val stats = get(ApiRoutes.ME_STATS, alice.token).ok<PlayerStats>()
        assertEquals(1, stats.games)
        assertEquals(1, stats.gamesAsHider)
        assertEquals(1, stats.winsAsHider)
        assertEquals(aliceEntry.distanceMeters, stats.distanceMeters, 0.001)
        assertEquals(settings.seekingSeconds, stats.longestHideSeconds)
        assertEquals((settings.hidingSeconds + settings.seekingSeconds).toLong(), stats.playedSeconds)
        assertNotNull(stats.averageSpeedMetersPerSecond)
        val bobStats = get(ApiRoutes.ME_STATS, bob.token).ok<PlayerStats>()
        assertEquals(1, bobStats.gamesAsSeeker)
        assertEquals(0, bobStats.wins)
    }

    @Test
    fun turningSavingOffDeletesTheRoutesAndKeepsTheHistory() {
        val alice = register()
        val bob = register()
        setSaveRoutes(alice, true).ok<UserProfile>()
        val game = play(host = alice, seeker = bob)
        get(ApiRoutes.meGameRoute(game), alice.token).ok<GameRoute>()

        assertFalse(setSaveRoutes(alice, false).ok<UserProfile>().saveRoutes)

        get(ApiRoutes.meGameRoute(game), alice.token).error(404, ErrorCode.NOT_FOUND)
        val entry = get(ApiRoutes.ME_GAMES, alice.token).ok<GameHistoryResponse>().games.single()
        assertFalse(entry.hasRoute)
        assertEquals(1, get(ApiRoutes.ME_STATS, alice.token).ok<PlayerStats>().games)
    }

    @Test
    fun turnedOnOnTheResultsScreenTheGameJustPlayedIsKeptToo() {
        val alice = register()
        val bob = register()
        val game = play(host = alice, seeker = bob)
        get(ApiRoutes.meGameRoute(game), alice.token).error(404, ErrorCode.NOT_FOUND)

        // The game is still in memory (the results screen): its route is saved now.
        setSaveRoutes(alice, true).ok<UserProfile>()
        writer.awaitIdle()

        assertTrue(get(ApiRoutes.meGameRoute(game), alice.token).ok<GameRoute>().points.isNotEmpty())
        get(ApiRoutes.meGameRoute(game), bob.token).error(404, ErrorCode.NOT_FOUND)
    }

    @Test
    fun oneRouteCanBeDeleted() {
        val alice = register()
        val bob = register()
        setSaveRoutes(alice, true).ok<UserProfile>()
        val game = play(host = alice, seeker = bob)

        post(ApiRoutes.meGameRouteDelete(game), json = null, alice.token).expect(204)
        // Again: nothing left, still fine.
        post(ApiRoutes.meGameRouteDelete(game), json = null, alice.token).expect(204)

        get(ApiRoutes.meGameRoute(game), alice.token).error(404, ErrorCode.NOT_FOUND)
        assertFalse(get(ApiRoutes.ME_GAMES, alice.token).ok<GameHistoryResponse>().games.single().hasRoute)
        assertTrue(get(ApiRoutes.ME, alice.token).ok<UserProfile>().saveRoutes)
    }

    @Test
    fun guestsLeaveNoHistoryAndTheGameIsCountedOnce() {
        val alice = register()
        val created = createGame(alice)
        val guest = join(created.snapshot.joinCode, token = null)
        val game = created.session.gameId
        start(created.session, guest.session)
        finish(created.session, guest.session)

        assertEquals(1, get(ApiRoutes.ME_GAMES, alice.token).ok<GameHistoryResponse>().games.size)
        val rows = jdbc.sql("SELECT players, guests FROM played_games WHERE id = :id")
            .param("id", game.value)
            .query { rs, _ -> rs.getInt("players") to rs.getInt("guests") }
            .list()
        assertEquals(listOf(2 to 1), rows)
        val results = jdbc.sql("SELECT count(*) FROM game_results WHERE game_id = :id")
            .param("id", game.value)
            .query(Int::class.java)
            .single()
        assertEquals(1, results)
    }

    @Test
    fun theHistoryGoesWithTheAccount() {
        val alice = register()
        val bob = register()
        setSaveRoutes(alice, true).ok<UserProfile>()
        val game = play(host = alice, seeker = bob)

        post(ApiRoutes.ME_DELETE, DeleteAccountRequest(PASSWORD).toJson(), alice.token).expect(204)

        for (table in listOf("game_results", "game_routes")) {
            val left = jdbc.sql("SELECT count(*) FROM $table WHERE user_id = :id")
                .param("id", alice.user.id.value)
                .query(Int::class.java)
                .single()
            assertEquals(0, left, table)
        }
        assertEquals(1, get(ApiRoutes.ME_GAMES, bob.token).ok<GameHistoryResponse>().games.size)
        assertEquals(game, get(ApiRoutes.ME_GAMES, bob.token).ok<GameHistoryResponse>().games.single().gameId)
    }

    @Test
    fun theGamesPlayersWatchItsRecordingWithEverybodysWay() {
        val alice = register()
        val bob = register()
        val stranger = register()
        val game = play(host = alice, seeker = bob)

        val entry = get(ApiRoutes.ME_GAMES, bob.token).ok<GameHistoryResponse>().games.single()
        assertTrue(entry.hasRecording)
        assertFalse(entry.hasRoute, "the recording is not bob's own saved route")

        val recording = get(ApiRoutes.meGameRecording(game), bob.token).ok<GameRecording>()
        assertEquals(game, recording.gameId)
        assertEquals(settings.zone, recording.zone)
        assertEquals(3, recording.players.size, "the guest too")
        val me = recording.players.single { it.isMe }
        assertEquals(Role.SEEKER, me.role)
        assertTrue(me.points.isNotEmpty())
        // Alice's walk east of the park: bob sees how she went.
        val hider = recording.players.single { it.role == Role.HIDER }
        assertTrue(hider.points.size >= 10, "${hider.points.size} points")
        assertTrue(hider.points.all { it.lon >= park.lon })
        assertTrue(recording.expiresAtMillis - Duration.ofDays(90).toMillis() >= recording.finishedAtMillis)
        assertEquals(1, recording.players.count { it.isMe })
        assertEquals(1, get(ApiRoutes.meGameRecording(game), alice.token).ok<GameRecording>().players.count { it.isMe })

        // Only for those who played it.
        get(ApiRoutes.meGameRecording(game), stranger.token).error(404, ErrorCode.NOT_FOUND)
        get(ApiRoutes.meGameRecording(game), token = null).error(401, ErrorCode.UNAUTHORIZED)
    }

    @Test
    fun aDeletedAccountTakesItsWayOutOfTheRecording() {
        val alice = register()
        val bob = register()
        val game = play(host = alice, seeker = bob)

        post(ApiRoutes.ME_DELETE, DeleteAccountRequest(PASSWORD).toJson(), alice.token).expect(204)

        val recording = get(ApiRoutes.meGameRecording(game), bob.token).ok<GameRecording>()
        assertTrue(recording.players.none { it.role == Role.HIDER }, "alice's way went with her account")
        assertEquals(2, recording.players.size, "bob and the guest")
    }

    @Test
    fun pages() {
        val alice = register()
        val bob = register()
        repeat(21) { play(host = alice, seeker = bob, walk = false) }

        val first = get(ApiRoutes.meGames(), alice.token).ok<GameHistoryResponse>()
        assertEquals(20, first.games.size)
        assertEquals(first.games.sortedByDescending { it.finishedAtMillis }, first.games)
        val before = assertNotNull(first.nextBefore)
        val second = get(ApiRoutes.meGames(before), alice.token).ok<GameHistoryResponse>()
        assertEquals(1, second.games.size)
        assertNull(second.nextBefore)
        assertTrue(second.games.single().finishedAtMillis < before)
    }

    /**
     * [host] hides and walks east of the park (unless not [walk]), [seeker] seeks and stays, a guest seeks too; the
     * time runs out, nobody is found. Returns the game, its history saved.
     */
    private fun play(host: AccountSession, seeker: AccountSession, walk: Boolean = true): GameId {
        val created = createGame(host)
        val hider = created.session
        val seekerSession = join(created.snapshot.joinCode, seeker.token).session
        val guest = join(created.snapshot.joinCode, token = null).session
        start(hider, seekerSession, guest)
        if (walk) {
            // 1.4 m/s east for 70 s, a fix every 5 s.
            for (step in 0..14) {
                sync(hider, park.moveBy(step * 7.0, 0.0))
                sync(seekerSession, park)
                clock.advance(Duration.ofSeconds(5))
            }
        }
        finish(hider, seekerSession)
        return hider.gameId
    }

    private fun start(host: PlayerSession, vararg seekers: PlayerSession) {
        val request = StartGameRequest(seekers.map { it.playerId })
        post(ApiRoutes.start(host.gameId), request.toJson(), host.token).expect(200)
    }

    /** Lets the time run out and waits for the history to be saved. */
    private fun finish(host: PlayerSession, other: PlayerSession) {
        clock.advance(Duration.ofSeconds((settings.hidingSeconds + settings.seekingSeconds).toLong()))
        assertEquals(GamePhase.FINISHED, sync(host, park.moveBy(-500.0, 0.0)).phase)
        sync(other, park)
        writer.awaitIdle()
    }

    private fun createGame(user: AccountSession): SessionResponse =
        post(ApiRoutes.GAMES, CreateGameRequest("", settings).toJson(), user.token).ok()

    private fun join(joinCode: String, token: String?): SessionResponse =
        post(ApiRoutes.JOIN, JoinGameRequest(joinCode, "Guest").toJson(), token).ok()

    private fun sync(session: PlayerSession, point: GeoPoint): GameSnapshot {
        val fix = LocationSample(point, accuracyMeters = 5.0, timestampMillis = clock.millis())
        return post(ApiRoutes.sync(session.gameId), SyncRequest(listOf(fix)).toJson(), session.token).ok()
    }

    private fun setSaveRoutes(user: AccountSession, on: Boolean) =
        post(ApiRoutes.ME_PRIVACY, PrivacyRequest(on).toJson(), user.token)

    private fun register(): AccountSession {
        val nickname = uniqueName("hist")
        return post(ApiRoutes.ACCOUNTS, RegisterRequest(nickname, "$nickname@example.com", PASSWORD).toJson()).ok()
    }

    private data class Response(val status: Int, val body: String) {
        fun expect(status: Int) = also { assertEquals(status, this.status, body) }

        inline fun <reified T> ok(): T = protocolJson.decodeFromString(expect(200).body)

        fun error(status: Int, code: ErrorCode) {
            assertEquals(code, protocolJson.decodeFromString<ApiError>(expect(status).body).code, body)
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
