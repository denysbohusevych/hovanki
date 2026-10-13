package app.hovanki.perf

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.rules.GameSetup
import io.gatling.javaapi.core.CoreDsl.StringBody
import io.gatling.javaapi.core.CoreDsl.asLongAs
import io.gatling.javaapi.core.CoreDsl.atOnceUsers
import io.gatling.javaapi.core.CoreDsl.doIf
import io.gatling.javaapi.core.CoreDsl.doIfOrElse
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.exitHereIfFailed
import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.jsonPath
import io.gatling.javaapi.core.CoreDsl.nothingFor
import io.gatling.javaapi.core.CoreDsl.pace
import io.gatling.javaapi.core.CoreDsl.pause
import io.gatling.javaapi.core.CoreDsl.rampUsers
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Session
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Ordinary games side by side (docs/perf.md): the most common load of the server. Every virtual user is one player's
 * phone. The first player of a game creates it (guests, no accounts), the others join with its code, the host starts
 * once everybody is in, and everybody syncs every `GameRules.syncIntervalSeconds` with a fix of their walk, as the app
 * does, through the lobby, the hiding and the search. Nobody catches anybody: the hot path is `/sync`.
 *
 * The ladder (`perf.steps` over 1) finds the server's limit in one run: every level brings `perf.games` more games, and
 * the run stops at the first level where the server stops keeping up ([LoadWatch]: the sync's p95 over
 * `perf.targetMs`, or failures), saying at how many players.
 *
 * `./gradlew :perf:gatlingRun --simulation app.hovanki.perf.OrdinaryGames -Pperf.games=20 -Pperf.steps=10`
 * - `perf.games` (10): games of a level, 2 km apart; their players come in over `perf.rampSeconds` (60), game after game;
 * - `perf.players` (30, production's limit of an ordinary game): players per game, a tenth of them seekers;
 * - `perf.minutes` (5): how long a level holds after its players came; everybody plays until the run's end;
 * - `perf.steps` (1): levels; the whole ladder has to fit the longest search, 90 minutes;
 * - `perf.targetMs` (50): the sync's p95 the server has to keep on the ladder;
 * - `perf.transport` (poll): `socket` syncs over the live channel ([LabConfig.transport]).
 */
class OrdinaryGames : Simulation() {
    private val games = LabConfig.int("games", 10)
    private val players = LabConfig.int("players", 30)
    private val rampSeconds = LabConfig.int("rampSeconds", 60)
    private val minutes = LabConfig.int("minutes", 5)
    private val steps = LabConfig.int("steps", 1)
    private val targetMillis = LabConfig.int("targetMs", 50).toLong()
    private val seekers = (players / 10).coerceAtLeast(1)

    /** A level: its players come in, then it holds. */
    private val levelSeconds = rampSeconds + minutes * 60

    private val setup = GameSetup(
        radiusMeters = ZONE_RADIUS_METERS,
        hidingMinutes = 1,
        seekingMinutes = (steps * levelSeconds / 60 + 5).coerceIn(GameSetup.SEEKING_MINUTES),
    )
    private val tables = List(games * steps) { Table(it) }

    /** Tells this run's admin from the earlier runs' ones in the same database (a socket run makes one). */
    private val run = Random.nextInt(36 * 36 * 36 * 36).toString(36).padStart(4, '0')

    private var liveChannel: LiveChannel? = null

    @Volatile
    private var endsAtMillis = Long.MAX_VALUE

    /** Judges the ladder from the first level's end on: the JIT and the pools warm up while the first players come. */
    @Volatile
    private var watch = LoadWatch(targetMillis, fromMillis = Long.MAX_VALUE)

    /** One game's meeting point: the host leaves the code there, the players their ids. */
    private inner class Table(index: Int) {
        val center: GeoPoint = ORIGIN.moveBy(eastMeters = index % 10 * 2_000.0, northMeters = index / 10 * 2_000.0)

        @Volatile
        var joinCode: String? = null
        val joined = ConcurrentLinkedQueue<PlayerId>()
        private val started = AtomicBoolean(false)

        /** When the host may start without the players who never came: the ramp is over and a little more. */
        @Volatile
        var startBy = Long.MAX_VALUE

        fun open(code: String) {
            startBy = System.currentTimeMillis() + (rampSeconds + START_PATIENCE_SECONDS) * 1000L
            joinCode = code
        }

        fun ready(): Boolean =
            joined.size >= players - 1 || (joined.size > seekers && System.currentTimeMillis() > startBy)

        fun claimStart(): Boolean = started.compareAndSet(false, true)

        fun seekers(): List<PlayerId> = joined.take(seekers)
    }

    private fun Session.table(): Table = tables[getInt("game")]

    private val seats = (0 until games * steps).flatMap { game ->
        (0 until players).map { seat -> mapOf("game" to game, "seat" to seat) }
    }.iterator()

    private val host = exec(
        http("create game").post(ApiRoutes.GAMES)
            .body(
                StringBody { session ->
                    json(CreateGameRequest(session.name(), setup.settings(session.table().center)))
                },
            )
            .check(
                status().shouldBe(200),
                jsonPath("$.session.gameId").saveAs(Keys.GAME_ID),
                jsonPath("$.session.playerId").saveAs(Keys.PLAYER_ID),
                jsonPath("$.session.token").saveAs(Keys.TOKEN),
                jsonPath("$.snapshot.joinCode").saveAs("joinCode"),
            ),
    ).exec { session ->
        session.getString("joinCode")?.let { session.table().open(it) }
        session
    }

    private val guest = asLongAs { session -> session.table().joinCode == null }.on(pause(Duration.ofMillis(200)))
        .exec(
            http("join").post(ApiRoutes.JOIN)
                .body(
                    StringBody { session ->
                        val code = checkNotNull(session.table().joinCode)
                        json(JoinGameRequest(code, session.name(), requestId = UUID.randomUUID().toString()))
                    },
                )
                .check(
                    status().shouldBe(200),
                    jsonPath("$.session.gameId").saveAs(Keys.GAME_ID),
                    jsonPath("$.session.playerId").saveAs(Keys.PLAYER_ID),
                    jsonPath("$.session.token").saveAs(Keys.TOKEN),
                ),
        ).exec { session ->
            session.getString(Keys.PLAYER_ID)?.let { session.table().joined.add(PlayerId(it)) }
            session
        }

    private val start = exec(
        http("start").post { session -> ApiRoutes.start(session.gameId()) }
            .asPlayer()
            .body(StringBody { session -> json(StartGameRequest(session.table().seekers())) })
            .check(status().shouldBe(200), jsonPath("$.phase").saveAs(Keys.PHASE)),
    )

    private val player = scenario("Ordinary games")
        .feed(seats)
        .exec { session ->
            val table = session.table()
            session.set(Keys.WALKER, Walker(table.center, ZONE_RADIUS_METERS * 0.6, seed = session.userId().toInt()))
                .set(Keys.PHASE, GamePhase.LOBBY.name)
        }
        .doIfOrElse { session -> session.getInt("seat") == 0 }.then(host).orElse(guest)
        .exitHereIfFailed()
        .exec(connect())
        .exitHereIfFailed()
        .exec { session ->
            val playing = watch.players.incrementAndGet()
            if (steps > 1 && playing % (games * players) == 0) {
                println("Level ${playing / (games * players)} of $steps: $playing players")
            }
            session
        }
        .exec(
            asLongAs { System.currentTimeMillis() < endsAtMillis }.on(
                pace(Duration.ofSeconds(SYNC_SECONDS.toLong()))
                    .exec { session -> session.set(SYNC_STARTED, System.nanoTime()) }
                    .exec(sync())
                    .exec { session -> judge(session) }
                    .stopLoadGeneratorIf({ session -> session.getString(STOP) }) { session -> session.contains(STOP) }
                    .doIf { session ->
                        session.getInt("seat") == 0 && session.phase() == GamePhase.LOBBY &&
                            session.table().ready() && session.table().claimStart()
                    }.then(start),
            ),
        )
        .exec { session ->
            watch.players.decrementAndGet()
            session
        }
        .exec(disconnect())

    /** The sync just made, for the ladder; a failed one counted, then forgotten, so the player plays on. */
    private fun judge(session: Session): Session {
        val now = System.currentTimeMillis()
        val tookMillis = (System.nanoTime() - session.getLong(SYNC_STARTED)) / 1_000_000
        watch.record(tookMillis, session.isFailed, now)
        val breakdown = if (steps > 1) watch.breakdown(now) else null
        return (if (breakdown != null) session.set(STOP, "The ladder stops $breakdown") else session).markAsSucceeded()
    }

    /** A socket run's live channel; the run's end and the ladder's watch, from the moment the first player comes. */
    override fun before() {
        liveChannel = LiveChannel.openFor { LabAdmin.create(run) }
        val now = System.currentTimeMillis()
        endsAtMillis = now + steps * levelSeconds * 1000L
        watch = LoadWatch(targetMillis, fromMillis = now + levelSeconds * 1000L)
        println(
            "${games * steps} games of $players over ${LabConfig.transport.name.lowercase()}" +
                (if (steps > 1) ", $steps levels of $games games, ${levelSeconds}s each" else ""),
        )
    }

    override fun after() {
        runCatching { liveChannel?.close() }
    }

    init {
        val level = if (rampSeconds > 0) {
            rampUsers(games * players).during(Duration.ofSeconds(rampSeconds.toLong()))
        } else {
            atOnceUsers(games * players)
        }
        val ladder = (0 until steps).flatMap { step ->
            if (step == 0) listOf(level) else listOf(nothingFor(Duration.ofSeconds(minutes * 60L)), level)
        }
        setUp(player.injectOpen(ladder)).protocols(hovankiHttp())
            .assertions(global().failedRequests().percent().lt(1.0))
    }

    private fun Session.name(): String = "G${getInt("game")}P${getInt("seat")}"

    private companion object {
        /** Mariinskyi Park, Kyiv, like the e2e games; the fake buildings and streets are laid around every center. */
        val ORIGIN = GeoPoint(50.4476, 30.5396)
        const val ZONE_RADIUS_METERS = 500
        const val START_PATIENCE_SECONDS = 30
        const val SYNC_STARTED = "syncStarted"
        const val STOP = "stop"
        val SYNC_SECONDS = GameRules().syncIntervalSeconds
    }
}
