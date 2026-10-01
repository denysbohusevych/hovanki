package app.hovanki.server.api

import app.hovanki.server.MutableClock
import app.hovanki.server.account.AccountTestConfig
import app.hovanki.server.features.FeatureFlags
import app.hovanki.server.lab.LabReportWriter
import app.hovanki.server.mail.EmailSender
import app.hovanki.server.mail.RecordingEmailSender
import app.hovanki.shared.lab.LabReport
import app.hovanki.shared.protocol.AdminLabAdvanceRequest
import app.hovanki.shared.protocol.AdminLabRun
import app.hovanki.shared.protocol.AdminLabRunRequest
import app.hovanki.shared.protocol.AdminLabRunView
import app.hovanki.shared.protocol.AdminLabRuns
import app.hovanki.shared.protocol.AdminReasonRequest
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.LabAdvanceRequest
import app.hovanki.shared.protocol.LabCapabilities
import app.hovanki.shared.protocol.LabEventsResponse
import app.hovanki.shared.protocol.LabJoinRequest
import app.hovanki.shared.protocol.LabJoinResponse
import app.hovanki.shared.protocol.LabRunAction
import app.hovanki.shared.protocol.LabRunId
import app.hovanki.shared.protocol.LabRunStateView
import app.hovanki.shared.protocol.LabRunStatus
import app.hovanki.shared.protocol.LabUpload
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.UserRole
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
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
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The radio lab's runs over HTTP (docs/adr/0017-radar-techniques-and-big-run.md §5): an admin makes a run, test phones
 * join it by its code, follow its plan by the server's clock and upload their logs; the live view, the report, the raw
 * logs and the audit log.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountTestConfig::class)
class LabApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired emailSender: EmailSender,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val features: FeatureFlags,
    @Autowired private val reports: LabReportWriter,
) {
    private val admin = AdminTestClient(mvc, emailSender as RecordingEmailSender, clock, jdbc)

    @BeforeTest
    fun labOn() {
        lab(true)
        // The field log's switch opens the upload route too (FieldApiTest): off here, the lab's alone.
        features.set(ServerFeature.FIELD_LOG, false, "test", clock.instant())
    }

    @Test
    fun theLabIsOffUntilTheOperatorTurnsItOn() {
        val staff = admin.staff(UserRole.ADMIN)
        lab(false)
        // The admin's side works whatever the flag: an admin turns it on, and old reports stay readable.
        val run = createRun(staff)
        val runs = admin.get(ApiRoutes.ADMIN_LAB_RUNS, staff).ok<AdminLabRuns>()
        assertContains(runs.runs.map { it.id }, run.id)
        join(run.code, "A").error(404, ErrorCode.NOT_FOUND)
        // Not even a body that isn't one, or an account token nobody knows, says the route is there.
        joinBody("{}").error(404, ErrorCode.NOT_FOUND)
        joinBody(null).error(404, ErrorCode.NOT_FOUND)
        join(run.code, "A", accountToken = "no such account").error(404, ErrorCode.NOT_FOUND)

        lab(true)
        joinBody("{}").error(400, ErrorCode.BAD_REQUEST)
        // Anybody with the code joins here: a stale account token (a development database made anew) doesn't count.
        join(run.code, "B", accountToken = "no such account").ok<LabJoinResponse>()
        val phone = joined(run.code, "A")
        lab(false)
        phoneGet(ApiRoutes.labState(phone.runId), phone.token).error(404, ErrorCode.NOT_FOUND)
        phoneGet(ApiRoutes.labState(phone.runId), "no such token").error(404, ErrorCode.NOT_FOUND)
        advance(phone, LabRunAction.NEXT).error(404, ErrorCode.NOT_FOUND)
        upload(phone, lines(phone, 1L..2L, clock.millis())).error(404, ErrorCode.NOT_FOUND)

        lab(true)
        assertEquals(LabRunStatus.CREATED, state(phone).status)
    }

    @Test
    fun adminsMakeRunsAndPhonesJoinThemByTheCode() {
        val moderator = admin.staff(UserRole.MODERATOR)
        admin.get(ApiRoutes.ADMIN_LAB_RUNS, moderator).error(403, ErrorCode.FORBIDDEN)
        admin.post(ApiRoutes.ADMIN_LAB_RUNS, AdminLabRunRequest("Park", "e2e", "a test"), moderator)
            .error(403, ErrorCode.FORBIDDEN)

        val staff = admin.staff(UserRole.ADMIN)
        admin.post(ApiRoutes.ADMIN_LAB_RUNS, AdminLabRunRequest("Park", "e2e", " "), staff)
            .error(400, ErrorCode.BAD_REQUEST)
        admin.post(ApiRoutes.ADMIN_LAB_RUNS, AdminLabRunRequest("Park", "no such plan", "a test"), staff)
            .error(400, ErrorCode.BAD_REQUEST)
        val run = admin.post(ApiRoutes.ADMIN_LAB_RUNS, AdminLabRunRequest(" Park ", "e2e", "field test"), staff)
            .ok<AdminLabRun>()
        assertEquals("Park", run.title)
        assertEquals(6, run.code.length)
        assertEquals(LabRunStatus.CREATED, run.status)
        assertEquals("e2e" to 3, run.scenarioId to run.scenarioVersion)
        assertEquals(staff.account.user.nickname, run.createdByName)
        assertTrue(run.qr.isNotEmpty() && run.qr.all { it.length == run.qr.size })
        assertEquals(1, audits("LAB_RUN_CREATE", run.id, "field test"))
        val list = admin.get(ApiRoutes.ADMIN_LAB_RUNS, staff).ok<AdminLabRuns>()
        assertEquals(run.id, list.runs.first { it.id == run.id }.id)
        assertEquals(setOf("radio", "e2e", "touch"), list.scenarios.map { it.id }.toSet())
        assertEquals(listOf("A", "B", "droid"), list.scenarios.first { it.id == "e2e" }.labels)

        // A wrong code, no code, a label the plan doesn't have.
        val wrong = "23456789".first { it !in run.code }.toString().repeat(6)
        join(wrong, "A").error(404, ErrorCode.NOT_FOUND)
        join("not a code", "A").error(404, ErrorCode.NOT_FOUND)
        join(run.code, "Z").error(400, ErrorCode.BAD_REQUEST)
        // The QR's payload, typed with dashes in lower case, works.
        val scanned = "hovanki-lab:" + run.code.lowercase().chunked(3).joinToString("-")
        val a = joined(scanned, "A")
        assertEquals(run.id, a.runId)
        assertTrue(Regex("[0-9a-f]{8}").matches(a.radarToken), a.radarToken)
        assertEquals("e2e" to 3, a.scenarioId to a.scenarioVersion)
        assertEquals(listOf("A", "B", "droid"), a.labels)
        assertEquals(LabRunStatus.CREATED to -1, a.state.status to a.state.stepIndex)
        assertEquals(a.state, state(a).copy(serverTimeMillis = a.state.serverTimeMillis))

        // Without a token, with a wrong one, and on another run.
        phoneGet(ApiRoutes.labState(a.runId), null).error(401, ErrorCode.UNAUTHORIZED)
        phoneGet(ApiRoutes.labState(a.runId), "no such token").error(401, ErrorCode.UNAUTHORIZED)
        val other = joined(createRun(staff).code, "B")
        phoneGet(ApiRoutes.labState(other.runId), a.token).error(403, ErrorCode.FORBIDDEN)
        upload(a, lines(a, 1L..2L, clock.millis()), runId = other.runId).error(403, ErrorCode.FORBIDDEN)

        // The same label again is a new device (the app restarted); eight at most.
        val more = (2..8).map { joined(run.code, listOf("A", "B", "droid")[it % 3]) }
        join(run.code, "B").error(409, ErrorCode.WRONG_STATE, ErrorReason.LIMIT_REACHED)
        assertEquals(8, (more + a).map { it.radarToken }.toSet().size)
        assertEquals(8, (more + a).map { it.deviceId }.toSet().size)
        val view = admin.get(ApiRoutes.adminLabRun(a.runId), staff).ok<AdminLabRunView>()
        assertEquals(8, view.devices.size)
        assertEquals(8, view.run.devices)
        assertEquals(Platform.IOS, view.devices.first { it.id == a.deviceId }.capabilities.platform)
        assertEquals("iPhone15,2", view.devices.first { it.id == a.deviceId }.model)
        assertEquals(listOf("all_hiders", "probe", "all_again"), view.steps.map { it.id })
        assertEquals(setOf("A", "B", "droid"), view.steps.first().hints.keys)
        // The token is stored only as its hash.
        val stored = jdbc.sql("SELECT count(*) FROM lab_devices WHERE token_hash = :t").param("t", a.token)
            .query(Long::class.java).single()
        assertEquals(0L, stored)
    }

    @Test
    fun thePlanMovesByTheServersClock() {
        val staff = admin.staff(UserRole.ADMIN)
        val run = createRun(staff)
        val phone = joined(run.code, "A")
        val start = clock.millis()

        // NEXT starts the first step; the timed steps move on by the clock.
        val started = advance(phone, LabRunAction.NEXT).ok<LabRunStateView>()
        assertEquals(Triple(LabRunStatus.RUNNING, 0, 1L), Triple(started.status, started.stepIndex, started.revision))
        assertEquals(start, started.stepStartedAtMillis)
        clock.advance(Duration.ofSeconds(8))
        val second = state(phone)
        assertEquals(1 to start + 8_000, second.stepIndex to second.stepStartedAtMillis)
        assertEquals(1L, second.revision)

        // REPEAT from the console restarts the step now; PAUSE from the phone; RESUME shifts the start by the pause.
        clock.advance(Duration.ofSeconds(2))
        val repeatedAt = clock.millis()
        val repeated = adminAdvance(staff, run.id, LabRunAction.REPEAT, "B was in the pocket").state
        assertEquals(1 to repeatedAt, repeated.stepIndex to repeated.stepStartedAtMillis)
        assertEquals(2L, repeated.revision)
        val paused = advance(phone, LabRunAction.PAUSE).ok<LabRunStateView>()
        assertEquals(LabRunStatus.PAUSED to repeatedAt, paused.status to paused.pausedAtMillis)
        assertEquals(3L, paused.revision)
        clock.advance(Duration.ofSeconds(10))
        assertEquals(LabRunStatus.PAUSED to 1, state(phone).let { it.status to it.stepIndex })
        // PAUSE again changes nothing, and the console doesn't log it.
        adminAdvance(staff, run.id, LabRunAction.PAUSE, "again")
        val resumed = adminAdvance(staff, run.id, LabRunAction.RESUME, "go on").state
        assertEquals(LabRunStatus.RUNNING, resumed.status)
        assertEquals(repeatedAt + 10_000 to 4L, resumed.stepStartedAtMillis to resumed.revision)
        clock.advance(Duration.ofSeconds(6))
        val third = state(phone)
        assertEquals(2 to clock.millis(), third.stepIndex to third.stepStartedAtMillis)

        // After the last timed step the run is finished; NEXT then changes nothing.
        clock.advance(Duration.ofSeconds(8))
        val done = state(phone)
        assertEquals(Triple(LabRunStatus.FINISHED, 2, 4L), Triple(done.status, done.stepIndex, done.revision))
        val unchanged = advance(phone, LabRunAction.NEXT).ok<LabRunStateView>()
        assertEquals(done, unchanged.copy(serverTimeMillis = done.serverTimeMillis))
        assertEquals(2, audits("LAB_RUN_CONTROL", run.id))

        // A run that finished by its plan gets its report, with the one device that sent nothing.
        reports.awaitIdle()
        val view = admin.get(ApiRoutes.adminLabRun(run.id), staff).ok<AdminLabRunView>()
        assertEquals(LabRunStatus.FINISHED, view.run.status)
        assertEquals(start, view.run.startedAtMillis)
        assertNotNull(view.run.finishedAtMillis)
        assertTrue(view.run.reportReady)
        val report = report(staff, run.id)
        assertEquals(listOf("A"), report.devices.map { it.label })
        // A finished run takes no more phones.
        join(run.code, "B").error(409, ErrorCode.WRONG_STATE, ErrorReason.LAB_RUN_CLOSED)

        // Nor does one older than the join window.
        val old = createRun(staff)
        clock.advance(Duration.ofHours(25))
        join(old.code, "A").error(409, ErrorCode.WRONG_STATE, ErrorReason.LAB_RUN_CLOSED)
    }

    @Test
    fun phonesUploadTheirLogsForTheLiveViewAndTheReport() {
        val staff = admin.staff(UserRole.ADMIN)
        val run = createRun(staff)
        val a = joined(run.code, "A")
        val b = joined(run.code, "B")
        val droid = joined(run.code, "droid", Platform.ANDROID)
        val phones = listOf(a, b, droid)
        val t0 = clock.millis()
        adminAdvance(staff, run.id, LabRunAction.NEXT, "start")

        // The first 8 s: every phone hears the two others twice a second.
        clock.advance(Duration.ofSeconds(8))
        val first = phones.associateWith { log(it, phones, seqFrom = 1, from = t0 - 1_000, to = t0 + 8_000) }
        val acked = upload(a, first.getValue(a), gzip = false).ok<LabEventsResponse>()
        assertEquals(first.getValue(a).size.toLong(), acked.ackedSeq)
        assertEquals(clock.millis(), acked.receivedAtMillis)
        for (phone in listOf(b, droid)) upload(phone, first.getValue(phone)).ok<LabEventsResponse>()
        // A retried upload is answered as stored, and stored once.
        assertEquals(acked.ackedSeq, upload(a, first.getValue(a)).ok<LabEventsResponse>().ackedSeq)
        assertEquals(1L, chunks(a.deviceId))
        // A retry that grew meanwhile (the answer was lost, the phone logged on): only what is stored is acknowledged,
        // and the phone sends the rest again from there.
        val grown = lines(b, 1L..first.getValue(b).size + 2L, t0)
        assertEquals(first.getValue(b).size.toLong(), upload(b, grown).ok<LabEventsResponse>().ackedSeq)
        val rest = grown.takeLast(2)
        assertEquals(first.getValue(b).size + 2L, upload(b, rest).ok<LabEventsResponse>().ackedSeq)
        assertEquals(2L, chunks(b.deviceId))

        // What is no event of the batch is refused.
        val later = first.getValue(a).size + 1L
        upload(a, listOf(buildJsonObject { put("k", "tick") }), seqFrom = later).error(400, ErrorCode.BAD_REQUEST)
        upload(a, lines(a, later..later + 1, clock.millis()), seqFrom = later, seqTo = later - 1)
            .error(400, ErrorCode.BAD_REQUEST)
        upload(a, lines(a, later..later + 1, clock.millis()), seqFrom = later + 1).error(400, ErrorCode.BAD_REQUEST)
        uploadBytes(a, "not gzip".toByteArray(), later, later, 1, gzip = true).error(400, ErrorCode.BAD_REQUEST)

        // The console: the devices' uploads and the live view.
        val view = admin.get(ApiRoutes.adminLabRun(run.id), staff).ok<AdminLabRunView>()
        val shownA = view.devices.first { it.id == a.deviceId }
        assertEquals(acked.ackedSeq, shownA.lastSeq)
        assertEquals(first.getValue(a).size.toLong(), shownA.events)
        assertTrue(shownA.bytes > 0 && view.run.bytes >= shownA.bytes * 3 / 2, "${view.run.bytes}")
        val live = view.live.devices.first { it.deviceId == a.deviceId }
        assertEquals(Triple(0L, 0, "active"), Triple(live.clockOffsetMillis, live.stepIndex, live.appState))
        val heard = view.live.pairs.first { it.from == "A" && it.to == "B" }
        assertEquals("android_le/service_data", heard.channel)
        assertEquals(16, heard.heardInLast10s)
        assertEquals(6, view.live.pairs.size)

        // The next 6 s, then the admin ends the run: its report.
        clock.advance(Duration.ofSeconds(6))
        val second = phones.associateWith { phone ->
            val seqFrom = first.getValue(phone).size + if (phone == b) 3L else 1L
            log(phone, phones, seqFrom = seqFrom, from = t0 + 8_000, to = t0 + 14_000, step = 1)
        }
        for (phone in phones) upload(phone, second.getValue(phone)).ok<LabEventsResponse>()
        admin.post(ApiRoutes.adminLabRun(run.id, "finish"), AdminReasonRequest("done"), staff)
            .ok<AdminLabRunView>()
        reports.awaitIdle()
        val report = report(staff, run.id)
        assertEquals(listOf("A", "B", "droid"), report.devices.map { it.label })
        assertEquals(phones.map { it.radarToken }, report.devices.map { it.radarToken })
        assertEquals(listOf("before", "all_hiders", "probe"), report.steps.map { it.id })
        val hiders = report.steps.first { it.id == "all_hiders" }
        assertEquals(t0 to t0 + 8_000, hiders.startMillis to hiders.endMillis)
        val labels = listOf("A", "B", "droid")
        val pairs = labels.flatMap { from -> labels.filter { it != from }.map { from to it } }
        assertEquals(pairs.toSet(), hiders.directions.map { it.from to it.to }.toSet())
        for (direction in hiders.directions) assertEquals(2.0, direction.perSecond, "$direction")
        assertTrue(report.steps.flatMap { it.directions }.none { it.from.startsWith("?") })
        assertEquals(labels, report.ticks.map { it.label }.sorted())

        // The phones' last uploads still come in for a while, and the report takes them.
        val lastSeq = first.getValue(a).size + second.getValue(a).size + 1L
        upload(a, lines(a, lastSeq..lastSeq, clock.millis()), seqFrom = lastSeq).ok<LabEventsResponse>()
        reports.awaitIdle()
        assertEquals(lastSeq.toInt(), report(staff, run.id).devices.first { it.label == "A" }.events)
        clock.advance(Duration.ofMinutes(31))
        upload(a, lines(a, lastSeq + 1..lastSeq + 1, clock.millis()), seqFrom = lastSeq + 1)
            .error(409, ErrorCode.WRONG_STATE, ErrorReason.LAB_RUN_CLOSED)

        // The raw logs: a reason, a zip with one file per device, every event once, the audit log.
        admin.post(ApiRoutes.adminLabRun(run.id, "raw"), AdminReasonRequest(" "), staff)
            .error(400, ErrorCode.BAD_REQUEST)
        val raw = admin.post(ApiRoutes.adminLabRun(run.id, "raw"), AdminReasonRequest("analysis"), staff).expect(200)
        assertEquals("application/zip", raw.contentType)
        assertContains(raw.contentDisposition.orEmpty(), "hovanki-lab-${run.code}.zip")
        val files = unzip(raw.bytes)
        val names = listOf("A" to a, "B" to b, "droid" to droid).map { (label, phone) ->
            "hovanki-lab-$label-${phone.deviceId}.jsonl"
        }
        assertEquals(names.toSet(), files.keys)
        val linesOfA = files.getValue("hovanki-lab-A-${a.deviceId}.jsonl").lines().filter { it.isNotBlank() }
        assertEquals(lastSeq.toInt(), linesOfA.size)
        assertEquals(1, audits("LAB_RUN_DOWNLOAD", run.id, "analysis"))

        // Deleting it takes everything with it.
        admin.post(ApiRoutes.adminLabRun(run.id, "delete"), AdminReasonRequest(""), staff)
            .error(400, ErrorCode.BAD_REQUEST)
        admin.post(ApiRoutes.adminLabRun(run.id, "delete"), AdminReasonRequest("done with it"), staff).expect(204)
        admin.get(ApiRoutes.adminLabRun(run.id), staff).error(404, ErrorCode.NOT_FOUND)
        admin.get(ApiRoutes.adminLabRun(run.id, "report"), staff).error(404, ErrorCode.NOT_FOUND)
        for (phone in phones) assertEquals(0L, chunks(phone.deviceId))
        val devices = jdbc.sql("SELECT count(*) FROM lab_devices WHERE run_id = :id").param("id", run.id.value)
            .query(Long::class.java).single()
        assertEquals(0L, devices)
        phoneGet(ApiRoutes.labState(a.runId), a.token).error(401, ErrorCode.UNAUTHORIZED)
        assertEquals(1, audits("LAB_RUN_DELETE", run.id, "done with it"))
    }

    // Admins

    private fun createRun(staff: AdminTestClient.StaffLogin, scenario: String = "e2e"): AdminLabRun =
        admin.post(ApiRoutes.ADMIN_LAB_RUNS, AdminLabRunRequest("Park", scenario, "a test run"), staff).ok()

    private fun adminAdvance(
        staff: AdminTestClient.StaffLogin,
        id: LabRunId,
        action: LabRunAction,
        reason: String,
    ): AdminLabRunView =
        admin.post(ApiRoutes.adminLabRun(id, "advance"), AdminLabAdvanceRequest(action, reason), staff).ok()

    private fun report(staff: AdminTestClient.StaffLogin, id: LabRunId): LabReport =
        admin.get(ApiRoutes.adminLabRun(id, "report"), staff).ok()

    /** Entries of [action] about run [id] in the audit log, with [reason] if given. */
    private fun audits(action: String, id: LabRunId, reason: String? = null): Int = jdbc.sql(
        """
        SELECT count(*) FROM admin_audit
        WHERE action = :action AND target LIKE :target AND (CAST(:reason AS text) IS NULL OR reason = :reason)
        """.trimIndent(),
    )
        .param("action", action)
        .param("target", "%${id.value}%")
        .param("reason", reason)
        .query(Int::class.java)
        .single()

    // Phones

    private fun lab(on: Boolean) = features.set(ServerFeature.RADIO_LAB, on, "test", clock.instant())

    private fun join(
        code: String,
        label: String,
        platform: Platform = Platform.IOS,
        accountToken: String? = null,
    ): TestResponse {
        val request = LabJoinRequest(
            code = code,
            label = label,
            model = if (platform == Platform.IOS) "iPhone15,2" else "Pixel 8",
            os = "test",
            build = "debug",
            commit = "abc1234",
            capabilities = LabCapabilities(platform = platform),
        )
        val response = mvc.post(ApiRoutes.LAB_JOIN) {
            contentType = MediaType.APPLICATION_JSON
            content = request.asJson()
            if (accountToken != null) header(HttpHeaders.AUTHORIZATION, "${ApiRoutes.AUTH_SCHEME} $accountToken")
        }.andReturn().response
        return TestResponse(response.status, response.getContentAsString(Charsets.UTF_8))
    }

    private fun joinBody(body: String?): TestResponse {
        val response = mvc.post(ApiRoutes.LAB_JOIN) {
            contentType = MediaType.APPLICATION_JSON
            if (body != null) content = body
        }.andReturn().response
        return TestResponse(response.status, response.getContentAsString(Charsets.UTF_8))
    }

    private fun joined(code: String, label: String, platform: Platform = Platform.IOS): LabJoinResponse =
        join(code, label, platform).ok()

    private fun state(phone: LabJoinResponse): LabRunStateView =
        phoneGet(ApiRoutes.labState(phone.runId), phone.token).ok()

    private fun advance(phone: LabJoinResponse, action: LabRunAction): TestResponse {
        val response = mvc.post(ApiRoutes.labAdvance(phone.runId)) {
            contentType = MediaType.APPLICATION_JSON
            content = LabAdvanceRequest(action).asJson()
            header(HttpHeaders.AUTHORIZATION, "${ApiRoutes.AUTH_SCHEME} ${phone.token}")
        }.andReturn().response
        return TestResponse(response.status, response.getContentAsString(Charsets.UTF_8))
    }

    private fun phoneGet(path: String, token: String?): TestResponse {
        val response = mvc.get(path) {
            if (token != null) header(HttpHeaders.AUTHORIZATION, "${ApiRoutes.AUTH_SCHEME} $token")
        }.andReturn().response
        return TestResponse(response.status, response.getContentAsString(Charsets.UTF_8))
    }

    /** Uploads [events] as a batch from [seqFrom] (their own `seq` by default) to [seqTo]. */
    private fun upload(
        phone: LabJoinResponse,
        events: List<JsonObject>,
        gzip: Boolean = true,
        seqFrom: Long = events.firstNotNullOfOrNull { seqOf(it) } ?: 1,
        seqTo: Long = events.mapNotNull { seqOf(it) }.maxOrNull() ?: seqFrom,
        runId: LabRunId = phone.runId,
    ): TestResponse {
        val jsonl = events.joinToString("") { "$it\n" }.toByteArray()
        return uploadBytes(phone, if (gzip) gzipped(jsonl) else jsonl, seqFrom, seqTo, events.size, gzip, runId)
    }

    private fun uploadBytes(
        phone: LabJoinResponse,
        body: ByteArray,
        seqFrom: Long,
        seqTo: Long,
        count: Int,
        gzip: Boolean,
        runId: LabRunId = phone.runId,
    ): TestResponse {
        val response = mvc.post(ApiRoutes.labEvents(runId)) {
            contentType = MediaType.parseMediaType(LabUpload.CONTENT_TYPE)
            content = body
            if (gzip) header(HttpHeaders.CONTENT_ENCODING, "gzip")
            header(HttpHeaders.AUTHORIZATION, "${ApiRoutes.AUTH_SCHEME} ${phone.token}")
            param(LabUpload.PARAM_SEQ_FROM, seqFrom.toString())
            param(LabUpload.PARAM_SEQ_TO, seqTo.toString())
            param(LabUpload.PARAM_COUNT, count.toString())
        }.andReturn().response
        return TestResponse(response.status, response.getContentAsString(Charsets.UTF_8))
    }

    private fun chunks(deviceId: String): Long =
        jdbc.sql("SELECT count(*) FROM lab_chunks WHERE device_id = :id").param("id", deviceId)
            .query(Long::class.java).single()

    private companion object {
        /** Ticks with the numbers [seqs] at [t]. */
        fun lines(phone: LabJoinResponse, seqs: LongRange, t: Long): List<JsonObject> =
            seqs.map { seq -> event(phone, seq, t, "tick") { put("n", seq) } }

        /**
         * A phone's log from [from] to [to]: the session and its clock at the start, the step [step] (revision 1),
         * a tick a second, and the others' radar tokens twice a second.
         */
        fun log(
            phone: LabJoinResponse,
            phones: List<LabJoinResponse>,
            seqFrom: Long,
            from: Long,
            to: Long,
            step: Int = 0,
        ): List<JsonObject> {
            val events = ArrayList<Pair<Long, (Long) -> JsonObject>>()
            val stepAt = if (step == 0) from + 1_000 else from
            if (step == 0) {
                events += from to { seq -> event(phone, seq, from, "session") { put("schema", 2) } }
                events += from + 100 to { seq -> event(phone, seq, from + 100, "clock") { put("offset", 0) } }
            }
            events += stepAt to { seq ->
                event(phone, seq, stepAt, "step") {
                    put("index", step)
                    put("id", if (step == 0) "all_hiders" else "probe")
                    put("revision", 1)
                }
            }
            for (t in stepAt + 500 until to step 1_000) {
                events += t to { seq -> event(phone, seq, t, "tick") { put("n", t) } }
            }
            for (other in phones.filter { it != phone }) {
                for ((i, t) in (stepAt + 250 until to step 500).withIndex()) {
                    events += t to { seq ->
                        event(phone, seq, t, "rx") {
                            put("token", other.radarToken)
                            put("rssi", -60 - i % 5)
                            put("api", "android_le")
                            put("via", "service_data")
                        }
                    }
                }
            }
            return events.sortedBy { it.first }.mapIndexed { index, (_, make) -> make(seqFrom + index) }
        }

        fun event(
            phone: LabJoinResponse,
            seq: Long,
            t: Long,
            kind: String,
            fields: JsonObjectBuilder.() -> Unit,
        ): JsonObject = buildJsonObject {
            put("t", t)
            put("dt", t)
            put("mono", t)
            put("dev", "x")
            put("k", kind)
            put("app", "active")
            put("run", phone.runId.value)
            put("seq", seq)
            fields()
        }

        fun seqOf(event: JsonObject): Long? = event["seq"]?.toString()?.toLongOrNull()

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
