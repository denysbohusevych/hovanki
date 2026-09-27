package app.hovanki.e2e.scenarios

import app.hovanki.e2e.bot.BotPlayer
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.e2e.scenario.Scenario
import app.hovanki.server.game.Game
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
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

    /**
     * Scenario 6.3: many short games one wave after another, as an evening on the server: 50 games of three players in
     * waves of ten, each over by two catches within seconds. Nothing piles up: every game finishes, no request fails,
     * and the last wave's `/sync` is not much slower than the first one's.
     */
    @Test
    fun manyShortGames() = scenario("Load: 50 short games in waves", timeout = 4.minutes) {
        val settings = GameSetups.fast().copy(hidingSeconds = 0)
        val p95s = mutableListOf<Long>()
        val finished = mutableListOf<GameId>()
        for (wave in 1..WAVES) {
            val before = metrics.syncLatencies.size
            finished += coroutineScope {
                (1..GAMES_PER_WAVE).map { n -> async { shortGame("W${wave}G$n", wave, n, settings) } }.awaitAll()
            }
            val latencies = metrics.syncLatencies.drop(before).sorted()
            val p95 = latencies[(latencies.size * 95 / 100).coerceAtMost(latencies.lastIndex)]
            note("wave $wave: ${latencies.size} syncs, p95 $p95 ms")
            p95s += p95
        }

        val onServer = observer.games().games.filter { it.gameId in finished }
        check(onServer.size == WAVES * GAMES_PER_WAVE, "the server has all ${WAVES * GAMES_PER_WAVE} games")
        check(onServer.all { it.phase == GamePhase.FINISHED }, "all of them finished")
        check(metrics.errors.isEmpty(), "no failed requests: ${metrics.errors.take(5)}")
        check(
            p95s.last() <= maxOf(2 * p95s.first(), p95s.first() + 50),
            "the last wave is not much slower than the first ($p95s ms)",
        )
        check(p95s.max() < MAX_P95_MILLIS, "p95 of /sync below $MAX_P95_MILLIS ms in every wave")
    }

    /** One game: the host seeks, two hiders stand next to him and show their codes; over once both are caught. */
    private suspend fun Scenario.shortGame(name: String, wave: Int, n: Int, settings: GameSettings): GameId {
        val center = PARK.offset(eastMeters = n * 1_000.0, northMeters = wave * 1_000.0)
        val host = player("$name-S", at = center, logChanges = false)
        val hiders = (1..2).map { player("$name-H$it", at = center.offset(eastMeters = 5.0 * it), logChanges = false) }
        requireOk(host.createGame(settings), "${host.name} creates a game")
        val game = checkNotNull(host.snapshot)
        for (hider in hiders) requireOk(hider.join(game.joinCode), "${hider.name} joins")
        eventually("${host.name}'s fixes reach the server", within = 10.seconds) {
            observer.game(game.gameId).players.single { it.id == host.id }.latestUsableFix
        }
        requireOk(host.startGame(listOf(host)), "${host.name} starts")
        for (hider in hiders) {
            requireOk(host.claimCatch(hider), "${host.name} claims ${hider.name}")
            val code = eventually("${hider.name} shows the code", within = 10.seconds) { hider.shownCode() }
            requireOk(host.confirmCatch(code.code), "${host.name} types ${hider.name}'s code")
        }
        awaitThat("${host.name}'s game is over", 10.seconds) { host.snapshot?.phase == GamePhase.FINISHED }
        for (bot in listOf(host) + hiders) bot.leave()
        return game.gameId
    }

    private companion object {
        /** Generous for a shared CI runner that also runs the bots; typical local values are a few ms. */
        const val MAX_P95_MILLIS = 500L
        const val WAVES = 5
        const val GAMES_PER_WAVE = 10
    }
}
