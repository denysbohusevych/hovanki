package app.hovanki.server.api

import app.hovanki.server.admin.Staff
import app.hovanki.server.lab.FieldExport
import app.hovanki.server.lab.FieldReportService
import app.hovanki.shared.protocol.AdminFieldRawRequest
import app.hovanki.shared.protocol.AdminReasonRequest
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.LabRunId
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * The exports of a field game (docs/adr/0018-field-test-build.md §6, docs/field-test.md step 6): `report.md`,
 * `digest.jsonl` and `raw.zip`. Admins only, each with a reason in the audit log (POST: the reason is in the body);
 * written straight to the response, so a long game's logs never sit in the heap. The rules are in
 * [FieldReportService]; the game's run, its live view and its stored report are the lab's routes.
 */
@RestController
class FieldAdminController(private val reports: FieldReportService) {
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
