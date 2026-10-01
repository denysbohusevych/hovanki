package app.hovanki.server.api

import app.hovanki.server.MutableClock
import app.hovanki.server.account.AccountTestConfig
import app.hovanki.server.account.uniqueName
import app.hovanki.server.features.FeatureFlags
import app.hovanki.server.mail.EmailSender
import app.hovanki.server.mail.RecordingEmailSender
import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.AdminLabRun
import app.hovanki.shared.protocol.AdminLabRunRequest
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.FieldJoinRequest
import app.hovanki.shared.protocol.FieldJoinResponse
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.LabEventsResponse
import app.hovanki.shared.protocol.LabJoinRequest
import app.hovanki.shared.protocol.LabJoinResponse
import app.hovanki.shared.protocol.LabUpload
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.UserRole
import app.hovanki.shared.rules.shrinkingZone
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A game's field log takes only so many phones and bytes (set low here, docs/adr/0018-field-test-build.md §3.1), and a
 * test server lets only staff into the lab's runs (§4.D).
 */
@SpringBootTest(
    properties = [
        "hovanki.field.max-devices=2",
        "hovanki.field.max-run-bytes=4KB",
        "hovanki.lab.join-staff-only=true",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestConfig::class)
class FieldLimitsApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired emailSender: EmailSender,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val features: FeatureFlags,
) {
    private val admin = AdminTestClient(mvc, emailSender as RecordingEmailSender, clock, jdbc)
    private val settings = GameSettings(zone = shrinkingZone(GeoPoint(50.4501, 30.5234)))

    @Test
    fun aGamesLogTakesOnlySoManyPhonesAndBytes() {
        features.set(ServerFeature.FIELD_LOG, true, "test", clock.instant())
        val game = post(ApiRoutes.GAMES, CreateGameRequest("Host", settings).asJson()).ok<SessionResponse>()
        val guests = (1..2).map {
            post(ApiRoutes.JOIN, JoinGameRequest(game.snapshot.joinCode, "Guest $it").asJson()).ok<SessionResponse>()
        }
        val phone = fieldJoin(game.session).ok<FieldJoinResponse>()
        fieldJoin(guests[0].session).ok<FieldJoinResponse>()
        fieldJoin(guests[1].session).error(409, ErrorCode.WRONG_STATE, ErrorReason.LIMIT_REACHED)

        // Random text barely packs: ~1 KB a chunk, a few go into the run's 4 KB.
        var seq = 1L
        val answers = ArrayList<TestResponse>()
        while (answers.size < 10 && answers.lastOrNull()?.status != 409) {
            answers += upload(phone, seq, seq + 9)
            seq += 10
        }
        assertTrue(answers.count { it.status == 200 } >= 2, "${answers.map { it.status }}")
        answers.last().error(409, ErrorCode.WRONG_STATE, ErrorReason.LIMIT_REACHED)
    }

    @Test
    fun onlyStaffJoinTheLabsRunsOnATestServer() {
        features.set(ServerFeature.RADIO_LAB, true, "test", clock.instant())
        val staff = admin.staff(UserRole.ADMIN)
        val run = admin.post(ApiRoutes.ADMIN_LAB_RUNS, AdminLabRunRequest("Field", "e2e", "a test"), staff)
            .ok<AdminLabRun>()
        val player = register()
        labJoin(run.code, token = null).error(403, ErrorCode.FORBIDDEN)
        labJoin(run.code, token = player.token).error(403, ErrorCode.FORBIDDEN)
        labJoin(run.code, token = "nonsense").error(401, ErrorCode.UNAUTHORIZED)
        labJoin(run.code, token = staff.account.token).ok<LabJoinResponse>()
    }

    /** The switches live in the database every test context shares: back off for the others. */
    @AfterTest
    fun switchesOff() {
        features.set(ServerFeature.FIELD_LOG, false, "test", clock.instant())
    }

    private fun register(): AccountSession {
        val nickname = uniqueName("limits")
        return post(
            ApiRoutes.ACCOUNTS,
            RegisterRequest(nickname, "$nickname@example.com", "correct horse battery").asJson(),
        )
            .ok()
    }

    private fun fieldJoin(session: PlayerSession): TestResponse = post(
        ApiRoutes.GAME_FIELD_JOIN.replace("{gameId}", session.gameId.value),
        FieldJoinRequest(consentAtMillis = clock.millis()).asJson(),
        session.token,
    )

    private fun labJoin(code: String, token: String?): TestResponse =
        post(ApiRoutes.LAB_JOIN, LabJoinRequest(code, "A").asJson(), token)

    private fun upload(phone: FieldJoinResponse, seqFrom: Long, seqTo: Long): TestResponse {
        val noise = Random(seqFrom)
        val lines = (seqFrom..seqTo).joinToString("") { seq ->
            val text = (1..80).map { 'a' + noise.nextInt(26) }.joinToString("")
            """{"t":1,"dt":1,"k":"note","seq":$seq,"text":"$text"}""" + "\n"
        }
        val response = mvc.post(ApiRoutes.labEvents(phone.runId)) {
            contentType = MediaType.parseMediaType(LabUpload.CONTENT_TYPE)
            content = lines.toByteArray()
            header(HttpHeaders.AUTHORIZATION, "${ApiRoutes.AUTH_SCHEME} ${phone.token}")
            param(LabUpload.PARAM_SEQ_FROM, seqFrom.toString())
            param(LabUpload.PARAM_SEQ_TO, seqTo.toString())
            param(LabUpload.PARAM_COUNT, (seqTo - seqFrom + 1).toString())
        }.andReturn().response
        return TestResponse(response.status, response.getContentAsString(Charsets.UTF_8))
    }

    private fun post(path: String, json: String, token: String? = null): TestResponse {
        val response = mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = json
            if (token != null) header(HttpHeaders.AUTHORIZATION, "${ApiRoutes.AUTH_SCHEME} $token")
        }.andReturn().response
        return TestResponse(response.status, response.getContentAsString(Charsets.UTF_8))
    }
}
