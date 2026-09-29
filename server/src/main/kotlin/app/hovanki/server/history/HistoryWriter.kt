package app.hovanki.server.history

import app.hovanki.server.account.UserRepository
import app.hovanki.server.game.GameRecord
import app.hovanki.server.radio.RadioCalibrationRepository
import app.hovanki.shared.protocol.UserId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Saves the history of finished games (docs/adr/0007-game-history-and-routes.md) on a thread of its own: the request
 * that finished a game, or the janitor, never waits for the database, and a database that is down only costs the
 * history of the games that finished meanwhile (logged, never retried). One thread, so everything is saved in the
 * order it was handed over: a game's results before a route saved late for it.
 *
 * Every game played to its end goes to `played_games`; each player with an account (still there) gets a row in
 * `game_results`; and those of them who agreed to keep their routes when it is saved, their route in `game_routes`.
 * With at least one of them, the game's recording, everybody's way, goes to `game_recordings`
 * (docs/adr/0011-spectators-and-recordings.md); the radar's readings by phone model add up in `radio_calibration`
 * (docs/adr/0012-nearby-radar.md).
 * The accounts are locked meanwhile, like [HistoryService.setPrivacy] locks them: a route is never saved after its
 * owner turned saving off.
 */
@Component
class HistoryWriter(
    private val history: HistoryRepository,
    private val users: UserRepository,
    private val calibration: RadioCalibrationRepository,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactions = TransactionTemplate(transactionManager)
    private val pool = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(QUEUE_SIZE)) { task ->
        thread(start = false, isDaemon = true, name = "history") { task.run() }
    }

    /** Saves a finished game (from [app.hovanki.server.game.Game.takeFinishedRecord]) in the background. */
    fun save(record: GameRecord) = submit(record) { store(record) }

    /**
     * Saves [userId]'s routes of [records], games that finished before they turned saving on and are still in memory.
     * Only where their results are saved already and they still agree.
     */
    fun saveLateRoutes(userId: UserId, records: List<GameRecord>) {
        for (record in records) submit(record) { storeLateRoute(userId, record) }
    }

    /** Waits until everything handed over so far is saved (tests). */
    fun awaitIdle(timeout: Duration = Duration.ofSeconds(10)) {
        val done = CountDownLatch(1)
        pool.execute { done.countDown() }
        check(done.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) { "History not saved within $timeout" }
    }

    internal fun store(record: GameRecord) {
        val now = clock.instant()
        transactions.executeWithoutResult {
            history.insertGame(record)
            val existing = users.lock(record.results.map { it.userId })
            val saving = users.savingRoutes(existing)
            for (result in record.results.filter { it.userId in existing }) {
                history.insertResult(record, result)
                if (result.userId in saving) history.insertRoute(record, result, now)
            }
            // The radar's readings by phone model: numbers of nobody's, next to the game's numbers.
            calibration.add(record.radioCalibration, now)
            // The recording is for the players with an account: without one of them, nobody could ever watch it. A big
            // game has none (Game.buildRecord).
            if (existing.isNotEmpty() && record.recording.isNotEmpty()) {
                history.insertRecording(record, existing.toSet(), now)
            }
        }
    }

    private fun storeLateRoute(userId: UserId, record: GameRecord) {
        val result = record.results.firstOrNull { it.userId == userId } ?: return
        val now = clock.instant()
        transactions.executeWithoutResult {
            if (users.lock(listOf(userId)).isEmpty() || users.savingRoutes(listOf(userId)).isEmpty()) {
                return@executeWithoutResult
            }
            history.insertRoute(record, result, now)
        }
    }

    private fun submit(record: GameRecord, task: () -> Unit) {
        try {
            pool.execute {
                try {
                    task()
                } catch (e: Exception) {
                    // Only the kind of error: PostgreSQL quotes failing rows in its messages, and these have routes.
                    val sqlState = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<SQLException>()
                        .firstOrNull()?.sqlState
                    log.warn(
                        "Could not save the history of game {}: {} (SQL state {})",
                        record.gameId.value,
                        e.javaClass.simpleName,
                        sqlState,
                    )
                }
            }
        } catch (e: RejectedExecutionException) {
            log.warn("History queue full, dropped game {}", record.gameId.value)
        }
    }

    /** Lets queued games be saved on shutdown, for a few seconds. */
    override fun destroy() {
        pool.shutdown()
        pool.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val QUEUE_SIZE = 1_000
        const val SHUTDOWN_SECONDS = 10L
    }
}
