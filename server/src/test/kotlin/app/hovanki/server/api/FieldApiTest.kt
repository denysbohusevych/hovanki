package app.hovanki.server.api

import app.hovanki.server.MutableClock
import app.hovanki.server.account.AccountTestConfig
import app.hovanki.server.account.uniqueName
import app.hovanki.server.features.FeatureFlags
import app.hovanki.server.game.GameJanitor
import app.hovanki.server.game.GameRegistry
import app.hovanki.server.game.GameService
import app.hovanki.server.game.IdGenerator
import app.hovanki.server.lab.FieldProperties
import app.hovanki.server.lab.FieldRunService
import app.hovanki.server.lab.LabLive
import app.hovanki.server.lab.LabRunRecord
import app.hovanki.server.lab.LabRunRepository
import app.hovanki.server.mail.EmailSender
import app.hovanki.server.mail.RecordingEmailSender
import app.hovanki.server.ratelimit.RateLimiter
import app.hovanki.shared.lab.FieldKinds
import app.hovanki.shared.lab.GpsFields
import app.hovanki.shared.lab.LabPlanState
import app.hovanki.shared.lab.LabSchema
import app.hovanki.shared.lab.MarkFields
import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.AdminLabRun
import app.hovanki.shared.protocol.AdminLabRunRequest
import app.hovanki.shared.protocol.AdminLabRunView
import app.hovanki.shared.protocol.AdminLabRuns
import app.hovanki.shared.protocol.AdminReasonRequest
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.FieldJoinRequest
import app.hovanki.shared.protocol.FieldJoinResponse
import app.hovanki.shared.protocol.FieldUpload
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.LabCapabilities
import app.hovanki.shared.protocol.LabEventsResponse
import app.hovanki.shared.protocol.LabJoinRequest
import app.hovanki.shared.protocol.LabJoinResponse
import app.hovanki.shared.protocol.LabRunId
import app.hovanki.shared.protocol.LabRunKind
import app.hovanki.shared.protocol.LabRunStatus
import app.hovanki.shared.protocol.LabUpload
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.UserRole
import app.hovanki.shared.rules.shrinkingZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The field log over HTTP (docs/adr/0018-field-test-build.md §3, docs/field-test.md step 2): the field build's phone
 * joins its game's log with the game token, uploads like a lab phone, coordinates only there; the run ends with the
 * game; staff get the lab's screen.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountTestConfig::class)
class FieldApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired emailSender: EmailSender,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val features: FeatureFlags,
    @Autowired private val janitor: GameJanitor,
    @Autowired private val registry: GameRegistry,
    @Autowired private val labRuns: LabRunRepository,
    @Autowired private val games: GameService,
    @Autowired private val rateLimiter: RateLimiter,
    @Autowired private val live: LabLive,
    @Autowired private val fieldProperties: FieldProperties,
    @Autowired private val ids: IdGenerator,
    @Autowired private val transactionManager: PlatformTransactionManager,
) {
    private val admin = AdminTestClient(mvc, emailSender as RecordingEmailSender, clock, jdbc)
    private val park = GeoPoint(50.4501, 30.5234)
    private val settings = GameSettings(zone = shrinkingZone(park), hidingSeconds = 30, seekingSeconds = 120)

    @BeforeTest
    fun fieldOn() {
        switch(ServerFeature.FIELD_LOG, true)
        switch(ServerFeature.RADIO_LAB, false)
    }

    /** The switches live in the database every test context shares: back off for the others. */
    @AfterTest
    fun fieldOff() {
        switch(ServerFeature.FIELD_LOG, false)
        switch(ServerFeature.RADIO_LAB, false)
    }

    @Test
    fun theFieldLogIsOffUntilTheOperatorTurnsItOn() {
        val host = createGame(token = null).session
        switch(ServerFeature.FIELD_LOG, false)
        // Nothing says the route exists: not the token, not the body.
        fieldJoin(host).error(404, ErrorCode.NOT_FOUND)
        fieldJoinRaw(host.gameId.value, token = null, body = "{}").error(404, ErrorCode.NOT_FOUND)
        fieldJoinRaw(host.gameId.value, token = "nonsense", body = null).error(404, ErrorCode.NOT_FOUND)

        switch(ServerFeature.FIELD_LOG, true)
        val phone = fieldJoin(host).ok<FieldJoinResponse>()
        // The lab off changes nothing for the field log, and the lab's plan isn't the field log's.
        upload(phone, listOf(gps(phone, 1))).ok<LabEventsResponse>()
        phoneGet(ApiRoutes.labState(phone.runId), phone.token).error(404, ErrorCode.NOT_FOUND)
        switch(ServerFeature.RADIO_LAB, true)
        phoneGet(ApiRoutes.labState(phone.runId), phone.token).error(404, ErrorCode.NOT_FOUND)

        switch(ServerFeature.FIELD_LOG, false)
        upload(phone, listOf(gps(phone, 2))).error(404, ErrorCode.NOT_FOUND)
        switch(ServerFeature.FIELD_LOG, true)
        assertEquals(2L, upload(phone, listOf(gps(phone, 2))).ok<LabEventsResponse>().ackedSeq)
    }

    @Test
    fun playersJoinTheirGamesLogWithTheGameToken() {
        val alice = register()
        val game = createGame(alice.token)
        val host = game.session
        val guest = join(game.snapshot.joinCode, token = null).session

        // No token, a wrong one, another game's; no consent, a consent from before the field build; no JSON.
        fieldJoinRaw(host.gameId.value, token = null, body = "{}").error(401, ErrorCode.UNAUTHORIZED)
        fieldJoinRaw(host.gameId.value, token = "nonsense", body = "{}").error(401, ErrorCode.UNAUTHORIZED)
        val other = createGame(token = null).session
        fieldJoin(other.copy(gameId = host.gameId)).error(403, ErrorCode.FORBIDDEN)
        fieldJoin(host, consentAt = null).error(400, ErrorCode.BAD_REQUEST)
        fieldJoin(host, consentAt = FieldUpload.EARLIEST_CONSENT_MILLIS - 1).error(400, ErrorCode.BAD_REQUEST)
        fieldJoinRaw(host.gameId.value, token = host.token, body = "not json").error(400, ErrorCode.BAD_REQUEST)

        val consent = clock.millis() - 60_000
        val a = fieldJoin(host, consentAt = consent).ok<FieldJoinResponse>()
        assertEquals(host.playerId.value, a.label)
        assertEquals(clock.millis(), a.serverTimeMillis)
        assertEquals(FieldUpload.INTERVAL_MILLIS, a.uploadIntervalMillis)
        assertEquals(LabUpload.MAX_EVENTS, a.maxEvents)
        assertTrue(Regex("[0-9a-f]{32}").matches(a.salt), a.salt)
        val g = fieldJoin(guest).ok<FieldJoinResponse>()
        assertEquals(a.runId, g.runId)
        assertEquals(a.salt, g.salt)
        // The app restarted: a new device in the same run.
        val again = fieldJoin(host).ok<FieldJoinResponse>()
        assertEquals(a.runId, again.runId)
        assertTrue(again.deviceId != a.deviceId && again.token != a.token)

        // One run for the game; the player's account with the phone, none for the guest; the consent; never the token.
        val runs = jdbc.sql("SELECT id FROM lab_runs WHERE game_id = :g AND kind = 'GAME'")
            .param("g", host.gameId.value).query(String::class.java).list()
        assertEquals(listOf(a.runId.value), runs)
        assertEquals(alice.user.id.value, deviceColumn(a.deviceId, "user_id"))
        assertNull(deviceColumn(g.deviceId, "user_id"))
        val consentAt = jdbc.sql("SELECT consent_at FROM lab_devices WHERE id = :id").param("id", a.deviceId)
            .query { rs, _ -> rs.getTimestamp(1).time }.single()
        assertEquals(consent, consentAt)
        val byToken = jdbc.sql("SELECT count(*) FROM lab_devices WHERE token_hash = :t").param("t", a.token)
            .query(Long::class.java).single()
        assertEquals(0L, byToken)

        // The phones upload their logs, coordinates and the player's marks included.
        // A position anywhere else than in `gps` is not kept.
        val strayPosition = event(a.runId, 3, FieldKinds.MARK) {
            put(MarkFields.BY, MarkFields.PLAYER)
            put(GpsFields.LAT, park.lat)
            put(GpsFields.LON, park.lon)
        }
        upload(a, listOf(gps(a, 1), mark(a, 2, "radar silent"), strayPosition)).ok<LabEventsResponse>()
        upload(g, listOf(gps(g, 1))).ok<LabEventsResponse>()
        // A lab phone's token isn't a game's, and a field token isn't for another run.
        upload(g, listOf(gps(g, 2)), runId = LabRunId("elsewhere")).error(403, ErrorCode.FORBIDDEN)

        // The admin sees the game's run among the lab's, without a code to join by, and its raw logs.
        val staff = admin.staff(UserRole.ADMIN)
        val listed = admin.get(ApiRoutes.ADMIN_LAB_RUNS, staff).ok<AdminLabRuns>().runs.single { it.id == a.runId }
        assertEquals(LabRunKind.GAME to host.gameId.value, listed.kind to listed.gameId)
        assertEquals("", listed.code)
        assertTrue(listed.qr.isEmpty())
        assertEquals(LabRunStatus.RUNNING, listed.status)
        val view = admin.get(ApiRoutes.adminLabRun(a.runId), staff).ok<AdminLabRunView>()
        assertEquals(3, view.devices.size)
        assertEquals(consent, view.devices.single { it.id == a.deviceId }.consentAtMillis)
        // A game's run ends with its game, not by the console.
        admin.post(ApiRoutes.adminLabRun(a.runId, "finish"), AdminReasonRequest("no"), staff)
            .error(409, ErrorCode.WRONG_STATE)
        val raw = admin.post(ApiRoutes.adminLabRun(a.runId, "raw"), AdminReasonRequest("analysis"), staff).expect(200)
        assertTrue(raw.contentDisposition.orEmpty().contains("hovanki-field-${host.gameId.value}.zip"))
        val files = unzip(raw.bytes)
        val aliceLog = files.entries.single { it.key.endsWith("${a.deviceId}.jsonl") }.value.lines()
            .filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(
            listOf(FieldKinds.GPS, FieldKinds.MARK, FieldKinds.MARK),
            aliceLog.map { it["k"].toString().trim('"') },
        )
        assertTrue(LabSchema.hasCoordinates(aliceLog[0]), "${aliceLog[0]}")
        assertEquals("\"radar silent\"", aliceLog[1][MarkFields.TEXT].toString())
        assertFalse(LabSchema.hasCoordinates(aliceLog[2]), "${aliceLog[2]}")
        assertEquals("\"${MarkFields.PLAYER}\"", aliceLog[2][MarkFields.BY].toString())
    }

    @Test
    fun aPhoneWhoseClockIsAheadAgreedByNow() {
        // The consent screen comes before the app knows the server's clock: two days ahead is kept as now.
        val host = createGame(token = null).session
        val phone = fieldJoin(host, consentAt = clock.millis() + Duration.ofDays(2).toMillis()).ok<FieldJoinResponse>()
        val consentAt = jdbc.sql("SELECT consent_at FROM lab_devices WHERE id = :id").param("id", phone.deviceId)
            .query { rs, _ -> rs.getTimestamp(1).time }.single()
        assertEquals(clock.millis(), consentAt)
    }

    @Test
    fun aServerFinishesOnlyItsOwnRunsAndThoseLeftByARestart() {
        // Another server process on the same database (a test context, a local server pointed at staging's) has games
        // this one never sees: their runs stay open. A run opened before this process started is a previous one's.
        fun openRun(game: String, createdAt: Instant): String {
            val run = LabRunRecord(
                id = "run-$game",
                code = "game:$game",
                title = "Game $game",
                scenarioId = "game",
                scenarioVersion = 0,
                plan = LabPlanState(status = LabRunStatus.RUNNING),
                createdByName = null,
                createdAt = createdAt,
                startedAt = createdAt,
                salt = "00",
                kind = LabRunKind.GAME,
                gameId = game,
            )
            assertTrue(labRuns.insertGameRunIfAbsent(run))
            return run.id
        }
        val suffix = uniqueName("gone")
        val leftover = openRun("left-$suffix", clock.instant().minus(Duration.ofHours(1)))
        val restarted = FieldRunService(
            labRuns, games, features, rateLimiter, live, fieldProperties, ids, clock, transactionManager,
        )
        val others = openRun("other-$suffix", clock.instant())
        try {
            val ours = setOf("left-$suffix", "other-$suffix")
            restarted.closeRunsOfGoneGames { it.value !in ours }
            assertEquals("FINISHED", runColumn(LabRunId(leftover), "status"))
            assertEquals("RUNNING", runColumn(LabRunId(others), "status"))
            // Only right after the start: later the other process's run stays open, here and in the context's own.
            restarted.closeRunsOfGoneGames { it.value !in ours }
            janitor.removeExpiredGames()
            assertEquals("RUNNING", runColumn(LabRunId(others), "status"))
            assertNull(runColumn(LabRunId(others), "finished_at"))
        } finally {
            labRuns.deleteRun(leftover)
            labRuns.deleteRun(others)
        }
    }

    @Test
    fun aLabRunKeepsNoCoordinates() {
        switch(ServerFeature.RADIO_LAB, true)
        val staff = admin.staff(UserRole.ADMIN)
        val run = admin.post(ApiRoutes.ADMIN_LAB_RUNS, AdminLabRunRequest("Park", "e2e", "a test"), staff)
            .ok<AdminLabRun>()
        val joined = mvc.post(ApiRoutes.LAB_JOIN) {
            contentType = MediaType.APPLICATION_JSON
            content = LabJoinRequest(run.code, "A", capabilities = LabCapabilities(platform = Platform.IOS)).asJson()
        }.andReturn().response
        val phone = TestResponse(joined.status, joined.contentAsString).ok<LabJoinResponse>()
        val lines = listOf(
            event(phone.runId, 1, FieldKinds.GPS) {
                put(GpsFields.LAT, 50.45)
                put(GpsFields.LON, 30.52)
                put(GpsFields.ACC, 5.0)
            },
            event(phone.runId, 2, FieldKinds.TICK) { put("n", 1) },
        )
        for (gzip in listOf(true, false)) {
            val seq = if (gzip) 1L else 3L
            val batch = lines.mapIndexed { i, line ->
                JsonObject(line + ("seq" to Json.parseToJsonElement("${seq + i}")))
            }
            uploadAs(phone.token, phone.runId, batch, gzip).ok<LabEventsResponse>()
        }
        val raw = admin.post(ApiRoutes.adminLabRun(run.id, "raw"), AdminReasonRequest("check"), staff).expect(200)
        val stored = unzip(raw.bytes).values.single().lines().filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(4, stored.size)
        assertTrue(stored.none(LabSchema::hasCoordinates), "$stored")
        // What says nothing of where stays.
        assertEquals("5.0", stored.first()[GpsFields.ACC].toString())
    }

    @Test
    fun theGamesLogEndsWithItsGame() {
        val host = createGame(token = null).session
        val phone = fieldJoin(host).ok<FieldJoinResponse>()
        upload(phone, listOf(gps(phone, 1))).ok<LabEventsResponse>()
        // A game still on keeps its log open.
        janitor.removeExpiredGames()
        assertNull(runColumn(phone.runId, "finished_at"))

        registry.removeIf { it.id == host.gameId }
        janitor.removeExpiredGames()
        assertEquals("FINISHED", runColumn(phone.runId, "status"))
        assertNotNull(runColumn(phone.runId, "finished_at"))
        // The game is gone: no new phones (its tokens with it), but the last uploads still come for a while.
        fieldJoin(host).error(401, ErrorCode.UNAUTHORIZED)
        upload(phone, listOf(gps(phone, 2))).ok<LabEventsResponse>()
        clock.advance(Duration.ofMinutes(31))
        upload(phone, listOf(gps(phone, 3))).error(409, ErrorCode.WRONG_STATE, ErrorReason.LAB_RUN_CLOSED)
    }

    @Test
    fun staffGetTheLabsScreenWhileTheLabIsOn() {
        val staff = admin.staff(UserRole.MODERATOR)
        val player = register()
        switch(ServerFeature.RADIO_LAB, true)
        assertTrue(me(staff.account.token).labAccess)
        assertFalse(me(player.token).labAccess)
        switch(ServerFeature.RADIO_LAB, false)
        assertFalse(me(staff.account.token).labAccess)
    }

    // Games and accounts

    private fun register(): AccountSession {
        val nickname = uniqueName("field")
        return post(ApiRoutes.ACCOUNTS, RegisterRequest(nickname, "$nickname@example.com", PASSWORD).asJson())
            .ok()
    }

    private fun me(token: String): UserProfile {
        val response = mvc.get(ApiRoutes.ME) {
            header(HttpHeaders.AUTHORIZATION, "${ApiRoutes.AUTH_SCHEME} $token")
        }.andReturn().response
        return TestResponse(response.status, response.getContentAsString(Charsets.UTF_8)).ok()
    }

    private fun createGame(token: String?): SessionResponse =
        post(ApiRoutes.GAMES, CreateGameRequest("Host", settings).asJson(), token).ok()

    private fun join(joinCode: String, token: String?): SessionResponse =
        post(ApiRoutes.JOIN, JoinGameRequest(joinCode, "Guest").asJson(), token).ok()

    // The field log

    private fun switch(feature: ServerFeature, on: Boolean) = features.set(feature, on, "test", clock.instant())

    private fun fieldJoin(session: PlayerSession, consentAt: Long? = clock.millis()): TestResponse {
        val request = FieldJoinRequest(
            model = "Pixel 8",
            os = "Android 16",
            build = "preview",
            commit = "abc1234",
            capabilities = LabCapabilities(platform = Platform.ANDROID),
            consentAtMillis = consentAt,
        )
        return fieldJoinRaw(session.gameId.value, session.token, request.asJson())
    }

    private fun fieldJoinRaw(gameId: String, token: String?, body: String?): TestResponse {
        val response = mvc.post(ApiRoutes.GAME_FIELD_JOIN.replace("{gameId}", gameId)) {
            contentType = MediaType.APPLICATION_JSON
            if (body != null) content = body
            if (token != null) header(HttpHeaders.AUTHORIZATION, "${ApiRoutes.AUTH_SCHEME} $token")
        }.andReturn().response
        return TestResponse(response.status, response.getContentAsString(Charsets.UTF_8))
    }

    private fun upload(
        phone: FieldJoinResponse,
        events: List<JsonObject>,
        runId: LabRunId = phone.runId,
    ): TestResponse = uploadAs(phone.token, runId, events, gzip = true)

    private fun uploadAs(token: String, runId: LabRunId, events: List<JsonObject>, gzip: Boolean): TestResponse {
        val seqs = events.map { it["seq"].toString().toLong() }
        val jsonl = events.joinToString("") { "$it\n" }.toByteArray()
        val response = mvc.post(ApiRoutes.labEvents(runId)) {
            contentType = MediaType.parseMediaType(LabUpload.CONTENT_TYPE)
            content = if (gzip) gzipped(jsonl) else jsonl
            if (gzip) header(HttpHeaders.CONTENT_ENCODING, "gzip")
            header(HttpHeaders.AUTHORIZATION, "${ApiRoutes.AUTH_SCHEME} $token")
            param(LabUpload.PARAM_SEQ_FROM, seqs.min().toString())
            param(LabUpload.PARAM_SEQ_TO, seqs.max().toString())
            param(LabUpload.PARAM_COUNT, events.size.toString())
        }.andReturn().response
        return TestResponse(response.status, response.getContentAsString(Charsets.UTF_8))
    }

    private fun phoneGet(path: String, token: String): TestResponse {
        val response = mvc.get(path) {
            header(HttpHeaders.AUTHORIZATION, "${ApiRoutes.AUTH_SCHEME} $token")
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

    private fun deviceColumn(id: String, column: String): String? =
        jdbc.sql("SELECT $column FROM lab_devices WHERE id = :id").param("id", id)
            .query { rs, _ -> listOf(rs.getString(1)) }.single().single()

    private fun runColumn(id: LabRunId, column: String): String? =
        jdbc.sql("SELECT $column FROM lab_runs WHERE id = :id").param("id", id.value)
            .query { rs, _ -> listOf(rs.getString(1)) }.single().single()

    private fun gps(phone: FieldJoinResponse, seq: Long): JsonObject = event(phone.runId, seq, FieldKinds.GPS) {
        put(GpsFields.LAT, park.lat)
        put(GpsFields.LON, park.lon)
        put(GpsFields.ACC, 4.0)
        put(GpsFields.ACCEPTED, true)
    }

    private fun mark(phone: FieldJoinResponse, seq: Long, text: String): JsonObject =
        event(phone.runId, seq, FieldKinds.MARK) {
            put(MarkFields.BY, MarkFields.PLAYER)
            put(MarkFields.TEXT, text)
        }

    private fun event(runId: LabRunId, seq: Long, kind: String, fields: JsonObjectBuilder.() -> Unit): JsonObject {
        val t = clock.millis()
        return buildJsonObject {
            put("t", t)
            put("dt", t)
            put("mono", t)
            put("dev", "p")
            put("k", kind)
            put("app", "active")
            put("run", runId.value)
            put("seq", seq)
            fields()
        }
    }

    private companion object {
        const val PASSWORD = "correct horse battery"

        fun gzipped(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(bytes) }
            return out.toByteArray()
        }

        fun unzip(bytes: ByteArray): Map<String, String> = buildMap {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                }
            }
        }
    }
}
