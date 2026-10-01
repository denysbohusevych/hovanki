package app.hovanki.server.lab

import app.hovanki.server.account.AccountKeys
import app.hovanki.server.account.UserRepository
import app.hovanki.server.admin.AuditLog
import app.hovanki.server.admin.Staff
import app.hovanki.server.api.AuthenticatedUser
import app.hovanki.server.features.FeatureFlags
import app.hovanki.server.game.GameException
import app.hovanki.server.game.IdGenerator
import app.hovanki.server.ratelimit.RateLimit
import app.hovanki.server.ratelimit.RateLimiter
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.lab.LabJoinCode
import app.hovanki.shared.lab.LabPlanState
import app.hovanki.shared.lab.LabRunPlan
import app.hovanki.shared.lab.LabRunScript
import app.hovanki.shared.lab.LabRunScripts
import app.hovanki.shared.lab.LabSchema
import app.hovanki.shared.protocol.AdminAction
import app.hovanki.shared.protocol.AdminLabAdvanceRequest
import app.hovanki.shared.protocol.AdminLabDevice
import app.hovanki.shared.protocol.AdminLabRun
import app.hovanki.shared.protocol.AdminLabRunRequest
import app.hovanki.shared.protocol.AdminLabRunView
import app.hovanki.shared.protocol.AdminLabRuns
import app.hovanki.shared.protocol.AdminLimits
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.LabEventsResponse
import app.hovanki.shared.protocol.LabJoinRequest
import app.hovanki.shared.protocol.LabJoinResponse
import app.hovanki.shared.protocol.LabRunAction
import app.hovanki.shared.protocol.LabRunId
import app.hovanki.shared.protocol.LabRunKind
import app.hovanki.shared.protocol.LabRunStateView
import app.hovanki.shared.protocol.LabRunStatus
import app.hovanki.shared.protocol.LabUpload
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.qr.QrCode
import app.hovanki.shared.totp.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.asKotlinRandom

/**
 * The device of a lab route's bearer token ([app.hovanki.server.api.LabDeviceArgumentResolver]), in a lab run or in a
 * game's field log ([kind]).
 */
data class LabDeviceRef(
    val deviceId: String,
    val runId: String,
    val label: String,
    val kind: LabRunKind = LabRunKind.LAB,
)

/** An upload's bounds, as the query parameters of [app.hovanki.shared.protocol.ApiRoutes.LAB_EVENTS] carry them. */
data class LabBatchBounds(
    val seqFrom: Long,
    val seqTo: Long,
    val count: Int,
    val tFrom: Long? = null,
    val tTo: Long? = null,
)

/** A run's raw logs for the admin: [fileName] of the zip, [writeTo] writes it, a chunk in memory at a time. */
class LabRawLogs(val fileName: String, val writeTo: (OutputStream) -> Unit)

/**
 * The radio lab's runs on the server (docs/adr/0017-radar-techniques-and-big-run.md §5, docs/radar-run.md step 1).
 *
 * An admin makes a run of a plan of the catalog ([LabRunScripts]); test phones of the debug build join it by its code
 * as one of the plan's labels, follow the plan by the server's clock ([LabRunPlan], the same function on the phones)
 * and upload their lab logs in chunks; the live view ([LabLive]) and, once the run is finished, the report
 * ([LabReportWriter]) come from those logs. No players, no accounts, no positions.
 *
 * Rules: the phone routes exist only while [ServerFeature.RADIO_LAB] is on (404 otherwise); the admin's work whatever
 * the flag (old reports stay readable). A run takes joins for [LabProperties.joinWindow] after it was made and until
 * it finishes, uploads until then too and for [LabProperties.uploadGrace] after its end ([ErrorReason.LAB_RUN_CLOSED]
 * afterwards); at most [LabProperties.maxDevices] devices (a rejoin is a new device) and [LabProperties.maxRunBytes]
 * stored ([ErrorReason.LIMIT_REACHED]). The run's row is locked for every change, so the plan moves one step at a time
 * whoever asks. The timed steps move when somebody looks (a phone's poll, the admin's page): the stored state may lag,
 * never what is answered. Admins only on the admin side, with a reason, written to the audit log in the same
 * transaction as the change. On a test server ([LabProperties.joinStaffOnly]) only staff join, with their account
 * token (docs/adr/0018-field-test-build.md §4.D).
 *
 * A game's field log (docs/adr/0018-field-test-build.md §3, [LabRunKind.GAME]) is opened by [FieldRunService]; its
 * phones upload here like the lab's ([acceptChunk]), behind [ServerFeature.FIELD_LOG] and with the limits of
 * [FieldProperties]. Only its events may say where somebody was ([LabSchema.COORDINATES]): a lab run's chunks are
 * stored without them.
 */
@Service
class LabRunService(
    private val repository: LabRunRepository,
    private val audit: AuditLog,
    private val features: FeatureFlags,
    private val rateLimiter: RateLimiter,
    private val live: LabLive,
    private val reports: LabReportWriter,
    private val properties: LabProperties,
    private val field: FieldProperties,
    private val users: UserRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)
    private val random = SecureRandom()
    private val codeRandom = random.asKotlinRandom()

    // Phones

    /** 404 while the operator has the lab off: the phone routes don't exist then. */
    fun requireEnabled() = requireEnabled(LabRunKind.LAB)

    /** 404 while the operator has the switch of [kind]'s runs off: the lab's RADIO_LAB, the field log's FIELD_LOG. */
    fun requireEnabled(kind: LabRunKind) {
        if (!features.isEnabled(switchOf(kind))) throw GameException(ErrorCode.NOT_FOUND, "Not found")
    }

    /** 404 while neither the lab nor the field log is on: the upload routes don't exist then. */
    fun requireAnyEnabled() {
        if (LabRunKind.entries.none { features.isEnabled(switchOf(it)) }) {
            throw GameException(ErrorCode.NOT_FOUND, "Not found")
        }
    }

    /** The device of a bearer token; null: no such device (or its run was deleted). */
    fun device(token: String): LabDeviceRef? = repository.findDeviceRefByTokenHash(AccountKeys.tokenHash(token))

    /**
     * [user]: the account token the phone sent along, if any; a server that lets only staff join
     * ([LabProperties.joinStaffOnly], a test server whose release-code builds have the lab for staff) refuses anybody
     * else.
     */
    fun join(request: LabJoinRequest, remoteAddr: String, user: AuthenticatedUser? = null): LabJoinResponse {
        requireEnabled()
        if (properties.joinStaffOnly && user?.let { users.findById(it.userId)?.role?.isStaff } != true) {
            throw GameException(ErrorCode.FORBIDDEN, "Staff only: log in with a staff account")
        }
        rateLimiter.acquire(RateLimit.LAB_JOIN_PER_IP, remoteAddr)
        val code = LabJoinCode.normalize(request.code) ?: throw noSuchRun()
        val label = request.label.trim()
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(TOKEN_BYTES))
        val now = clock.instant()
        return locked({ repository.lockRunByCode(code) }, now) { locked ->
            val run = locked.run
            // A game's run has no code to join by; this only guards the rule.
            if (run.kind != LabRunKind.LAB) throw noSuchRun()
            if (run.plan.status == LabRunStatus.FINISHED || !now.isBefore(run.createdAt + properties.joinWindow)) {
                throw closed()
            }
            val script = locked.script ?: throw noScript()
            if (label !in script.labels) {
                throw GameException(ErrorCode.BAD_REQUEST, "No such label in this run: ${script.labels}")
            }
            val devices = repository.devicesOf(run.id)
            if (devices.size >= properties.maxDevices) {
                throw GameException(ErrorCode.WRONG_STATE, "The run is full", ErrorReason.LIMIT_REACHED)
            }
            val taken = devices.map { it.radarToken }.toSet()
            val radarToken = generateSequence { randomBytes(RADAR_TOKEN_BYTES).toHex() }.first { it !in taken }
            val device = LabDeviceRecord(
                id = ids.gameId().value,
                runId = run.id,
                label = label,
                model = request.model?.clip(),
                os = request.os?.clip(),
                build = request.build?.clip(),
                commit = request.commit?.clip(),
                capabilities = request.capabilities,
                radarToken = radarToken,
                joinedAt = now,
            )
            repository.insertDevice(device, AccountKeys.tokenHash(token))
            LabJoinResponse(
                runId = LabRunId(run.id),
                deviceId = device.id,
                token = token,
                radarToken = radarToken,
                salt = run.salt,
                scenarioId = script.id,
                scenarioVersion = script.version,
                labels = script.labels,
                state = view(run, now),
            )
        }
    }

    /** Where the run is now; the timed steps due by now are done first. A game's run has no plan: 404. */
    fun state(device: LabDeviceRef): LabRunStateView {
        requireEnabled()
        if (device.kind != LabRunKind.LAB) throw noSuchRun()
        val now = clock.instant()
        return locked({ repository.lockRun(device.runId) }, now) { view(it.run, now) }
    }

    /** A control action from a phone: the same as the admin's, without the audit log (the phones are the lab's). */
    fun advance(device: LabDeviceRef, action: LabRunAction): LabRunStateView {
        requireEnabled()
        if (device.kind != LabRunKind.LAB) throw noSuchRun()
        val now = clock.instant()
        return locked({ repository.lockRun(device.runId) }, now) { locked ->
            val script = locked.script ?: throw noScript()
            locked.save(LabRunPlan.apply(script, locked.run.plan, action, now.toEpochMilli()))
            view(locked.run, now)
        }
    }

    /**
     * Stores a chunk of [device]'s log: the JSONL [body] (gzipped when [gzipped]; [declaredLength] its
     * `Content-Length`, -1 unknown) of the events [bounds]. The body is read only after the rate limit, and never more
     * than [LabProperties.maxChunkBytes] of it. A chunk whose first event is stored already (a retried upload) is not
     * stored again; the answer then acknowledges only what the server has, so a retry that grew meanwhile sends its
     * new events again from there. Every line must be an event of the batch (`k`, `dt`, and `seq` within the bounds);
     * the server keeps it gzipped. A lab run's lines lose their coordinates ([LabSchema.withoutCoordinates]): only a
     * game's field log keeps them. A game's run has the limits of [FieldProperties] and its own switch.
     */
    fun acceptChunk(
        device: LabDeviceRef,
        bounds: LabBatchBounds,
        body: InputStream,
        declaredLength: Long,
        gzipped: Boolean,
    ): LabEventsResponse {
        requireEnabled(device.kind)
        rateLimiter.acquire(RateLimit.LAB_EVENTS_PER_DEVICE, device.deviceId)
        if (bounds.seqFrom < 0 || bounds.seqTo < bounds.seqFrom || bounds.count !in 1..LabUpload.MAX_EVENTS) {
            throw GameException(ErrorCode.BAD_REQUEST, "Bad batch bounds")
        }
        val limit = properties.maxChunkBytes.toBytes()
        if (declaredLength > limit) throw tooBig("The upload is too big")
        val received = LabChunks.read(body, limit) ?: throw tooBig("The upload is too big")
        val plain = if (gzipped) {
            try {
                LabChunks.gunzip(received, limit) ?: throw tooBig("The upload is too big")
            } catch (e: IOException) {
                throw GameException(ErrorCode.BAD_REQUEST, "The body is no gzip")
            }
        } else {
            received
        }
        val parsed = events(plain, bounds)
        // Only a game's field log may say where somebody was: a lab run keeps its lines without it.
        val stripped = device.kind == LabRunKind.LAB && parsed.any(LabSchema::hasCoordinates)
        val events = if (stripped) parsed.map(LabSchema::withoutCoordinates) else parsed
        val stored = when {
            stripped -> LabChunks.gzip(events.joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8))
            gzipped -> received
            else -> LabChunks.gzip(plain)
        }
        val maxRunBytes = if (device.kind == LabRunKind.GAME) field.maxRunBytes else properties.maxRunBytes
        val now = clock.instant()
        var inserted = false
        var finished = false
        val acked = locked({ repository.lockRun(device.runId) }, now) { locked ->
            if (!uploadOpen(locked.run, now)) throw closed()
            finished = locked.run.plan.status == LabRunStatus.FINISHED
            if (!repository.chunkExists(device.deviceId, bounds.seqFrom)) {
                if (repository.runBytes(device.runId) + stored.size > maxRunBytes.toBytes()) {
                    throw tooBig("The run has all the logs it may have")
                }
                inserted = repository.insertChunk(
                    deviceId = device.deviceId,
                    seqFrom = bounds.seqFrom,
                    seqTo = bounds.seqTo,
                    tFrom = bounds.tFrom,
                    tTo = bounds.tTo,
                    events = events.size,
                    body = stored,
                    receivedAt = now,
                )
                // Under the run's lock: a finish or a delete drops the live view after this, never before.
                if (inserted && !finished) live.accept(device.runId, device.deviceId, events, now.toEpochMilli())
            }
            // What the server has: a retry cut differently than the stored chunk goes on after it.
            repository.lastSeq(device.deviceId) ?: (bounds.seqFrom - 1)
        }
        // A finished run's report is computed again with its last logs; a game's has none yet (field-test.md step 6).
        if (inserted && finished && device.kind == LabRunKind.LAB) reports.compute(device.runId)
        return LabEventsResponse(acked, now.toEpochMilli())
    }

    // Admins

    fun list(staff: Staff): AdminLabRuns {
        requireAdmin(staff)
        val now = clock.millis()
        val runs = repository.listRuns(LIST_SIZE).map { row ->
            // The timed steps that ended since anybody looked, as the next look will save them.
            val script = scriptOf(row.run)
            val plan = script?.let { LabRunPlan.advanceByTime(it, row.run.plan, now) } ?: row.run.plan
            admin(row.run.copy(plan = plan), row.devices, row.bytes, row.reportReady)
        }
        return AdminLabRuns(runs, LabRunScripts.ALL.map { it.summary() })
    }

    fun create(staff: Staff, request: AdminLabRunRequest): AdminLabRun {
        requireAdmin(staff)
        val reason = validReason(request.reason)
        val title = request.title.trim()
        if (title.isEmpty() || title.length > TITLE_MAX_LENGTH) {
            throw GameException(ErrorCode.BAD_REQUEST, "A title is required: 1..$TITLE_MAX_LENGTH characters")
        }
        val script = LabRunScripts.byId(request.scenarioId)
            ?: throw GameException(ErrorCode.BAD_REQUEST, "No such scenario: ${request.scenarioId}")
        val now = clock.instant()
        repeat(CODE_ATTEMPTS) {
            val code = LabJoinCode.random(codeRandom)
            if (repository.codeTaken(code)) return@repeat
            val run = LabRunRecord(
                id = ids.gameId().value,
                code = code,
                title = title,
                scenarioId = script.id,
                scenarioVersion = script.version,
                plan = LabPlanState(),
                createdByName = staff.nickname,
                createdAt = now,
                salt = randomBytes(SALT_BYTES).toHex(),
            )
            try {
                transactions.executeWithoutResult {
                    repository.insertRun(run)
                    audit.record(staff, AdminAction.LAB_RUN_CREATE, now, target = describe(run), reason = reason)
                }
            } catch (e: DuplicateKeyException) {
                return@repeat
            }
            return admin(run, devices = 0, bytes = 0, reportReady = false)
        }
        throw GameException(ErrorCode.INTERNAL, "No free code found")
    }

    /** The console: the run, where it is, its plan, its devices and the live view. */
    fun adminView(staff: Staff, id: LabRunId): AdminLabRunView {
        requireAdmin(staff)
        val now = clock.instant()
        val run = locked({ repository.lockRun(id.value) }, now) { it.run }
        return adminView(run, now)
    }

    /** NEXT / REPEAT / PAUSE / RESUME from the console; an action that changes nothing is not logged. */
    fun adminAdvance(staff: Staff, id: LabRunId, request: AdminLabAdvanceRequest): AdminLabRunView {
        requireAdmin(staff)
        val reason = validReason(request.reason)
        val now = clock.instant()
        val run = locked({ repository.lockRun(id.value) }, now) { locked ->
            // A game's run has no plan.
            val script = locked.script ?: throw noScript()
            val before = locked.run.plan
            val after = LabRunPlan.apply(script, before, request.action, now.toEpochMilli())
            if (after != before) {
                locked.save(after)
                val step = "step ${after.stepIndex + 1}, ${after.status}"
                val target = "${describe(locked.run)}: ${request.action} → $step"
                audit.record(staff, AdminAction.LAB_RUN_CONTROL, now, target = target, reason = reason)
            }
            locked.run
        }
        return adminView(run, now)
    }

    /**
     * Ends the run where it is and computes its report (again, for a run that ended by its plan). A game's run ends with
     * its game ([FieldRunService.closeRunsOfGoneGames]), not here.
     */
    fun finish(staff: Staff, id: LabRunId, reason: String): AdminLabRunView {
        requireAdmin(staff)
        val why = validReason(reason)
        val now = clock.instant()
        val run = locked({ repository.lockRun(id.value) }, now) { locked ->
            if (locked.run.kind != LabRunKind.LAB) throw noScript()
            locked.save(LabRunPlan.finish(locked.run.plan))
            val target = "${describe(locked.run)}: finish"
            audit.record(staff, AdminAction.LAB_RUN_CONTROL, now, target = target, reason = why)
            locked.run
        }
        live.drop(run.id)
        reports.compute(run.id)
        return adminView(run, now)
    }

    /** The report's JSON (`app.hovanki.shared.lab.LabReport`); 404 until it is computed. */
    fun report(staff: Staff, id: LabRunId): String {
        requireAdmin(staff)
        repository.findRun(id.value) ?: throw noSuchRun()
        return repository.findReport(id.value) ?: throw GameException(ErrorCode.NOT_FOUND, "No report yet")
    }

    /**
     * Every device's log as it was uploaded, one JSONL file per device in a zip:
     * `hovanki-lab-<label>-<deviceId>.jsonl`, its events in the order of `seq`, each once.
     */
    fun raw(staff: Staff, id: LabRunId, reason: String): LabRawLogs {
        requireAdmin(staff)
        val why = validReason(reason)
        val run = repository.findRun(id.value) ?: throw noSuchRun()
        val devices = repository.devicesOf(run.id)
        audit.record(staff, AdminAction.LAB_RUN_DOWNLOAD, clock.instant(), target = describe(run), reason = why)
        val name = if (run.kind == LabRunKind.GAME) "hovanki-field-${run.gameId}" else "hovanki-lab-${run.code}"
        return LabRawLogs("$name.zip") { out ->
            ZipOutputStream(out).use { zip ->
                for (device in devices) {
                    zip.putNextEntry(ZipEntry("hovanki-lab-${device.label}-${device.id}.jsonl"))
                    LabChunks.write(repository.chunksOf(device.id), repository::chunkBody, zip)
                    zip.closeEntry()
                }
            }
        }
    }

    /** The run with its devices, logs and report. */
    fun delete(staff: Staff, id: LabRunId, reason: String) {
        requireAdmin(staff)
        val why = validReason(reason)
        transactions.executeWithoutResult {
            val run = repository.lockRun(id.value) ?: throw noSuchRun()
            repository.deleteRun(run.id)
            audit.record(staff, AdminAction.LAB_RUN_DELETE, clock.instant(), target = describe(run), reason = why)
        }
        live.drop(id.value)
    }

    // The run locked

    /** The run of a transaction, its row locked; [save] changes its plan (and its start and end with it). */
    private inner class Locked(var run: LabRunRecord, val script: LabRunScript?, private val now: Instant) {
        fun save(plan: LabPlanState) {
            if (plan == run.plan) return
            run = run.copy(
                plan = plan,
                startedAt = run.startedAt ?: now.takeIf { plan.status != LabRunStatus.CREATED },
                finishedAt = run.finishedAt ?: now.takeIf { plan.status == LabRunStatus.FINISHED },
            )
            repository.updatePlan(run)
        }
    }

    /**
     * Runs [block] in a transaction on the run [find] locks, its timed steps due by [now] done and saved first. A run
     * that finished meanwhile gets its report and loses its live view after the commit.
     */
    private fun <T : Any> locked(find: () -> LabRunRecord?, now: Instant, block: (Locked) -> T): T {
        var finishedRun: String? = null
        val result = transactions.execute {
            val run = find() ?: throw noSuchRun()
            val locked = Locked(run, scriptOf(run), now)
            locked.script?.let { locked.save(LabRunPlan.advanceByTime(it, run.plan, now.toEpochMilli())) }
            val value = block(locked)
            if (run.plan.status != LabRunStatus.FINISHED && locked.run.plan.status == LabRunStatus.FINISHED) {
                finishedRun = run.id
            }
            value
        }
        finishedRun?.let { id ->
            live.drop(id)
            reports.compute(id)
        }
        return checkNotNull(result)
    }

    private fun uploadOpen(run: LabRunRecord, now: Instant): Boolean {
        val game = run.kind == LabRunKind.GAME
        val joinWindow = if (game) field.joinWindow else properties.joinWindow
        val uploadGrace = if (game) field.uploadGrace else properties.uploadGrace
        if (run.plan.status != LabRunStatus.FINISHED) return now.isBefore(run.createdAt + joinWindow)
        return now.isBefore((run.finishedAt ?: run.createdAt) + uploadGrace)
    }

    // Views

    private fun view(run: LabRunRecord, now: Instant): LabRunStateView =
        LabRunPlan.toView(run.plan, LabRunId(run.id), now.toEpochMilli())

    private fun adminView(run: LabRunRecord, now: Instant): AdminLabRunView {
        val script = scriptOf(run)
        val devices = repository.devicesOf(run.id)
        val bytes = devices.sumOf { it.bytes }
        return AdminLabRunView(
            run = admin(run, devices.size, bytes, repository.reportExists(run.id)),
            state = view(run, now),
            labels = script?.labels ?: devices.map { it.label }.distinct(),
            steps = script?.stepViews().orEmpty(),
            devices = devices.map { device ->
                AdminLabDevice(
                    id = device.id,
                    label = device.label,
                    model = device.model,
                    os = device.os,
                    build = device.build,
                    commit = device.commit,
                    capabilities = device.capabilities,
                    joinedAtMillis = device.joinedAt.toEpochMilli(),
                    lastChunkAtMillis = device.lastChunkAt?.toEpochMilli(),
                    lastSeq = device.lastSeq,
                    bytes = device.bytes,
                    events = device.events,
                    consentAtMillis = device.consentAt?.toEpochMilli(),
                )
            },
            live = live.view(run.id, devices, now.toEpochMilli()),
        )
    }

    private fun admin(run: LabRunRecord, devices: Int, bytes: Long, reportReady: Boolean): AdminLabRun {
        // A game's run has no code to join by: no QR, no code.
        val lab = run.kind == LabRunKind.LAB
        val rows = if (!lab) {
            emptyList()
        } else {
            val qr = QrCode.encode(LabJoinCode.qrPayload(run.code))
            (0 until qr.size).map { y ->
                buildString(qr.size) { for (x in 0 until qr.size) append(if (qr[x, y]) '1' else '0') }
            }
        }
        return AdminLabRun(
            id = LabRunId(run.id),
            code = if (lab) run.code else "",
            kind = run.kind,
            gameId = run.gameId,
            qr = rows,
            title = run.title,
            scenarioId = run.scenarioId,
            scenarioVersion = run.scenarioVersion,
            status = run.plan.status,
            createdByName = run.createdByName.orEmpty(),
            createdAtMillis = run.createdAt.toEpochMilli(),
            startedAtMillis = run.startedAt?.toEpochMilli(),
            finishedAtMillis = run.finishedAt?.toEpochMilli(),
            devices = devices,
            bytes = bytes,
            reportReady = reportReady,
        )
    }

    // Checks

    /** The lines of an upload as events; each must have a kind and its `seq` within the batch's bounds. */
    private fun events(plain: ByteArray, bounds: LabBatchBounds): List<JsonObject> {
        val lines = plain.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty() || lines.size > LabUpload.MAX_EVENTS) {
            throw GameException(ErrorCode.BAD_REQUEST, "1..${LabUpload.MAX_EVENTS} events per upload")
        }
        return lines.mapIndexed { index, line ->
            val event = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            val kind = event?.get(LabFields.K) as? JsonPrimitive
            val seq = (event?.get(LabFields.SEQ) as? JsonPrimitive)?.longOrNull
            // The times are numbers, whatever a clock said: the report and the live view drop the implausible ones.
            val dt = (event?.get(LabFields.DT) as? JsonPrimitive)?.longOrNull
            val t = event?.get(LabFields.T)
            val timed = dt != null && (t == null || (t as? JsonPrimitive)?.longOrNull != null)
            if (event == null || kind?.isString != true || seq == null || seq !in bounds.seqFrom..bounds.seqTo ||
                !timed
            ) {
                throw GameException(ErrorCode.BAD_REQUEST, "Line ${index + 1} is no event of this batch")
            }
            event
        }
    }

    private fun scriptOf(run: LabRunRecord): LabRunScript? =
        LabRunScripts.byId(run.scenarioId)?.takeIf { it.version == run.scenarioVersion }

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

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    private companion object {
        const val TOKEN_BYTES = 32
        const val RADAR_TOKEN_BYTES = 4
        const val SALT_BYTES = 16
        const val CODE_ATTEMPTS = 5
        const val LIST_SIZE = 100
        const val TITLE_MAX_LENGTH = 100
        const val TEXT_MAX_LENGTH = 120

        fun describe(run: LabRunRecord) = "lab run ${run.id} «${run.title}»"

        /** The operator's switch of [kind]'s runs. */
        fun switchOf(kind: LabRunKind): ServerFeature = when (kind) {
            LabRunKind.LAB -> ServerFeature.RADIO_LAB
            LabRunKind.GAME -> ServerFeature.FIELD_LOG
        }

        fun String.clip(): String = trim().take(TEXT_MAX_LENGTH)

        fun noSuchRun() = GameException(ErrorCode.NOT_FOUND, "No such run")

        fun noScript() = GameException(ErrorCode.WRONG_STATE, "The run's plan is not in this server's catalog")

        fun closed() = GameException(ErrorCode.WRONG_STATE, "The run is over", ErrorReason.LAB_RUN_CLOSED)

        fun tooBig(message: String) = GameException(ErrorCode.WRONG_STATE, message, ErrorReason.LIMIT_REACHED)
    }
}
