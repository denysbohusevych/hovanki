package app.hovanki.server.lab

import app.hovanki.server.db.getInstant
import app.hovanki.server.db.getInstantOrNull
import app.hovanki.server.db.toTimestamptz
import app.hovanki.shared.lab.LabPlanState
import app.hovanki.shared.protocol.LabCapabilities
import app.hovanki.shared.protocol.LabRunKind
import app.hovanki.shared.protocol.LabRunStatus
import app.hovanki.shared.protocol.protocolJson
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

/** A row of `lab_runs`: a run of the radio lab and where its plan is ([LabPlanState], server times). */
data class LabRunRecord(
    val id: String,
    val code: String,
    val title: String,
    val scenarioId: String,
    val scenarioVersion: Int,
    val plan: LabPlanState,
    /** The admin's nickname then; null a year after (DataRetention, as the audit log). */
    val createdByName: String?,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    /** Hex; the phones of the run hash the peers' ids in their logs with it. */
    val salt: String,
    /** A lab run, or a game's field log (docs/adr/0018-field-test-build.md §3.1): then [gameId], no plan, no code. */
    val kind: LabRunKind = LabRunKind.LAB,
    val gameId: String? = null,
) {
    override fun toString(): String = "LabRun($id, $kind, ${plan.status})"
}

/** A run's row with what the admin's list shows next to it. */
data class LabRunRow(val run: LabRunRecord, val devices: Int, val bytes: Long, val reportReady: Boolean)

/** A row of `lab_devices`: one phone (or the Mac) in a run, as it joined. Never the device token, only its hash. */
data class LabDeviceRecord(
    val id: String,
    val runId: String,
    val label: String,
    val model: String? = null,
    val os: String? = null,
    val build: String? = null,
    val commit: String? = null,
    val capabilities: LabCapabilities = LabCapabilities(),
    val radarToken: String,
    val joinedAt: Instant,
    val lastChunkAt: Instant? = null,
    val lastSeq: Long? = null,
    /** Stored (gzip) bytes of its chunks. */
    val bytes: Long = 0,
    val events: Long = 0,
    /** A field log's device: its player's account (null: a guest); the device goes with the account. */
    val userId: String? = null,
    /** A field log's device: when its tester agreed (docs/adr/0018-field-test-build.md §3.4). */
    val consentAt: Instant? = null,
) {
    // Never the radar token in logs.
    override fun toString(): String = "LabDevice($id, $label)"
}

/** A chunk of a device's log: the events [seqFrom]..[seqTo]; its body (gzipped JSONL) by [LabRunRepository.chunkBody]. */
class LabChunk(val id: Long, val seqFrom: Long, val seqTo: Long, val events: Int)

/** `lab_runs`, `lab_devices`, `lab_chunks` and `lab_reports`. Times are passed in (the injected Clock). */
@Repository
class LabRunRepository(private val jdbc: JdbcClient) {
    // Runs

    fun insertRun(run: LabRunRecord) {
        insertRunSql(run, onConflict = "").update()
    }

    /**
     * A game's field run, unless the game has one already (another phone of it joined at the same moment): true, this
     * one was stored.
     */
    fun insertGameRunIfAbsent(run: LabRunRecord): Boolean {
        require(run.kind == LabRunKind.GAME && run.gameId != null) { "Not a game's run: $run" }
        return insertRunSql(run, onConflict = "ON CONFLICT DO NOTHING").update() > 0
    }

    private fun insertRunSql(run: LabRunRecord, onConflict: String): JdbcClient.StatementSpec = jdbc.sql(
        """
        INSERT INTO lab_runs (id, code, title, scenario_id, scenario_version, status, step_index, step_started_at,
                              paused_at, revision, created_by_name, created_at, started_at, finished_at, salt, kind,
                              game_id)
        VALUES (:id, :code, :title, :scenarioId, :scenarioVersion, :status, :stepIndex, :stepStartedAt, :pausedAt,
                :revision, :createdByName, :createdAt, :startedAt, :finishedAt, :salt, :kind, :gameId)
        $onConflict
        """.trimIndent(),
    )
        .param("id", run.id)
        .param("code", run.code)
        .param("title", run.title)
        .param("scenarioId", run.scenarioId)
        .param("scenarioVersion", run.scenarioVersion)
        .param("createdByName", run.createdByName)
        .param("createdAt", run.createdAt.toTimestamptz())
        .param("salt", run.salt)
        .param("kind", run.kind.name)
        .param("gameId", run.gameId)
        .planParams(run)

    /** The field run of game [gameId], its row locked until the transaction ends; null: none yet. */
    fun lockGameRun(gameId: String): LabRunRecord? =
        jdbc.sql("SELECT * FROM lab_runs WHERE game_id = :gameId FOR UPDATE").param("gameId", gameId).query(runs)
            .optional().orElse(null)

    /** The field run of game [gameId], unlocked; null: none yet. */
    fun findGameRun(gameId: String): LabRunRecord? =
        jdbc.sql("SELECT * FROM lab_runs WHERE game_id = :gameId").param("gameId", gameId).query(runs).optional()
            .orElse(null)

    /** The field runs not finished yet: their games may be gone from the server. */
    fun openGameRuns(): List<LabRunRecord> =
        jdbc.sql("SELECT * FROM lab_runs WHERE kind = 'GAME' AND finished_at IS NULL").query(runs).list()
            .filterNotNull()

    /**
     * The field runs that finished before [finishedBefore], and those never finished made before [createdBefore],
     * whole: devices, chunks and reports (ON DELETE CASCADE). Their devices are the players.
     */
    fun deleteGameRunsFinishedBefore(finishedBefore: Instant, createdBefore: Instant): Int = jdbc.sql(
        """
        DELETE FROM lab_runs
        WHERE kind = 'GAME'
          AND (finished_at < :finishedBefore OR (finished_at IS NULL AND created_at < :createdBefore))
        """.trimIndent(),
    )
        .param("finishedBefore", finishedBefore.toTimestamptz())
        .param("createdBefore", createdBefore.toTimestamptz())
        .update()

    /** The plan's state and the run's start and end. */
    fun updatePlan(run: LabRunRecord) {
        jdbc.sql(
            """
            UPDATE lab_runs SET status = :status, step_index = :stepIndex, step_started_at = :stepStartedAt,
                paused_at = :pausedAt, revision = :revision, started_at = :startedAt, finished_at = :finishedAt
            WHERE id = :id
            """.trimIndent(),
        ).param("id", run.id).planParams(run).update()
    }

    fun findRun(id: String): LabRunRecord? =
        jdbc.sql("SELECT * FROM lab_runs WHERE id = :id").param("id", id).query(runs).optional().orElse(null)

    /** The row, locked until the transaction ends: the plan's changes, the joins and the uploads one after another. */
    fun lockRun(id: String): LabRunRecord? =
        jdbc.sql("SELECT * FROM lab_runs WHERE id = :id FOR UPDATE").param("id", id).query(runs).optional()
            .orElse(null)

    fun lockRunByCode(code: String): LabRunRecord? =
        jdbc.sql("SELECT * FROM lab_runs WHERE code = :code FOR UPDATE").param("code", code).query(runs).optional()
            .orElse(null)

    fun codeTaken(code: String): Boolean =
        jdbc.sql("SELECT count(*) FROM lab_runs WHERE code = :code").param("code", code).query(Long::class.java)
            .single() > 0

    /** Newest first, with their devices, bytes and whether the report is there; [kind]: this kind's only. */
    fun listRuns(limit: Int, kind: LabRunKind? = null): List<LabRunRow> = jdbc.sql(
        """
        SELECT r.*,
               (SELECT count(*) FROM lab_devices d WHERE d.run_id = r.id) AS device_count,
               (SELECT coalesce(sum(d.bytes), 0) FROM lab_devices d WHERE d.run_id = r.id) AS run_bytes,
               EXISTS (SELECT 1 FROM lab_reports p WHERE p.run_id = r.id) AS report_ready
        FROM lab_runs r
        WHERE (:kind::text IS NULL OR r.kind = :kind)
        ORDER BY r.created_at DESC, r.id
        LIMIT :limit
        """.trimIndent(),
    )
        .param("limit", limit)
        .param("kind", kind?.name)
        .query { rs, n ->
            LabRunRow(
                run = runs.mapRow(rs, n),
                devices = rs.getInt("device_count"),
                bytes = rs.getLong("run_bytes"),
                reportReady = rs.getBoolean("report_ready"),
            )
        }
        .list()
        .filterNotNull()

    /** With its devices, their chunks and its report (ON DELETE CASCADE). */
    fun deleteRun(id: String): Boolean = jdbc.sql("DELETE FROM lab_runs WHERE id = :id").param("id", id).update() > 0

    // Devices

    fun insertDevice(device: LabDeviceRecord, tokenHash: String) {
        jdbc.sql(
            """
            INSERT INTO lab_devices (id, run_id, label, model, os, build, commit, capabilities, token_hash,
                                     radar_token, joined_at, user_id, consent_at)
            VALUES (:id, :runId, :label, :model, :os, :build, :commit, :capabilities, :tokenHash, :radarToken,
                    :joinedAt, :userId, :consentAt)
            """.trimIndent(),
        )
            .param("userId", device.userId)
            .param("consentAt", device.consentAt?.toTimestamptz())
            .param("id", device.id)
            .param("runId", device.runId)
            .param("label", device.label)
            .param("model", device.model)
            .param("os", device.os)
            .param("build", device.build)
            .param("commit", device.commit)
            .param("capabilities", protocolJson.encodeToString(LabCapabilities.serializer(), device.capabilities))
            .param("tokenHash", tokenHash)
            .param("radarToken", device.radarToken)
            .param("joinedAt", device.joinedAt.toTimestamptz())
            .update()
    }

    /** The device of a token and the kind of its run, for the routes; null: no such device (or its run is gone). */
    fun findDeviceRefByTokenHash(tokenHash: String): LabDeviceRef? = jdbc.sql(
        """
        SELECT d.id, d.run_id, d.label, r.kind FROM lab_devices d JOIN lab_runs r ON r.id = d.run_id
        WHERE d.token_hash = :hash
        """.trimIndent(),
    )
        .param("hash", tokenHash)
        .query { rs, _ ->
            LabDeviceRef(
                deviceId = rs.getString("id"),
                runId = rs.getString("run_id"),
                label = rs.getString("label"),
                kind = LabRunKind.valueOf(rs.getString("kind")),
            )
        }
        .optional().orElse(null)

    fun findDeviceByTokenHash(tokenHash: String): LabDeviceRecord? =
        jdbc.sql("SELECT * FROM lab_devices WHERE token_hash = :hash").param("hash", tokenHash).query(devices)
            .optional().orElse(null)

    /** In the order they joined (by label within the same millisecond). */
    fun devicesOf(runId: String): List<LabDeviceRecord> =
        jdbc.sql("SELECT * FROM lab_devices WHERE run_id = :runId ORDER BY joined_at, label, id")
            .param("runId", runId)
            .query(devices)
            .list()
            .filterNotNull()

    /** What a run's chunks take, gzipped as stored. */
    fun runBytes(runId: String): Long =
        jdbc.sql("SELECT coalesce(sum(bytes), 0) FROM lab_devices WHERE run_id = :runId").param("runId", runId)
            .query(Long::class.java).single()

    /** What the chunks of all games' field runs take together, gzipped as stored (the server-wide budget). */
    fun gameRunsBytes(): Long = jdbc.sql(
        """
        SELECT coalesce(sum(d.bytes), 0) FROM lab_devices d JOIN lab_runs r ON r.id = d.run_id WHERE r.kind = 'GAME'
        """.trimIndent(),
    ).query(Long::class.java).single()

    // Chunks

    /** Stores a chunk unless the device has one from [seqFrom] already (a retried upload); true: stored. */
    fun insertChunk(
        deviceId: String,
        seqFrom: Long,
        seqTo: Long,
        tFrom: Long?,
        tTo: Long?,
        events: Int,
        body: ByteArray,
        receivedAt: Instant,
    ): Boolean {
        val stored = jdbc.sql(
            """
            INSERT INTO lab_chunks (device_id, seq_from, seq_to, t_from, t_to, events, received_at, body)
            VALUES (:deviceId, :seqFrom, :seqTo, :tFrom, :tTo, :events, :receivedAt, :body)
            ON CONFLICT (device_id, seq_from) DO NOTHING
            """.trimIndent(),
        )
            .param("deviceId", deviceId)
            .param("seqFrom", seqFrom)
            .param("seqTo", seqTo)
            .param("tFrom", tFrom)
            .param("tTo", tTo)
            .param("events", events)
            .param("receivedAt", receivedAt.toTimestamptz())
            .param("body", body)
            .update() > 0
        if (stored) {
            jdbc.sql(
                """
                UPDATE lab_devices SET last_chunk_at = :receivedAt,
                    last_seq = greatest(coalesce(last_seq, :seqTo), :seqTo),
                    bytes = bytes + :bytes, events = events + :events
                WHERE id = :deviceId
                """.trimIndent(),
            )
                .param("deviceId", deviceId)
                .param("receivedAt", receivedAt.toTimestamptz())
                .param("seqTo", seqTo)
                .param("bytes", body.size.toLong())
                .param("events", events.toLong())
                .update()
        }
        return stored
    }

    fun chunkExists(deviceId: String, seqFrom: Long): Boolean =
        jdbc.sql("SELECT count(*) FROM lab_chunks WHERE device_id = :deviceId AND seq_from = :seqFrom")
            .param("deviceId", deviceId)
            .param("seqFrom", seqFrom)
            .query(Long::class.java)
            .single() > 0

    fun lastSeq(deviceId: String): Long? =
        jdbc.sql("SELECT last_seq FROM lab_devices WHERE id = :id").param("id", deviceId)
            .query { rs, _ -> rs.getLong("last_seq").takeUnless { rs.wasNull() } }
            .optional().orElse(null)

    /** A device's chunks in the order of their events, without their bodies: those one at a time ([chunkBody]). */
    fun chunksOf(deviceId: String): List<LabChunk> = jdbc.sql(
        "SELECT id, seq_from, seq_to, events FROM lab_chunks WHERE device_id = :deviceId ORDER BY seq_from, id",
    )
        .param("deviceId", deviceId)
        .query { rs, _ ->
            LabChunk(rs.getLong("id"), rs.getLong("seq_from"), rs.getLong("seq_to"), rs.getInt("events"))
        }
        .list()
        .filterNotNull()

    /** A chunk's gzipped JSONL; empty when it is gone meanwhile (the run deleted, the retention). */
    fun chunkBody(chunk: LabChunk): ByteArray =
        jdbc.sql("SELECT body FROM lab_chunks WHERE id = :id").param("id", chunk.id)
            .query { rs, _ -> rs.getBytes("body") }
            .optional().orElse(null) ?: ByteArray(0)

    /**
     * The chunks of the lab's runs finished before [finishedBefore], and of those never finished that were made before
     * [createdBefore] (the join window after that); the runs, devices and reports stay. A game's run has its own
     * retention and goes whole ([deleteGameRunsFinishedBefore]).
     */
    fun deleteChunksOfRunsFinishedBefore(finishedBefore: Instant, createdBefore: Instant): Int = jdbc.sql(
        """
        DELETE FROM lab_chunks c USING lab_devices d, lab_runs r
        WHERE c.device_id = d.id AND d.run_id = r.id AND r.kind = 'LAB'
          AND (r.finished_at < :finishedBefore OR (r.finished_at IS NULL AND r.created_at < :createdBefore))
        """.trimIndent(),
    )
        .param("finishedBefore", finishedBefore.toTimestamptz())
        .param("createdBefore", createdBefore.toTimestamptz())
        .update()

    // Reports

    fun upsertReport(runId: String, version: Int, computedAt: Instant, body: String) {
        jdbc.sql(
            """
            INSERT INTO lab_reports (run_id, version, computed_at, body) VALUES (:runId, :version, :computedAt, :body)
            ON CONFLICT (run_id) DO UPDATE SET version = :version, computed_at = :computedAt, body = :body
            """.trimIndent(),
        )
            .param("runId", runId)
            .param("version", version)
            .param("computedAt", computedAt.toTimestamptz())
            .param("body", body)
            .update()
    }

    /** The games' field runs with a device of account [userId]: their reports were built from that player's log. */
    fun gameRunsOfUser(userId: String): List<String> = jdbc.sql(
        """
        SELECT DISTINCT d.run_id FROM lab_devices d JOIN lab_runs r ON r.id = d.run_id
        WHERE d.user_id = :userId AND r.kind = 'GAME'
        """.trimIndent(),
    )
        .param("userId", userId)
        .query(String::class.java)
        .list()
        .filterNotNull()

    fun deleteReport(runId: String): Boolean =
        jdbc.sql("DELETE FROM lab_reports WHERE run_id = :runId").param("runId", runId).update() > 0

    /** The report's JSON; null until computed. */
    fun findReport(runId: String): String? =
        jdbc.sql("SELECT body FROM lab_reports WHERE run_id = :runId").param("runId", runId)
            .query(String::class.java).optional().orElse(null)

    fun reportExists(runId: String): Boolean =
        jdbc.sql("SELECT count(*) FROM lab_reports WHERE run_id = :runId").param("runId", runId)
            .query(Long::class.java).single() > 0

    private fun JdbcClient.StatementSpec.planParams(run: LabRunRecord): JdbcClient.StatementSpec =
        param("status", run.plan.status.name)
            .param("stepIndex", run.plan.stepIndex)
            .param("stepStartedAt", run.plan.stepStartedAtMillis?.let { Instant.ofEpochMilli(it).toTimestamptz() })
            .param("pausedAt", run.plan.pausedAtMillis?.let { Instant.ofEpochMilli(it).toTimestamptz() })
            .param("revision", run.plan.revision)
            .param("startedAt", run.startedAt?.toTimestamptz())
            .param("finishedAt", run.finishedAt?.toTimestamptz())

    private val runs = RowMapper { rs, _ ->
        LabRunRecord(
            id = rs.getString("id"),
            code = rs.getString("code"),
            title = rs.getString("title"),
            scenarioId = rs.getString("scenario_id"),
            scenarioVersion = rs.getInt("scenario_version"),
            plan = LabPlanState(
                status = LabRunStatus.valueOf(rs.getString("status")),
                stepIndex = rs.getInt("step_index"),
                stepStartedAtMillis = rs.getInstantOrNull("step_started_at")?.toEpochMilli(),
                pausedAtMillis = rs.getInstantOrNull("paused_at")?.toEpochMilli(),
                revision = rs.getLong("revision"),
            ),
            createdByName = rs.getString("created_by_name"),
            createdAt = rs.getInstant("created_at"),
            startedAt = rs.getInstantOrNull("started_at"),
            finishedAt = rs.getInstantOrNull("finished_at"),
            salt = rs.getString("salt"),
            kind = LabRunKind.valueOf(rs.getString("kind")),
            gameId = rs.getString("game_id"),
        )
    }

    private val devices = RowMapper { rs, _ ->
        LabDeviceRecord(
            id = rs.getString("id"),
            runId = rs.getString("run_id"),
            label = rs.getString("label"),
            model = rs.getString("model"),
            os = rs.getString("os"),
            build = rs.getString("build"),
            commit = rs.getString("commit"),
            capabilities = runCatching {
                protocolJson.decodeFromString(LabCapabilities.serializer(), rs.getString("capabilities"))
            }.getOrDefault(LabCapabilities()),
            radarToken = rs.getString("radar_token"),
            joinedAt = rs.getInstant("joined_at"),
            lastChunkAt = rs.getInstantOrNull("last_chunk_at"),
            lastSeq = rs.getLong("last_seq").takeUnless { rs.wasNull() },
            bytes = rs.getLong("bytes"),
            events = rs.getLong("events"),
            userId = rs.getString("user_id"),
            consentAt = rs.getInstantOrNull("consent_at"),
        )
    }
}
