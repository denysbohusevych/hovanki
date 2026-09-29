package app.hovanki.e2e.scenarios

import app.hovanki.e2e.admin.StaffConsole
import app.hovanki.e2e.bot.BotPlayer
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.AdminBigGameRequest
import app.hovanki.shared.protocol.BigGameSetup
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.UserRole
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.protocol.protocolJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.parallel.Isolated
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The load test of big games (docs/adr/0010-big-games.md): hundreds of bots in one big game, each on its own phone,
 * syncing every 3 s through the lobby, the hiding and the search. `slow`: nightly, or by hand with
 * `./gradlew :e2e:test -Pe2e.slow=true --tests '*BigGameLoadTest'`; `-Pe2e.bigGamePlayers=1600` for the most a big
 * game takes (the numbers are in the ADR). Runs alone so the numbers mean something.
 */
@Isolated
@Tag("slow")
class BigGameLoadTest {
    private val count = System.getProperty("hovanki.e2e.bigGamePlayers")?.toInt() ?: DEFAULT_PLAYERS

    @Test
    fun hundredsOfPlayersInOneBigGame() = scenario("Load: a big game of $count bots", timeout = 12.minutes) {
        val random = Random(7)
        val mila = player("Mila", at = PARK, logChanges = false)
        val milaAccount = mila.signsUp()
        mila.confirmsEmail()
        observer.setRole(checkNotNull(mila.userId), UserRole.ADMIN)
        // Room for everybody on built-up ground (the test source): 1 000 m² a player and a fifth more.
        val half = sqrt(count * 1_000.0 * 1.2) / 2
        val zone = ZonePolygon(
            listOf(
                PARK.offset(-half, -half),
                PARK.offset(half, -half),
                PARK.offset(half, half),
                PARK.offset(-half, half),
            ),
        )
        val bots = (1..count).map { n ->
            player(
                "B$n",
                at = PARK.offset(random.nextDouble(-half, half), random.nextDouble(-half, half)),
                logChanges = false,
            )
        }

        StaffConsole(serverUrl, observer).use { console ->
            console.logIn(milaAccount)
            val game = console.createBigGame(
                AdminBigGameRequest(
                    title = "Load",
                    startsAtMillis = System.currentTimeMillis() + 20.minutes.inWholeMilliseconds,
                    timeZone = "UTC",
                    zone = zone,
                    setup = BigGameSetup(
                        hidingMinutes = 1,
                        seekingMinutes = 10,
                        glowEveryMinutes = 1,
                        glowForSeconds = 5,
                        seekers = count / 10,
                    ),
                    playerLimit = count,
                    reason = "the load test",
                ),
            )
            note("big game ${game.id.value}: ${game.playerLimit} places")

            // Everybody registers, signs up and comes into the lobby, a few dozen at a time.
            val gate = Semaphore(PARALLEL_JOINS)
            coroutineScope {
                bots.map { bot ->
                    async {
                        gate.withPermit {
                            bot.signsUp()
                            requireOk(bot.signsUpFor(game.id), "${bot.name} signs up")
                            requireOk(bot.joinBigGame(game.id), "${bot.name} comes in")
                        }
                    }
                }.awaitAll()
            }
            val lobby = checkNotNull(bots.first().snapshot)
            useGame(lobby.gameId, lobby.joinCode)
            check(state().players.size == count, "all $count in the lobby")
            val lobbySyncs = metrics.syncLatencies.size
            note("lobby: ${lobbyNote(bots.first())}")

            console.startBigGame(game.id, "everybody is here")
            awaitPhase(GamePhase.HIDING, within = 30.seconds)
            for (bot in bots) {
                bot.gps.walkTo(PARK.offset(random.nextDouble(-half, half), random.nextDouble(-half, half)), 1.4)
            }
            awaitPhase(GamePhase.SEEKING, within = 90.seconds)
            note("playing for 60 s of the search")
            delay(60.seconds)

            val round = state()
            val silent = round.players.filter {
                (it.lastFixReceivedMillis ?: 0) <
                    round.serverTimeMillis - 3 * round.settings.rules.syncIntervalSeconds * 1000L
            }
            check(silent.isEmpty(), "every player's fixes keep arriving (silent: ${silent.size})")
            val hider = bots.first { it.snapshot?.me?.role == Role.HIDER }
            val seeker = bots.first { it.snapshot?.me?.role == Role.SEEKER }
            note("a hider's poll: ${lobbyNote(hider)}; a seeker's: ${lobbyNote(seeker)}")
            note("sync latency: ${metrics.summary()} (${metrics.syncLatencies.size - lobbySyncs} in the round)")
            val p95 = metrics.syncPercentile(95.0)
            check(metrics.errors.isEmpty(), "no failed requests: ${metrics.errors.take(5)}")
            check(p95 < MAX_P95_MILLIS, "p95 of /sync is $p95 ms (limit $MAX_P95_MILLIS ms)")
            check(bots.all(BotPlayer::isAppRunning), "no app crashed")
            console.cancelBigGame(game.id, "the load test is over")
        }
    }

    /** How many players a bot's last snapshot lists, and how large it is. */
    private fun lobbyNote(bot: BotPlayer): String {
        val snapshot = checkNotNull(bot.snapshot)
        val bytes = protocolJson.encodeToString(snapshot).length
        return "${snapshot.players.size} of ${snapshot.counts?.players} players listed, ${bytes / 1024} KB"
    }

    private companion object {
        const val DEFAULT_PLAYERS = 300
        const val PARALLEL_JOINS = 30
        const val MAX_P95_MILLIS = 1_000L
    }
}
