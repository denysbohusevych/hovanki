package app.hovanki.server.lab

import app.hovanki.server.account.AccountKeys
import app.hovanki.server.game.IdGenerator
import app.hovanki.shared.lab.FieldKinds
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.protocol.LabRunStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64

/** Where [FieldEventWriter] puts the server's events of a game's field log: the database, or a fake in tests. */
interface FieldLogStore {
    /** The id of game [gameId]'s field run, opened by its first phone ([FieldRunService.join]); null: none yet. */
    fun runOf(gameId: String): String?

    /** A new device [FieldKinds.SERVER_DEVICE] in run [runId]; its id, or null when the run is over. */
    fun addServerDevice(runId: String): String?

    /** Stores [events] (`seq` [seqFrom]..[seqTo], in order) of [deviceId] in run [runId]. */
    fun append(runId: String, deviceId: String, events: List<JsonObject>, seqFrom: Long, seqTo: Long): Append

    enum class Append {
        STORED,

        /** The run is finished and its last uploads are over, or gone: nothing more of this game. */
        CLOSED,

        /** The run, or all the games' runs, have all the room they may have: these events are lost. */
        FULL,
    }
}

/**
 * [FieldLogStore] in `lab_devices`/`lab_chunks` with the limits of a phone's upload ([LabRunService.acceptChunk]):
 * the run's and all games' bytes, the upload grace of a finished run. Never on a game's lock: [FieldEventWriter]'s
 * thread only.
 */
@Component
class JdbcFieldLogStore(
    private val repository: LabRunRepository,
    private val live: LabLive,
    private val properties: FieldProperties,
    private val ids: IdGenerator,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) : FieldLogStore {
    private val transactions = TransactionTemplate(transactionManager)
    private val random = SecureRandom()

    override fun runOf(gameId: String): String? = repository.findGameRun(gameId)?.id

    override fun addServerDevice(runId: String): String? = transactions.execute {
        val now = clock.instant()
        val run = repository.lockRun(runId)
        if (run == null || !isOpen(run)) return@execute null
        val device = LabDeviceRecord(
            id = ids.gameId().value,
            runId = runId,
            label = FieldKinds.SERVER_DEVICE,
            model = FieldKinds.SERVER_DEVICE,
            os = "JVM ${Runtime.version().feature()}",
            radarToken = "",
            joinedAt = now,
        )
        // Nobody ever holds its token: the server writes its chunks itself.
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        repository.insertDevice(device, AccountKeys.tokenHash(Base64.getUrlEncoder().encodeToString(token)))
        device.id
    }

    override fun append(
        runId: String,
        deviceId: String,
        events: List<JsonObject>,
        seqFrom: Long,
        seqTo: Long,
    ): FieldLogStore.Append {
        val body = LabChunks.gzip(events.joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8))
        val times = events.mapNotNull { (it[LabFields.T] as? JsonPrimitive)?.longOrNull }
        return checkNotNull(
            transactions.execute {
                val now = clock.instant()
                val run = repository.lockRun(runId)
                if (run == null || !isOpen(run)) return@execute FieldLogStore.Append.CLOSED
                if (repository.runBytes(runId) + body.size > properties.maxRunBytes.toBytes() ||
                    repository.gameRunsBytes() + body.size > properties.maxTotalBytes.toBytes()
                ) {
                    return@execute FieldLogStore.Append.FULL
                }
                val stored = repository.insertChunk(
                    deviceId = deviceId,
                    seqFrom = seqFrom,
                    seqTo = seqTo,
                    tFrom = times.minOrNull(),
                    tTo = times.maxOrNull(),
                    events = events.size,
                    body = body,
                    receivedAt = now,
                )
                // Under the run's lock, as a phone's upload: a finish drops the live view after this, never before.
                if (stored && run.plan.status != LabRunStatus.FINISHED) {
                    live.accept(runId, deviceId, events, now.toEpochMilli(), pairs = false)
                }
                FieldLogStore.Append.STORED
            },
        )
    }

    /** Takes events: not finished, or finished less than the upload grace ago (the phones' last chunks too). */
    private fun isOpen(run: LabRunRecord): Boolean {
        val finishedAt = run.finishedAt ?: return true
        return clock.instant().isBefore(finishedAt + properties.uploadGrace)
    }

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
