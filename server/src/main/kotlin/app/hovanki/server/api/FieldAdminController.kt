package app.hovanki.server.api

import app.hovanki.server.admin.Staff
import app.hovanki.server.lab.FieldExport
import app.hovanki.server.lab.FieldReportService
import app.hovanki.server.lab.FieldStaffMarks
import app.hovanki.server.lab.LabRunService
import app.hovanki.shared.protocol.AdminFieldGameView
import app.hovanki.shared.protocol.AdminFieldGames
import app.hovanki.shared.protocol.AdminFieldMarkRequest
import app.hovanki.shared.protocol.AdminFieldRawRequest
import app.hovanki.shared.protocol.AdminReasonRequest
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.LabRunId
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * The field games in the admin (docs/adr/0018-field-test-build.md §6, docs/field-test.md step 6), admins only:
 * the list, a game's run with its live view and its report, an organizer's mark, the delete, and the exports
 * `report.md`, `digest.jsonl` and `raw.zip`. Everything that changes something or takes data out carries a reason that
 * goes to the audit log (POST/DELETE: the reason is in the body); the exports are written straight to the response, so
 * a long game's logs never sit in the heap. The rules are in [LabRunService], [FieldStaffMarks] and
 * [FieldReportService].
 */
@RestController
class FieldAdminController(
    private val labs: LabRunService,
    private val marks: FieldStaffMarks,
    private val reports: FieldReportService,
) {
    @GetMapping(ApiRoutes.ADMIN_FIELD_GAMES)
    fun games(staff: Staff): AdminFieldGames = labs.fieldGames(staff)

    @GetMapping(ApiRoutes.ADMIN_FIELD_GAME)
    fun game(staff: Staff, @PathVariable runId: String): AdminFieldGameView = labs.fieldGame(staff, LabRunId(runId))

    /** The stored `FieldReport` JSON as it is; 404 until it is computed. */
    @GetMapping(ApiRoutes.ADMIN_FIELD_GAME_REPORT)
    fun report(staff: Staff, @PathVariable runId: String): ResponseEntity<String> =
        ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(labs.fieldReport(staff, LabRunId(runId)))

    @PostMapping(ApiRoutes.ADMIN_FIELD_GAME_MARKS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun mark(staff: Staff, @PathVariable runId: String, @RequestBody request: AdminFieldMarkRequest) =
        marks.mark(staff, LabRunId(runId), request)

    @DeleteMapping(ApiRoutes.ADMIN_FIELD_GAME)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(staff: Staff, @PathVariable runId: String, @RequestBody request: AdminReasonRequest) =
        labs.deleteFieldGame(staff, LabRunId(runId), request.reason)

    @PostMapping(ApiRoutes.ADMIN_FIELD_GAME_REPORT_MD)
    fun markdown(
        staff: Staff,
        @PathVariable runId: String,
        @RequestBody request: AdminReasonRequest,
        response: HttpServletResponse,
    ) = send(reports.markdown(staff, LabRunId(runId), request.reason), response)

    @PostMapping(ApiRoutes.ADMIN_FIELD_GAME_DIGEST)
    fun digest(
        staff: Staff,
        @PathVariable runId: String,
        @RequestBody request: AdminReasonRequest,
        response: HttpServletResponse,
    ) = send(reports.digest(staff, LabRunId(runId), request.reason), response)

    @PostMapping(ApiRoutes.ADMIN_FIELD_GAME_RAW)
    fun raw(
        staff: Staff,
        @PathVariable runId: String,
        @RequestBody request: AdminFieldRawRequest,
        response: HttpServletResponse,
    ) = send(reports.raw(staff, LabRunId(runId), request), response)

    private fun send(export: FieldExport, response: HttpServletResponse) {
        response.status = HttpStatus.OK.value()
        response.contentType = export.contentType
        response.setHeader(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(export.fileName).build().toString(),
        )
        export.writeTo(response.outputStream)
        response.flushBuffer()
    }
}
