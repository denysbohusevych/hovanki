package app.hovanki.server.lab

import app.hovanki.server.account.AccountKeys
import app.hovanki.server.admin.AuditLog
import app.hovanki.server.admin.Staff
import app.hovanki.server.game.GameException
import app.hovanki.server.game.IdGenerator
import app.hovanki.shared.lab.FieldKinds
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.lab.MarkFields
import app.hovanki.shared.protocol.AdminAction
import app.hovanki.shared.protocol.AdminFieldMarkRequest
import app.hovanki.shared.protocol.AdminLimits
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.LabRunId
import app.hovanki.shared.protocol.LabRunKind
import app.hovanki.shared.protocol.LabRunStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64

/**
 * An organizer's mark in a game's field log (docs/adr/0018-field-test-build.md §6, docs/field-test.md step 6): «the
 * hider was inside the shop here», written by an admin from the page while the game is on. It goes into the run as a
 * `mark` event of the device [MarkFields.STAFF] (a device of the run like the server's, made at the first mark), in the
 * phones' JSONL on the server's clock now, so the report puts it on the timeline with 30 s around it. The admin's
 * name is only in the audit log, written in the same transaction; the run holds the text and nothing about who wrote it.
 *
 * Admins only. The text (1..[TEXT_MAX_LENGTH] characters) is a few words and is also the reason of the audit row,
 * together with [AdminFieldMarkRequest.reason]. At most [MAX_MARKS] to a run.
 */
@Service
class FieldStaffMarks(
    private val repository: LabRunRepository,
    private val audit: AuditLog,
    private val reports: LabReportWriter,
    private val properties: FieldProperties,
    private val ids: IdGenerator,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)
    private val random = SecureRandom()

    fun mark(staff: Staff, id: LabRunId, request: AdminFieldMarkRequest) {
        if (!staff.isAdmin) throw GameException(ErrorCode.FORBIDDEN, "Admins only")
        val text = request.text.replace(CONTROL, " ").trim().take(TEXT_MAX_LENGTH)
        val reason = request.reason.trim()
        if (text.isEmpty() || reason.isEmpty() || reason.length > AdminLimits.REASON_MAX_LENGTH) {
            throw GameException(
                ErrorCode.BAD_REQUEST,
                "A text of 1..$TEXT_MAX_LENGTH characters and a reason of 1..${AdminLimits.REASON_MAX_LENGTH} are required",
            )
        }
        val runId = transactions.execute {
            val now = clock.instant()
            val run = repository.lockRun(id.value)?.takeIf { it.kind == LabRunKind.GAME }
                ?: throw GameException(ErrorCode.NOT_FOUND, "No such game's run")
            val finishedAt = run.finishedAt
            if (run.plan.status == LabRunStatus.FINISHED && finishedAt != null &&
                !now.isBefore(finishedAt + properties.uploadGrace)
            ) {
                throw GameException(ErrorCode.WRONG_STATE, "The run is over", ErrorReason.LAB_RUN_CLOSED)
            }
            val device = repository.devicesOf(run.id).firstOrNull { it.label == MarkFields.STAFF }
                ?: addDevice(run.id, now)
            if (device.events >= MAX_MARKS) {
                throw GameException(ErrorCode.WRONG_STATE, "Too many marks in this game", ErrorReason.LIMIT_REACHED)
            }
            val seq = (device.lastSeq ?: -1) + 1
            val at = now.toEpochMilli()
            val line = line(at, run.id, seq, text)
            val stored = repository.insertChunk(
                deviceId = device.id,
                seqFrom = seq,
                seqTo = seq,
                tFrom = at,
                tTo = at,
                events = 1,
                body = LabChunks.gzip("$line\n".toByteArray(Charsets.UTF_8)),
                receivedAt = now,
            )
            check(stored) { "The staff device's seq $seq was taken" }
            audit.record(
                staff,
                AdminAction.FIELD_MARK,
                now,
                target = "field run ${run.id} of game ${run.gameId}: «$text»",
                reason = reason,
            )
            run.id
        }
        // The mark goes into the report with the next one (live while the game plays, whole when it is over).
        reports.computeSoon(runId)
    }

    private fun addDevice(runId: String, now: Instant): LabDeviceRecord {
        val device = LabDeviceRecord(
            id = ids.gameId().value,
            runId = runId,
            label = MarkFields.STAFF,
            model = MarkFields.STAFF,
            radarToken = "",
            joinedAt = now,
        )
        // Nobody ever holds its token: the server writes its chunks itself.
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        repository.insertDevice(device, AccountKeys.tokenHash(Base64.getUrlEncoder().encodeToString(token)))
        return device
    }

    private fun line(atMillis: Long, runId: String, seq: Long, text: String): JsonObject = buildJsonObject {
        put(LabFields.T, atMillis)
        put(LabFields.DT, atMillis)
        put(LabFields.MONO, atMillis)
        put(LabFields.DEV, MarkFields.STAFF)
        put(LabFields.K, FieldKinds.MARK)
        put(LabFields.SEQ, seq)
        put(LabFields.RUN, runId)
        put(MarkFields.BY, MarkFields.STAFF)
        put(MarkFields.TEXT, text)
    }

    private companion object {
        const val TEXT_MAX_LENGTH = 120
        const val MAX_MARKS = 500
        const val TOKEN_BYTES = 32
        val CONTROL = Regex("\\p{Cntrl}+")
    }
}
