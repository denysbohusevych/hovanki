package app.hovanki.server.api

import app.hovanki.server.MutableClock
import app.hovanki.server.account.AccountTestConfig
import app.hovanki.server.features.FeatureFlags
import app.hovanki.server.lab.LabReportWriter
import app.hovanki.server.mail.EmailSender
import app.hovanki.server.mail.RecordingEmailSender
import app.hovanki.shared.lab.LabReport
import app.hovanki.shared.protocol.AdminLabRun
import app.hovanki.shared.protocol.AdminLabRunRequest
import app.hovanki.shared.protocol.AdminLabRunView
import app.hovanki.shared.protocol.AdminReasonRequest
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.LabJoinRequest
import app.hovanki.shared.protocol.LabJoinResponse
import app.hovanki.shared.protocol.LabUpload
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.UserRole
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
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A run of the radio lab takes only so many bytes, and its report reads only so many events (set low here). */
@SpringBootTest(
    properties = [
        "hovanki.lab.max-run-bytes=4KB",
        "hovanki.lab.max-chunk-bytes=2KB",
        "hovanki.lab.max-report-events=5",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestConfig::class)
class LabLimitsApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired emailSender: EmailSender,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val features: FeatureFlags,
    @Autowired private val reports: LabReportWriter,
) {
    private val admin = AdminTestClient(mvc, emailSender as RecordingEmailSender, clock, jdbc)

    @Test
    fun aRunTakesOnlySoManyBytes() {
        features.set(ServerFeature.RADIO_LAB, true, "test", clock.instant())
        val staff = admin.staff(UserRole.ADMIN)
        val run = admin.post(ApiRoutes.ADMIN_LAB_RUNS, AdminLabRunRequest("Limits", "e2e", "a test"), staff)
            .ok<AdminLabRun>()
        val joined = mvc.post(ApiRoutes.LAB_JOIN) {
            contentType = MediaType.APPLICATION_JSON
            content = LabJoinRequest(run.code, "A").asJson()
        }.andReturn().response
        val phone = TestResponse(joined.status, joined.contentAsString).ok<LabJoinResponse>()

        // More than an upload may unpack to.
        val big = (1L..40L).map { seq -> line(seq, noise = 100) }
        upload(phone, big, 1, 40).error(409, ErrorCode.WRONG_STATE, ErrorReason.LIMIT_REACHED)

        // Random text barely packs: ~1 KB a chunk, a few go into the run's 4 KB.
        var seq = 1L
        val answers = ArrayList<TestResponse>()
        while (answers.size < 10 && answers.lastOrNull()?.status != 409) {
            answers += upload(phone, (seq until seq + 10).map { line(it, noise = 80) }, seq, seq + 9)
            seq += 10
        }
        assertTrue(answers.count { it.status == 200 } >= 2, "${answers.map { it.status }}")
        answers.last().error(409, ErrorCode.WRONG_STATE, ErrorReason.LIMIT_REACHED)
        val stored = jdbc.sql("SELECT coalesce(sum(bytes), 0) FROM lab_devices WHERE id = :id")
            .param("id", phone.deviceId)
            .query(Long::class.java)
            .single()
        assertTrue(stored in 1..4 * 1024, "$stored")

        // The report of a run with more events than it reads: the devices, and why there is nothing else.
        admin.post(ApiRoutes.adminLabRun(run.id, "finish"), AdminReasonRequest("done"), staff).ok<AdminLabRunView>()
        reports.awaitIdle()
        val report = admin.get(ApiRoutes.adminLabRun(run.id, "report"), staff).ok<LabReport>()
        assertEquals(listOf("A"), report.devices.map { it.label })
        assertEquals(emptyList(), report.steps)
        assertContains(report.problems.single(), "more than the server's report reads (5)")
    }

    private fun line(seq: Long, noise: Int): String {
        val text = (1..noise).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")
        return """{"t":$seq,"dt":$seq,"k":"note","seq":$seq,"text":"$text"}"""
    }

    private fun upload(phone: LabJoinResponse, lines: List<String>, seqFrom: Long, seqTo: Long): TestResponse {
        val response = mvc.post(ApiRoutes.labEvents(phone.runId)) {
            contentType = MediaType.parseMediaType(LabUpload.CONTENT_TYPE)
            content = lines.joinToString("\n").toByteArray()
            header(HttpHeaders.AUTHORIZATION, "${ApiRoutes.AUTH_SCHEME} ${phone.token}")
            param(LabUpload.PARAM_SEQ_FROM, seqFrom.toString())
            param(LabUpload.PARAM_SEQ_TO, seqTo.toString())
            param(LabUpload.PARAM_COUNT, lines.size.toString())
        }.andReturn().response
        return TestResponse(response.status, response.getContentAsString(Charsets.UTF_8))
    }

    private companion object {
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    }
}
