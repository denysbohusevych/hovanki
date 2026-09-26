package app.hovanki.server.debug

import app.hovanki.shared.debug.DebugEmails
import app.hovanki.shared.debug.DebugGameList
import app.hovanki.shared.debug.DebugGameState
import app.hovanki.shared.debug.DebugReportList
import app.hovanki.shared.debug.DebugRoutes
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.AccountRules
import app.hovanki.shared.rules.shrinkingZone
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val park = GeoPoint(50.4501, 30.5234)

/** A normal server (no `e2e` profile) must not expose the observer: it would leak every position. */
@SpringBootTest
@AutoConfigureMockMvc
class DebugEndpointAbsentTest(@Autowired private val mvc: MockMvc, @Autowired private val context: ApplicationContext) {
    @Test
    fun noObserverWithoutTheE2eProfile() {
        assertTrue(context.getBeansOfType(DebugController::class.java).isEmpty())
        val host = createGame(mvc)
        val email = register(mvc)

        val paths = listOf(
            DebugRoutes.GAMES,
            DebugRoutes.game(host.session.gameId),
            DebugRoutes.emails(email),
            DebugRoutes.REPORTS,
        )
        for (path in paths) {
            val body = mvc.get(path).andExpect { status { isNotFound() } }.andReturn().response.contentAsString
            assertEquals(ErrorCode.NOT_FOUND, protocolJson.decodeFromString<ApiError>(body).code, body)
        }
    }
}

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(DebugController.PROFILE)
class DebugEndpointTest(@Autowired private val mvc: MockMvc, @Autowired private val jdbc: JdbcClient) {
    @Test
    fun observerSeesEverything() {
        val host = createGame(mvc)
        val fix = LocationSample(park, accuracyMeters = 5.0, timestampMillis = System.currentTimeMillis())
        mvc.post(ApiRoutes.sync(host.session.gameId)) {
            contentType = MediaType.APPLICATION_JSON
            content = protocolJson.encodeToString(SyncRequest(listOf(fix)))
            header("Authorization", "${ApiRoutes.AUTH_SCHEME} ${host.session.token}")
        }.andExpect { status { isOk() } }

        val list = protocolJson.decodeFromString<DebugGameList>(getOk(DebugRoutes.GAMES))
        assertTrue(list.games.any { it.gameId == host.session.gameId && it.joinCode == host.snapshot.joinCode })

        val state = protocolJson.decodeFromString<DebugGameState>(getOk(DebugRoutes.game(host.session.gameId)))
        val player = state.players.single()
        assertEquals("Host", player.name)
        assertEquals(fix.point, assertNotNull(player.latestFix).point)
        assertEquals(1, player.fixes.accepted)
    }

    @Test
    fun emailsWithTheirCodes() {
        val email = register(mvc)
        // Sent after the commit, off the request thread.
        var emails = DebugEmails()
        val deadline = System.currentTimeMillis() + 5000
        while (emails.emails.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            emails = protocolJson.decodeFromString(getOk(DebugRoutes.emails(email.uppercase())))
        }
        val sent = emails.emails.single()
        assertEquals(email, sent.to)
        assertEquals("VERIFY_EMAIL", sent.purpose)
        val code = assertNotNull(sent.code)
        assertTrue(AccountRules.isCodeFormat(code) && code in sent.text, sent.text)

        val nobody = protocolJson.decodeFromString<DebugEmails>(getOk(DebugRoutes.emails("nobody@example.com")))
        assertTrue(nobody.emails.isEmpty())
    }

    @Test
    fun reports() {
        val gameId = "game-${System.nanoTime()}"
        jdbc.sql(
            """
            INSERT INTO reports (game_id, message_seq, reporter_player_id, reporter_user_id, reported_user_id,
                                 reported_name, text, created_at)
            VALUES (:game, 7, 'reporter', NULL, 'reported', 'Mallory', 'rude words', now())
            """.trimIndent(),
        ).param("game", gameId).update()

        val list = protocolJson.decodeFromString<DebugReportList>(getOk(DebugRoutes.REPORTS))
        val report = list.reports.single { it.gameId == GameId(gameId) }
        assertEquals(7, report.messageSeq)
        assertEquals(PlayerId("reporter"), report.reporterPlayerId)
        assertNull(report.reporterUserId)
        assertEquals(UserId("reported"), report.reportedUserId)
        assertEquals("Mallory", report.reportedName)
        assertEquals("rude words", report.text)
    }

    @Test
    fun unknownGame() {
        mvc.get(DebugRoutes.game(GameId("nope"))).andExpect { status { isNotFound() } }
    }

    private fun getOk(path: String): String =
        mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString
}

/** A new account; returns its email. */
private fun register(mvc: MockMvc): String {
    val nickname = "debug${System.nanoTime() % 1_000_000_000}"
    val email = "$nickname@example.com"
    mvc.post(ApiRoutes.ACCOUNTS) {
        contentType = MediaType.APPLICATION_JSON
        content = protocolJson.encodeToString(RegisterRequest(nickname, email, "correct horse battery"))
    }.andExpect { status { isOk() } }
    return email
}

private fun createGame(mvc: MockMvc): SessionResponse {
    val request = CreateGameRequest("Host", GameSettings(zone = shrinkingZone(park)))
    val body = mvc.post(ApiRoutes.GAMES) {
        contentType = MediaType.APPLICATION_JSON
        content = protocolJson.encodeToString(request)
    }.andExpect { status { isOk() } }.andReturn().response.contentAsString
    return protocolJson.decodeFromString(body)
}
