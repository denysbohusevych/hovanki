package app.hovanki.server.api

import app.hovanki.server.admin.Staff
import app.hovanki.server.lab.LabRunService
import app.hovanki.shared.protocol.AdminLabAdvanceRequest
import app.hovanki.shared.protocol.AdminLabRun
import app.hovanki.shared.protocol.AdminLabRunRequest
import app.hovanki.shared.protocol.AdminLabRunView
import app.hovanki.shared.protocol.AdminLabRuns
import app.hovanki.shared.protocol.AdminReasonRequest
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.LabRunId
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * The radio lab in the admin (docs/adr/0017-radar-techniques-and-big-run.md §5, docs/adr/0008-admin.md): runs, the
 * console, the live view, the report and the raw logs. Admins only, whether the lab is on or off; the rules are in
 * [LabRunService].
 */
@RestController
class LabAdminController(private val labs: LabRunService) {
    @GetMapping(ApiRoutes.ADMIN_LAB_RUNS)
    fun runs(staff: Staff): AdminLabRuns = labs.list(staff)

    @PostMapping(ApiRoutes.ADMIN_LAB_RUNS)
    fun create(staff: Staff, @RequestBody request: AdminLabRunRequest): AdminLabRun = labs.create(staff, request)

    @GetMapping(ApiRoutes.ADMIN_LAB_RUN)
    fun run(staff: Staff, @PathVariable runId: String): AdminLabRunView = labs.adminView(staff, LabRunId(runId))

    @PostMapping(ApiRoutes.ADMIN_LAB_RUN_ADVANCE)
    fun advance(
        staff: Staff,
        @PathVariable runId: String,
        @RequestBody request: AdminLabAdvanceRequest,
    ): AdminLabRunView = labs.adminAdvance(staff, LabRunId(runId), request)

    @PostMapping(ApiRoutes.ADMIN_LAB_RUN_FINISH)
    fun finish(staff: Staff, @PathVariable runId: String, @RequestBody request: AdminReasonRequest): AdminLabRunView =
        labs.finish(staff, LabRunId(runId), request.reason)

    /** The stored `LabReport` JSON as it is; 404 until it is computed. */
    @GetMapping(ApiRoutes.ADMIN_LAB_RUN_REPORT)
    fun report(staff: Staff, @PathVariable runId: String): ResponseEntity<String> =
        ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(labs.report(staff, LabRunId(runId)))

    /**
     * A zip with one JSONL file per device; POST: it needs a reason, and the audit log gets it. Written straight to
     * the response, a chunk in memory at a time: a run's logs can be far bigger than the heap.
     */
    @PostMapping(ApiRoutes.ADMIN_LAB_RUN_RAW)
    fun raw(
        staff: Staff,
        @PathVariable runId: String,
        @RequestBody request: AdminReasonRequest,
        response: HttpServletResponse,
    ) {
        val logs = labs.raw(staff, LabRunId(runId), request.reason)
        response.status = HttpStatus.OK.value()
        response.contentType = ZIP
        response.setHeader(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(logs.fileName).build().toString(),
        )
        logs.writeTo(response.outputStream)
        response.flushBuffer()
    }

    @PostMapping(ApiRoutes.ADMIN_LAB_RUN_DELETE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(staff: Staff, @PathVariable runId: String, @RequestBody request: AdminReasonRequest) =
        labs.delete(staff, LabRunId(runId), request.reason)

    private companion object {
        const val ZIP = "application/zip"
    }
}
