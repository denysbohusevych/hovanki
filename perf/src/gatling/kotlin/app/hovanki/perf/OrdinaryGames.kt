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
import io.gatling.javaapi.core.CoreDsl.during
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.exitHereIfFailed
import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.jsonPath
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

/**
 * Ordinary games side by side (docs/perf.md): the most common load of the server. Every virtual user is one player's
 * phone. The first player of a game creates it (guests, no accounts), the others join with its code, the host starts
 * once everybody is in, and everybody syncs every `GameRules.syncIntervalSeconds` with a fix of their walk, as the app
 * does, through the lobby, the hiding and the search. Nobody catches anybody: the hot path is `/sync`.
 *
 * `./gradlew :perf:gatlingRun --simulation app.hovanki.perf.OrdinaryGames -Pperf.games=20`
 * - `perf.games` (10): games, 2 km apart; the players come in over `perf.rampSeconds` (60), game after game;
 * - `perf.players` (30, production's limit of an ordinary game): players per game, a tenth of them seekers;
 * - `perf.minutes` (5): how long every player plays after joining.
 */
class OrdinaryGames : Simulation() {
    private val games = LabConfig.int("games", 10)
    private val players = LabConfig.int("players", 30)
    private val rampSeconds = LabConfig.int("rampSeconds", 60)
    private val minutes = LabConfig.int("minutes", 5)
    private val seekers = (players / 10).coerceAtLeast(1)

    private val setup = GameSetup(
        radiusMeters = ZONE_RADIUS_METERS,
        hidingMinutes = 1,
        seekingMinutes = (minutes + rampSeconds / 60 + 5).coerceIn(GameSetup.SEEKING_MINUTES),
    )
    private val tables = List(games) { Table(it) }

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

    private val seats = (0 until games).flatMap { game ->
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
        .exec(
            during(Duration.ofMinutes(minutes.toLong())).on(
                pace(Duration.ofSeconds(SYNC_SECONDS.toLong()))
                    .exec(sync())
                    .doIf { session ->
                        session.getInt("seat") == 0 && session.phase() == GamePhase.LOBBY &&
                            session.table().ready() && session.table().claimStart()
                    }.then(start),
            ),
        )

    init {
        setUp(
            player.injectOpen(
                if (rampSeconds > 0) {
                    rampUsers(games * players).during(Duration.ofSeconds(rampSeconds.toLong()))
                } else {
                    atOnceUsers(games * players)
                },
            ),
        ).protocols(hovankiHttp())
            .assertions(global().failedRequests().percent().lt(1.0))
    }

    private fun Session.name(): String = "G${getInt("game")}P${getInt("seat")}"

    private companion object {
        /** Mariinskyi Park, Kyiv, like the e2e games; the fake buildings and streets are laid around every center. */
        val ORIGIN = GeoPoint(50.4476, 30.5396)
        const val ZONE_RADIUS_METERS = 500
        const val START_PATIENCE_SECONDS = 30
        val SYNC_SECONDS = GameRules().syncIntervalSeconds
    }
}
