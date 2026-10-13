package app.hovanki.perf

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.AdminBigGameRequest
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.BigGameId
import app.hovanki.shared.protocol.BigGameSetup
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinBigGameRequest
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.rules.BigGameLimits
import io.gatling.javaapi.core.CoreDsl.StringBody
import io.gatling.javaapi.core.CoreDsl.asLongAs
import io.gatling.javaapi.core.CoreDsl.atOnceUsers
import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.jsonPath
import io.gatling.javaapi.core.CoreDsl.pace
import io.gatling.javaapi.core.CoreDsl.rampUsers
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Session
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import java.time.Duration
import java.util.UUID
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One big game (docs/adr/0010-big-games.md, docs/perf.md): hundreds of players in one game, all through one game's
 * lock. Before the players come, an admin of the lab makes the big game ([LabAdmin]), its start a little after the last
 * player is due; every virtual user then signs up as a new account, signs up for the game, comes into the lobby and
 * syncs every `GameRules.syncIntervalSeconds` with a fix of their walk, through the lobby, the start by the server's
 * scheduler (everybody at once), the hiding and the search. Nobody catches anybody.
 *
 * `./gradlew :perf:gatlingRun --simulation app.hovanki.perf.BigGame -Pperf.players=1600`
 * - `perf.players` (300): players; 1 600 is the most a big game takes (`BigGameLimits.MAX_PLAYERS`);
 * - `perf.rampSeconds` (60): they come in over this time, a new account each (registering is part of the load);
 * - `perf.lobbySeconds` (ramp + 45): the round starts this long after the setup, by the scheduler's next tick;
 * - `perf.minutes` (5): how long everybody plays after the start: a minute of hiding, then the search.
 */
class BigGame : Simulation() {
    private val players = LabConfig.int("players", 300)
    private val rampSeconds = LabConfig.int("rampSeconds", 60)
    private val lobbySeconds = LabConfig.int("lobbySeconds", rampSeconds + LOBBY_MARGIN_SECONDS)
    private val minutes = LabConfig.int("minutes", 5)

    /** Tells this run's accounts from the earlier runs' ones in the same database. */
    private val run = Random.nextInt(36 * 36 * 36 * 36).toString(36).padStart(4, '0')

    /** Half the side of the square zone: built-up ground everywhere (the fake terrain), 1 000 m² a player and a fifth. */
    private val half = sqrt(players * SQUARE_METERS_PER_PLAYER * 1.2) / 2

    private lateinit var admin: LabAdmin

    @Volatile
    private var bigGame: BigGameId? = null

    @Volatile
    private var endsAtMillis = Long.MAX_VALUE

    private fun Session.bigGame(): BigGameId = checkNotNull(bigGame) { "No big game: the setup failed" }

    private fun Session.nickname(): String = "p_${run}_${userId()}"

    private val player = scenario("Big game")
        .exec { session ->
            val random = Random(session.userId())
            val start = CENTER.moveBy(random.nextDouble(-half, half) * 0.8, random.nextDouble(-half, half) * 0.8)
            session.set(Keys.WALKER, Walker(CENTER, half * 0.9, seed = session.userId().toInt(), start = start))
                .set(Keys.PHASE, GamePhase.LOBBY.name)
        }
        .exec(
            http("register").post(ApiRoutes.ACCOUNTS)
                .body(
                    StringBody { session ->
                        val nickname = session.nickname()
                        json(RegisterRequest(nickname, "$nickname@hovanki.test", PASSWORD))
                    },
                )
                .check(status().shouldBe(200), jsonPath("$.token").saveAs(Keys.ACCOUNT_TOKEN)),
        )
        .exitHereIfFailed()
        .exec(
            http("big game: sign up").post { session -> ApiRoutes.bigGameSignup(session.bigGame()) }
                .asAccount()
                .check(status().shouldBe(200)),
        )
        .exec(
            http("big game: join").post { session -> ApiRoutes.bigGameJoin(session.bigGame()) }
                .asAccount()
                .body(StringBody { json(JoinBigGameRequest(requestId = UUID.randomUUID().toString())) })
                .check(
                    status().shouldBe(200),
                    jsonPath("$.session.gameId").saveAs(Keys.GAME_ID),
                    jsonPath("$.session.playerId").saveAs(Keys.PLAYER_ID),
                    jsonPath("$.session.token").saveAs(Keys.TOKEN),
                ),
        )
        .exitHereIfFailed()
        .exec(
            asLongAs { System.currentTimeMillis() < endsAtMillis }.on(
                pace(Duration.ofSeconds(GameRules().syncIntervalSeconds.toLong())).exec(sync()),
            ),
        )

    /** The admin and the big game, before the first player comes: not part of the measurement. */
    override fun before() {
        admin = LabAdmin.create(run)
        val startsAtMillis = System.currentTimeMillis() + lobbySeconds * 1000L
        val game = admin.createBigGame(
            AdminBigGameRequest(
                title = "Perf $run",
                startsAtMillis = startsAtMillis,
                timeZone = "UTC",
                zone = ZonePolygon(
                    listOf(
                        CENTER.moveBy(-half, -half),
                        CENTER.moveBy(half, -half),
                        CENTER.moveBy(half, half),
                        CENTER.moveBy(-half, half),
                    ),
                ),
                setup = BigGameSetup(
                    hidingMinutes = 1,
                    seekingMinutes = (minutes + 5).coerceIn(BigGameLimits.SEEKING_MINUTES),
                    seekers = (players / 10).coerceAtLeast(1),
                ),
                playerLimit = players,
                reason = "perf: the BigGame simulation",
            ),
        )
        admin.awaitLobby(game.id)
        endsAtMillis = startsAtMillis + minutes * 60_000L
        bigGame = game.id
        println("Big game ${game.id.value}: $players places, the round starts in $lobbySeconds s")
    }

    /** The round is called off: it would go on in the server's memory after the run. */
    override fun after() {
        bigGame?.let { id -> runCatching { admin.cancelBigGame(id, "perf: the run is over") } }
    }

    init {
        setUp(
            player.injectOpen(
                if (rampSeconds > 0) {
                    rampUsers(players).during(Duration.ofSeconds(rampSeconds.toLong()))
                } else {
                    atOnceUsers(players)
                },
            ),
        ).protocols(hovankiHttp())
            .assertions(global().failedRequests().percent().lt(1.0))
    }

    private companion object {
        /** Mariinskyi Park, Kyiv, like the e2e games; the fake buildings and streets are laid around it. */
        val CENTER = GeoPoint(50.4476, 30.5396)
        const val SQUARE_METERS_PER_PLAYER = 1_000.0
        const val PASSWORD = "perf-player-password"

        /** After the last player is due: the lobby opens at the scheduler's tick (10 s), registering takes a while. */
        const val LOBBY_MARGIN_SECONDS = 45
    }
}
