package app.hovanki.server.lab

import app.hovanki.server.account.AccountKeys
import app.hovanki.server.features.FeatureFlags
import app.hovanki.server.game.GameException
import app.hovanki.server.game.GameService
import app.hovanki.server.game.IdGenerator
import app.hovanki.server.game.PlayerRef
import app.hovanki.server.ratelimit.RateLimit
import app.hovanki.server.ratelimit.RateLimiter
import app.hovanki.shared.lab.LabPlanState
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.FieldJoinRequest
import app.hovanki.shared.protocol.FieldJoinResponse
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.LabRunId
import app.hovanki.shared.protocol.LabRunKind
import app.hovanki.shared.protocol.LabRunStatus
import app.hovanki.shared.protocol.LabUpload
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.totp.toHex
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * The field log of real games (docs/adr/0018-field-test-build.md §3, docs/field-test.md step 2): a game is a run of the
 * radio lab ([LabRunKind.GAME]) whose devices are its players' phones of the field build, after their testers agreed.
 *
 * A phone joins with its game token ([join]); the game's run is opened by the first one, so a game nobody logs costs
 * nothing, and the run's row is the lock of the joins. From then on the phone uploads like a lab phone
 * ([LabRunService.acceptChunk]). The run is finished once its game is gone from the server ([closeRunsOfGoneGames],
 * from [app.hovanki.server.game.GameJanitor]): the phones still have [FieldProperties.uploadGrace] for their last
 * chunks, and the whole run goes [FieldProperties.retention] later (DataRetention). Behind [ServerFeature.FIELD_LOG]:
 * off, the join answers 404 as if it didn't exist. Never on the game's hot path: the game's lock is held only to read
 * who the player is, the database is touched after it.
 *
 * Each device keeps its player's account ([LabDeviceRecord.userId], gone with the account) and when its tester agreed
 * ([LabDeviceRecord.consentAt]); its label in the run is the player's id. A rejoin (the app restarted) is a new device,
 * as in the lab. Logs only ids and counts.
 */
@Service
class FieldRunService(
    private val repository: LabRunRepository,
    private val games: GameService,
    private val features: FeatureFlags,
    private val rateLimiter: RateLimiter,
    private val live: LabLive,
    private val properties: FieldProperties,
    private val ids: IdGenerator,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactions = TransactionTemplate(transactionManager)
    private val random = SecureRandom()

    /** 404 while the operator has the field log off: its routes don't exist then. */
    fun requireEnabled() {
        if (!features.isEnabled(ServerFeature.FIELD_LOG)) throw GameException(ErrorCode.NOT_FOUND, "Not found")
    }

    /**
     * [caller]'s phone joins the field log of game [gameId]: a new device of the game's run (opened now if it is the
     * first), with the player's account if they have one. The tester must have agreed ([FieldJoinRequest.consentAtMillis],
     * not in the future). Refused: the game's run is finished ([ErrorReason.LAB_RUN_CLOSED]) or full
     * ([ErrorReason.LIMIT_REACHED]).
     */
    fun join(caller: PlayerRef, gameId: GameId, request: FieldJoinRequest): FieldJoinResponse {
        requireEnabled()
        // Per player, not per address: a whole game may come from behind one carrier's NAT.
        rateLimiter.acquire(RateLimit.FIELD_JOIN_PER_PLAYER, "${caller.gameId.value}/${caller.playerId.value}")
        val now = clock.instant()
        val consentAt = request.consentAtMillis?.let(Instant::ofEpochMilli)
        if (consentAt == null || consentAt.isAfter(now + CONSENT_SLACK) || consentAt.isBefore(EARLIEST_CONSENT)) {
            throw GameException(ErrorCode.BAD_REQUEST, "The tester's consent is required")
        }
        // Under the game's lock: only who the player is; the 404 / 403 of a gone game or another game's token.
        val player = games.withGame(caller, gameId) { game, _ ->
            Player(game.joinCode, game.userIdOf(caller.playerId)?.value)
        }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(TOKEN_BYTES))
        val device = transactions.execute {
            val run = openRun(gameId, player.joinCode, now)
            if (run.plan.status == LabRunStatus.FINISHED || !now.isBefore(run.createdAt + properties.joinWindow)) {
                throw GameException(ErrorCode.WRONG_STATE, "The game's log is closed", ErrorReason.LAB_RUN_CLOSED)
            }
            if (repository.devicesOf(run.id).size >= properties.maxDevices) {
                throw GameException(ErrorCode.WRONG_STATE, "The game's log is full", ErrorReason.LIMIT_REACHED)
            }
            val device = LabDeviceRecord(
                id = ids.gameId().value,
                runId = run.id,
                label = caller.playerId.value,
                model = request.model?.clip(),
                os = request.os?.clip(),
                build = request.build?.clip(),
                commit = request.commit?.clip(),
                capabilities = request.capabilities,
                // The game's radar tokens change with the clock: the run gives none of its own.
                radarToken = "",
                joinedAt = now,
                userId = player.userId,
                consentAt = consentAt,
            )
            repository.insertDevice(device, AccountKeys.tokenHash(token))
            run to device
        }
        val (run, record) = checkNotNull(device)
        return FieldJoinResponse(
            runId = LabRunId(run.id),
            deviceId = record.id,
            token = token,
            label = record.label,
            salt = run.salt,
            serverTimeMillis = now.toEpochMilli(),
            uploadIntervalMillis = properties.uploadInterval.toMillis(),
            maxEvents = LabUpload.MAX_EVENTS,
            maxBodyBytes = LabUpload.MAX_BODY_BYTES,
            rxEveryMillis = properties.rxEvery.toMillis(),
            frameEveryMillis = properties.frameEvery.toMillis(),
            gpsEveryMillis = properties.gpsEvery.toMillis(),
        )
    }

    /**
     * Finishes the field runs whose games are not [isLive] any more (the janitor removed them, or the server
     * restarted): the phones' last uploads still come for [FieldProperties.uploadGrace]. Whatever the switch: a run
     * opened while it was on ends too. A game is asked after its run was read, so a game made meanwhile is live.
     * Returns how many it finished.
     */
    fun closeRunsOfGoneGames(isLive: (GameId) -> Boolean): Int {
        val now = clock.instant()
        var closed = 0
        for (run in repository.openGameRuns()) {
            val gameId = run.gameId ?: continue
            if (isLive(GameId(gameId))) continue
            val finished = transactions.execute {
                val locked = repository.lockRun(run.id) ?: return@execute false
                if (locked.plan.status == LabRunStatus.FINISHED) return@execute false
                repository.updatePlan(
                    locked.copy(
                        plan = locked.plan.copy(status = LabRunStatus.FINISHED),
                        finishedAt = locked.finishedAt ?: now,
                    ),
                )
                true
            }
            if (finished == true) {
                live.drop(run.id)
                closed++
            }
        }
        if (closed > 0) log.info("Field log: finished {} runs of games that are gone", closed)
        return closed
    }

    /** The game's run, locked; made now by the first phone of the game (another may make it at the same moment). */
    private fun openRun(gameId: GameId, joinCode: String, now: Instant): LabRunRecord {
        repository.lockGameRun(gameId.value)?.let { return it }
        val run = LabRunRecord(
            id = ids.gameId().value,
            // Never a code to join by: a lab code is 6 letters and digits.
            code = "$CODE_PREFIX${gameId.value}",
            title = "Game $joinCode",
            scenarioId = SCENARIO,
            scenarioVersion = 0,
            plan = LabPlanState(status = LabRunStatus.RUNNING),
            createdByName = null,
            createdAt = now,
            startedAt = now,
            salt = randomBytes(SALT_BYTES).toHex(),
            kind = LabRunKind.GAME,
            gameId = gameId.value,
        )
        if (repository.insertGameRunIfAbsent(run)) log.info("Field log: game {} opened run {}", gameId.value, run.id)
        return checkNotNull(repository.lockGameRun(gameId.value)) { "The run of game ${gameId.value} vanished" }
    }

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    private data class Player(val joinCode: String, val userId: String?)

    private companion object {
        const val TOKEN_BYTES = 32
        const val SALT_BYTES = 16
        const val TEXT_MAX_LENGTH = 120
        const val CODE_PREFIX = "game:"

        /** No plan of the catalog: a game's run has none. */
        const val SCENARIO = "game"

        /**
         * A phone's clock ahead of the server's still agreed «now»: the consent screen comes before the app ever asked
         * the server's clock.
         */
        val CONSENT_SLACK: Duration = Duration.ofDays(1)
        val EARLIEST_CONSENT: Instant = Instant.parse("2026-01-01T00:00:00Z")

        fun String.clip(): String = trim().take(TEXT_MAX_LENGTH)
    }
}
