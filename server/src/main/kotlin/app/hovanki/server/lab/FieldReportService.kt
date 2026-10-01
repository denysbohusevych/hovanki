package app.hovanki.server.lab

import app.hovanki.server.admin.AuditLog
import app.hovanki.server.admin.Staff
import app.hovanki.server.game.GameException
import app.hovanki.shared.lab.FieldRawSlice
import app.hovanki.shared.lab.FieldReport
import app.hovanki.shared.lab.FieldReportBuilder
import app.hovanki.shared.lab.FieldReportDevice
import app.hovanki.shared.lab.FieldReportMarkdown
import app.hovanki.shared.lab.FieldReportStream
import app.hovanki.shared.protocol.AdminAction
import app.hovanki.shared.protocol.AdminFieldRawRequest
import app.hovanki.shared.protocol.AdminLimits
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.LabRunId
import app.hovanki.shared.protocol.LabRunKind
import app.hovanki.shared.protocol.LabRunStatus
import app.hovanki.shared.protocol.protocolJson
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.OutputStream
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** An export of a field game for the admin: [fileName] and [contentType] of the download, [writeTo] writes it. */
class FieldExport(val fileName: String, val contentType: String, val writeTo: (OutputStream) -> Unit)

/**
 * The report of a game's field log (docs/adr/0018-field-test-build.md §6, docs/field-test.md step 6) on the server:
 * computed from the chunks of the game's devices, the phones' and the server's own, streaming them through
 * [FieldReportStream] window by window ([FieldProperties.reportWindow]), so a long game's journal is never in the heap.
 *
 * - [store] (on [LabReportWriter]'s thread): while the game runs, the live report, incremental: the chunks that came
 *   since the last look are read into a [FieldReportBuilder] kept in memory per run, in windows of
 *   [FieldProperties.liveWindow] up to [FieldProperties.liveLag] ago; once the run is finished, the whole report from
 *   scratch, and the live state is dropped. Either goes to `lab_reports` as [FieldReport] JSON.
 * - The exports, admins only, with a reason in the audit log: [markdown] (`report.md`), [digest] (`digest.jsonl`,
 *   written to the response as the windows are read) and [raw] (a slice of devices and time). Players are P1…Pn and
 *   there are no coordinates in the first two; the raw logs are the logs (coordinates in `gps`, ADR 0018 §9).
 *
 * Logs only ids and counts.
 */
@Service
class FieldReportService(
    private val repository: LabRunRepository,
    private val audit: AuditLog,
    private val properties: FieldProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The live reports of the runs in play: touched by the report thread only ([store]); [drop] from anywhere. */
    private val live = ConcurrentHashMap<String, Live>()

    // Computing (the report thread)

    /** Computes [run]'s report, live while it runs, whole once it is finished, and stores it. */
    fun store(run: LabRunRecord): FieldReport {
        require(run.kind == LabRunKind.GAME) { "Not a game's run: $run" }
        val report = if (run.plan.status == LabRunStatus.FINISHED) {
            live.remove(run.id)
            compute(run)
        } else {
            liveReport(run)
        }
        val body = protocolJson.encodeToString(FieldReport.serializer(), report)
        repository.upsertReport(run.id, FieldReport.VERSION, clock.instant(), body)
        log.info(
            "Field run {}: {} report of {} players, {} windows",
            run.id,
            if (report.final) "the whole" else "a live",
            report.players.size,
            report.windows,
        )
        return report
    }

    /** Forgets [runId]'s live report (the run deleted). */
    fun drop(runId: String) {
        live.remove(runId)
    }

    /**
     * The whole report of [run], read now from all its chunks; [digest]: where the lines of `digest.jsonl` go as the
     * windows are read (null: none).
     */
    fun compute(run: LabRunRecord, digest: ((List<String>) -> Unit)? = null): FieldReport {
        val devices = repository.devicesOf(run.id)
        val builder = FieldReportBuilder(
            runId = run.id,
            gameId = run.gameId,
            devices = devices.map(::reportDevice),
            options = FieldReportBuilder.Options(maxTechniqueEvents = properties.maxTechniqueEvents),
            digest = digest,
        )
        digest?.invoke(listOf(builder.digestHeader()))
        val stream = FieldReportStream(builder, properties.reportWindow.toMillis(), timeOf(run))
        for (device in devices) {
            stream.add(device.id, device.label, LabChunks.lines(repository.chunksOf(device.id), repository::chunkBody))
        }
        stream.finish()
        problems(stream, builder)
        return builder.report(clock.millis(), final = run.plan.status == LabRunStatus.FINISHED)
    }

    /** The live report: what came since the last look, read on into the run's builder. */
    private fun liveReport(run: LabRunRecord): FieldReport {
        val state = live.getOrPut(run.id) {
            val builder = FieldReportBuilder(
                runId = run.id,
                gameId = run.gameId,
                devices = emptyList(),
                options = FieldReportBuilder.Options(maxTechniqueEvents = properties.liveTechniqueEvents),
            )
            Live(builder, FieldReportStream(builder, properties.liveWindow.toMillis(), timeOf(run)))
        }
        for (device in repository.devicesOf(run.id)) {
            if (state.seqs.putIfAbsent(device.id, Long.MIN_VALUE) == null) state.builder.addDevice(reportDevice(device))
            val after = state.seqs.getValue(device.id)
            val chunks = repository.chunksOf(device.id).filter { it.seqTo > after }
            if (chunks.isEmpty()) continue
            state.stream.feed(device.id, device.label, LabChunks.lines(chunks, repository::chunkBody, after))
            state.seqs[device.id] = maxOf(after, chunks.maxOf { it.seqTo })
        }
        state.stream.advance(clock.millis() - properties.liveLag.toMillis())
        problems(state.stream, state.builder)
        return state.builder.report(clock.millis(), final = false)
    }

    private class Live(val builder: FieldReportBuilder, val stream: FieldReportStream) {
        /** Device → the last `seq` read of it. */
        val seqs = HashMap<String, Long>()
    }

    private fun problems(stream: FieldReportStream, builder: FieldReportBuilder) {
        if (stream.badLines > 0) builder.problem("${stream.badLines} lines that are no events")
        if (stream.outside > 0) builder.problem("${stream.outside} events outside the game's time, left out")
    }

    /** The events a phone's clock put far outside the game are left out: a day around it is plenty. */
    private fun timeOf(run: LabRunRecord): LongRange {
        val from = run.createdAt.minus(SLACK).toEpochMilli()
        val end = run.finishedAt ?: clock.instant()
        return from..end.plus(properties.uploadGrace).plus(SLACK).toEpochMilli()
    }

    // The exports (admins)

    /**
     * `report.md`: the stored report when it is the whole game's, else computed now from the chunks (the game so far).
     * 404: no such game's run.
     */
    fun markdown(staff: Staff, id: LabRunId, reason: String): FieldExport {
        val run = exported(staff, id, reason, "report.md")
        val stored = repository.findReport(run.id)?.let {
            runCatching { protocolJson.decodeFromString(FieldReport.serializer(), it) }.getOrNull()
        }
        val report = stored?.takeIf { it.final && run.plan.status == LabRunStatus.FINISHED } ?: compute(run)
        val text = FieldReportMarkdown.render(report)
        return FieldExport("${name(run)}-report.md", MARKDOWN) { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** `digest.jsonl`, written as the game's logs are read, a window at a time. */
    fun digest(staff: Staff, id: LabRunId, reason: String): FieldExport {
        val run = exported(staff, id, reason, "digest.jsonl")
        return FieldExport("${name(run)}-digest.jsonl", NDJSON) { out ->
            compute(run) { lines ->
                for (line in lines) {
                    out.write(line.toByteArray(Charsets.UTF_8))
                    out.write('\n'.code)
                }
            }
            out.flush()
        }
    }

    /**
     * The raw logs of the game's devices in a zip, all or a slice ([AdminFieldRawRequest]): one JSONL per device
     * (`hovanki-lab-<label>-<deviceId>.jsonl`, as the lab's), its events in the order of `seq`, each once.
     */
    fun raw(staff: Staff, id: LabRunId, request: AdminFieldRawRequest): FieldExport {
        requireAdmin(staff)
        val devices = request.devices.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val slice = try {
            FieldRawSlice(devices, request.fromMillis, request.toMillis)
        } catch (e: IllegalArgumentException) {
            throw GameException(ErrorCode.BAD_REQUEST, e.message ?: "Bad slice")
        }
        val why = validReason(request.reason)
        val run = gameRun(id)
        val taken = repository.devicesOf(run.id).filter { slice.includes(it.id, it.label) }
        val what = buildString {
            append("raw.zip")
            if (slice.devices.isNotEmpty()) append(", ${taken.size} devices")
            if (!slice.wholeTime) append(", ${slice.fromMillis ?: "start"}..${slice.toMillis ?: "end"}")
        }
        audit.record(staff, AdminAction.LAB_RUN_DOWNLOAD, clock.instant(), target = describe(run, what), reason = why)
        return FieldExport("${name(run)}.zip", ZIP) { out ->
            ZipOutputStream(out).use { zip ->
                for (device in taken) {
                    zip.putNextEntry(ZipEntry("hovanki-lab-${device.label}-${device.id}.jsonl"))
                    for (line in LabChunks.lines(repository.chunksOf(device.id), repository::chunkBody)) {
                        if (!slice.keeps(line)) continue
                        zip.write(line.toByteArray(Charsets.UTF_8))
                        zip.write('\n'.code)
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun exported(staff: Staff, id: LabRunId, reason: String, what: String): LabRunRecord {
        requireAdmin(staff)
        val why = validReason(reason)
        val run = gameRun(id)
        audit.record(staff, AdminAction.FIELD_EXPORT, clock.instant(), target = describe(run, what), reason = why)
        return run
    }

    private fun gameRun(id: LabRunId): LabRunRecord =
        repository.findRun(id.value)?.takeIf { it.kind == LabRunKind.GAME }
            ?: throw GameException(ErrorCode.NOT_FOUND, "No such game's run")

    private fun requireAdmin(staff: Staff) {
        if (!staff.isAdmin) throw GameException(ErrorCode.FORBIDDEN, "Admins only")
    }

    private fun validReason(reason: String): String {
        val trimmed = reason.trim()
        if (trimmed.isEmpty() || trimmed.length > AdminLimits.REASON_MAX_LENGTH) {
            throw GameException(
                ErrorCode.BAD_REQUEST,
                "A reason is required: 1..${AdminLimits.REASON_MAX_LENGTH} characters",
            )
        }
        return trimmed
    }

    private companion object {
        val SLACK: Duration = Duration.ofDays(1)
        const val MARKDOWN = "text/markdown; charset=utf-8"
        const val NDJSON = "application/x-ndjson"
        const val ZIP = "application/zip"

        fun reportDevice(device: LabDeviceRecord) =
            FieldReportDevice(device.label, device.id, device.model, device.os, device.build)

        fun name(run: LabRunRecord) = "hovanki-field-${run.gameId ?: run.id}"

        fun describe(run: LabRunRecord, what: String) = "field run ${run.id} of game ${run.gameId}: $what"
    }
}
