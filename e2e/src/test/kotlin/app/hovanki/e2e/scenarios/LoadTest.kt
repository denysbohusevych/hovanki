package app.hovanki.e2e.scenarios

import app.hovanki.e2e.bot.BotPlayer
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.server.game.Game
import app.hovanki.shared.protocol.GamePhase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.junit.jupiter.api.parallel.Isolated
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Scenario 11: full games in parallel, every bot syncing every 3 s. Runs alone so the numbers mean something. */
@Isolated
class LoadTest {
    private val games = 3
    private val playersPerGame = Game.MAX_PLAYERS
    private val rules = GameSetups.FAST_RULES.copy(syncIntervalSeconds = 3)

    @Test
    fun fullGamesInParallel() = scenario("Load: 3 games x 30 bots", timeout = 4.minutes) {
        val random = Random(42)
        val lobbies = (1..games).map { game ->
            val center = PARK.offset(eastMeters = game * 2_000.0)
            val bots = (1..playersPerGame).map { n ->
                val start = center.offset(random.nextDouble(-50.0, 50.0), random.nextDouble(-50.0, 50.0))
                player("G$game-P$n", at = start, logChanges = false)
            }
            center to bots
        }

        val gameIds = coroutineScope {
            lobbies.map { (center, bots) ->
                async {
                    val host = bots.first()
                    requireOk(host.createGame(GameSetups.fast(center, rules)), "${host.name} creates a game")
                    val snapshot = checkNotNull(host.snapshot)
                    bots.drop(1).map { bot ->
                        async { requireOk(bot.join(snapshot.joinCode), "${bot.name} joins") }
                    }.awaitAll()
                    requireOk(host.startGame(seekers = bots.take(3)), "${host.name} starts")
                    snapshot.gameId
                }
            }.awaitAll()
        }
        check(gameIds.all { observer.game(it).players.size == playersPerGame }, "$games full games of $playersPerGame")

        for ((center, bots) in lobbies) {
            for (bot in bots.drop(
                3,
            )) {
                bot.gps.walkTo(center.offset(random.nextDouble(-80.0, 80.0), random.nextDouble(-80.0, 80.0)), 1.5)
            }
        }
        eventually("all games are in SEEKING", within = 20.seconds) {
            gameIds.all { observer.game(it).phase == GamePhase.SEEKING }.takeIf { it }
        }
        note("playing for 40 s")
        delay(40.seconds)

        for (id in gameIds) {
            val state = observer.game(id)
            val silent = state.players.filter {
                (it.lastFixReceivedMillis ?: 0) <
                    state.serverTimeMillis - 3 * rules.syncIntervalSeconds * 1000L
            }
            check(
                silent.isEmpty(),
                "game ${id.value}: every player's fixes keep arriving (silent: ${silent.map {
                    it.name
                }})",
            )
        }
        val syncs = metrics.syncCount
        val p95 = metrics.syncPercentile(95.0)
        note("sync latency: ${metrics.summary()}")
        check(metrics.errors.isEmpty(), "no failed requests: ${metrics.errors.take(5)}")
        check(syncs >= games * playersPerGame * 10, "$syncs syncs")
        check(p95 < MAX_P95_MILLIS, "p95 of /sync is $p95 ms (limit $MAX_P95_MILLIS ms)")
        check(players.all(BotPlayer::isAppRunning), "no app crashed")
    }

    private companion object {
        /** Generous for a shared CI runner that also runs the bots; typical local values are a few ms. */
        const val MAX_P95_MILLIS = 500L
    }
}
