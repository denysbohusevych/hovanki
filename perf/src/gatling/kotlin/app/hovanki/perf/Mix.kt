package app.hovanki.perf

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.AdminBigGameRequest
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.BigGameId
import app.hovanki.shared.protocol.BigGameSetup
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.FeatureMode
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinBigGameRequest
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.LeaderboardScope
import app.hovanki.shared.protocol.LoginRequest
import app.hovanki.shared.protocol.PlayAgainRequest
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.SendChatRequest
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.rules.BigGameLimits
import app.hovanki.shared.rules.GameSetup
import io.gatling.javaapi.core.ChainBuilder
import io.gatling.javaapi.core.CoreDsl.StringBody
import io.gatling.javaapi.core.CoreDsl.asLongAs
import io.gatling.javaapi.core.CoreDsl.doIf
import io.gatling.javaapi.core.CoreDsl.doIfOrElse
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.feed
import io.gatling.javaapi.core.CoreDsl.jsonPath
import io.gatling.javaapi.core.CoreDsl.nothingFor
import io.gatling.javaapi.core.CoreDsl.pace
import io.gatling.javaapi.core.CoreDsl.pause
import io.gatling.javaapi.core.CoreDsl.percent
import io.gatling.javaapi.core.CoreDsl.rampUsers
import io.gatling.javaapi.core.CoreDsl.randomSwitch
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.OpenInjectionStep
import io.gatling.javaapi.core.PopulationBuilder
import io.gatling.javaapi.core.ScenarioBuilder
import io.gatling.javaapi.core.Session
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import io.gatling.javaapi.http.HttpRequestActionBuilder
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The server's day at its busiest (docs/perf.md): every kind of player of the stress test to 30 000 at once, in the
 * shares the plan sets. Every virtual user is one phone.
 * - 65 % play ordinary rounds: games of `perf.minPlayers`–`perf.maxPlayers` players, the radar on in every other one
 *   (with the sightings and the phone's report in every sync), a chat message about once a minute, the round to its end
 *   by the clock, the results, then half the games «Ще раз» (the host opens the next lobby, everybody follows) and the
 *   other half's players go to new games;
 * - 10 % wait in lobbies: they join, sync in the lobby for `perf.lobbyMinutes`, leave, and join another;
 * - 5 % play one big game (docs/adr/0010-big-games.md), at most 1 600 (`BigGameLimits.MAX_PLAYERS`): they come over
 *   the first half of the ladder, the round starts at its half and lasts to the end;
 * - 15 % are on the start screen with an account: the inbox every 10 s as the app polls it, and about once a minute
 *   one of the profile, the stats, the history, a leaderboard (every scope) or the achievements;
 * - 5 % log in or register (40 % of them register) every `perf.loginEverySeconds`, then wait on the start screen.
 *
 * Of the players in games 60 % have an account (`perf.accountPercent`), the others are guests; 80 % sync over the live
 * channel (`perf.socketPercent`), the others poll: the transport is the player's, not the run's. The accounts are the
 * seeded ones ([SeedAccounts], perf/seed.sh first), each one in one place at a time; registering makes new ones.
 *
 * The ladder: `perf.players` (30 000) arrive in `perf.steps` (10) levels, each over `perf.rampSeconds` (60) and then
 * holding for `perf.minutes` (3); the run stops at the first level where the syncs stop keeping up ([LoadWatch], the
 * p95 over `perf.targetMs`, 50, or failures), saying at how many players.
 *
 * `./gradlew :perf:gatlingRun --simulation app.hovanki.perf.Mix -Pperf.players=30000` (perf/run.sh does it all).
 */
class Mix : Simulation() {
    private val total = LabConfig.int("players", 30_000)
    private val steps = LabConfig.int("steps", 10)
    private val rampSeconds = LabConfig.int("rampSeconds", 60)
    private val minutes = LabConfig.int("minutes", 3)
    private val targetMillis = LabConfig.int("targetMs", 50).toLong()
    private val minPlayers = LabConfig.int("minPlayers", 4)
    private val maxPlayers = LabConfig.int("maxPlayers", 30)
    private val roundMinutes = LabConfig.int("roundMinutes", GameSetup.SEEKING_MINUTES.first)
    private val lobbyMinutes = LabConfig.int("lobbyMinutes", 5)
    private val loginEverySeconds = LabConfig.int("loginEverySeconds", 600)
    private val accountPercent = LabConfig.int("accountPercent", 60)
    private val socketPercent = LabConfig.int("socketPercent", 80)

    private val levelSeconds = rampSeconds + minutes * 60
    private val runSeconds = steps * levelSeconds

    private val rounds = share(ROUNDS_PERCENT)
    private val lobbies = share(LOBBY_PERCENT)
    private val bigPlayers = share(BIG_PERCENT).coerceAtMost(BigGameLimits.MAX_PLAYERS)
    private val starters = share(START_PERCENT)
    private val logins = share(LOGIN_PERCENT)

    /** The levels the big game's players come in: the first half of the ladder, then its round starts. */
    private val bigSteps = ((steps + 1) / 2).coerceAtLeast(1)

    /** Tells this run's admin and new accounts from the earlier runs' ones in the same database. */
    private val run = Random.nextInt(36 * 36 * 36 * 36).toString(36).padStart(4, '0')

    private val nextAccount = AtomicInteger(Random.nextInt(SeedAccounts.users))
    private val nextTable = AtomicInteger()

    private lateinit var admin: LabAdmin
    private var liveChannel: LiveChannel? = null

    @Volatile
    private var bigGame: BigGameId? = null

    @Volatile
    private var endsAtMillis = Long.MAX_VALUE

    @Volatile
    private var watch = LoadWatch(targetMillis, fromMillis = Long.MAX_VALUE)

    private fun share(percent: Int): Int = (total * percent / 100.0).roundToInt()

    private fun running(): Boolean = System.currentTimeMillis() < endsAtMillis

    /** One game's meeting point, as in [OrdinaryGames]: the host leaves the code, the players their ids and secrets. */
    private inner class Table(val index: Int, val size: Int, val radar: Boolean, val starts: Boolean) {
        val center: GeoPoint = ORIGIN.moveBy(eastMeters = index % 50 * 2_000.0, northMeters = index / 50 * 2_000.0)
        val seekers = (size / 5).coerceAtLeast(1)

        @Volatile
        var joinCode: String? = null
        val joined = ConcurrentLinkedQueue<PlayerId>()
        private val started = AtomicBoolean(false)

        @Volatile
        var startBy = Long.MAX_VALUE

        /** The radar secrets of the players of the round, for their neighbours' sightings. */
        val secrets = ConcurrentHashMap<String, String>()

        /** Whether the players of the finished round play again: the host decides once the round is over. */
        @Volatile
        var again: Boolean? = null

        fun open(code: String) {
            startBy = System.currentTimeMillis() + (rampSeconds + START_PATIENCE_SECONDS) * 1000L
            joinCode = code
        }

        /** The host opened the next lobby: the same table, a new round of joins. */
        fun reopen() {
            joined.clear()
            secrets.clear()
            started.set(false)
            again = null
            startBy = System.currentTimeMillis() + AGAIN_PATIENCE_SECONDS * 1000L
        }

        fun ready(): Boolean =
            joined.size >= size - 1 || (joined.size >= seekers && System.currentTimeMillis() > startBy)

        fun claimStart(): Boolean = started.compareAndSet(false, true)
    }

    private val tables = ConcurrentHashMap<Int, Table>()

    /**
     * Seats at tables without end, table after table: a player who leaves a game takes the next free seat. A table's
     * size is drawn when its first seat is handed out; every other table of the rounds has the radar.
     */
    private inner class Seats(private val starts: Boolean) : Iterator<Map<String, Any>> {
        private var table: Table? = null
        private var seat = 0

        override fun hasNext() = true

        @Synchronized
        override fun next(): Map<String, Any> {
            val current = table?.takeIf { seat < it.size } ?: newTable().also {
                table = it
                seat = 0
            }
            return mapOf(TABLE to current.index, SEAT to seat++)
        }

        private fun newTable(): Table {
            val index = nextTable.getAndIncrement()
            val size = ThreadLocalRandom.current().nextInt(minPlayers, maxPlayers + 1)
            return Table(index, size, radar = starts && index % 2 == 0, starts = starts).also { tables[index] = it }
        }
    }

    private fun Session.table(): Table = checkNotNull(tables[getInt(TABLE)])

    private fun Session.isHost(): Boolean = getInt(SEAT) == 0

    private fun Session.hasAccount(): Boolean = contains(Keys.ACCOUNT_TOKEN)

    /** The phone: its transport, maybe an account, the next seat's walk; none of the last game's state. */
    private fun phone(withAccountPercent: Int): (Session) -> Session = { session ->
        val random = ThreadLocalRandom.current()
        val transport = if (random.nextInt(100) < socketPercent) Transport.SOCKET else Transport.POLL
        val withAccount = random.nextInt(100) < withAccountPercent
        session.set(Keys.TRANSPORT, transport.name)
            .let { if (withAccount) it.set(Keys.ACCOUNT_TOKEN, SeedAccounts.token(account())) else it }
    }

    private fun account(): Int = Math.floorMod(nextAccount.getAndIncrement(), SeedAccounts.users)

    /** Sits the player at their table: a walk about its center, the lobby, none of the last game's state. */
    private fun seat(session: Session): Session {
        val table = session.table()
        val playerName = "T${table.index}P${session.getInt(SEAT)}"
        return session.removeAll(Keys.CHAT_AFTER, Keys.RADAR_SECRET, Keys.AGAIN_CODE, Keys.GAME_ID, Keys.TOKEN)
            .set(NAME, playerName)
            .set(Keys.WALKER, Walker(table.center, ZONE_RADIUS_METERS * 0.6, seed = session.userId().toInt()))
            .set(Keys.PHASE, GamePhase.LOBBY.name)
            .set(FINISHED_SYNCS, 0)
            .set(Keys.PHONE, Phone(session.userId().toInt(), table.radar) { neighbours(session.table(), playerName) })
    }

    private fun neighbours(table: Table, me: String): Collection<String> = table.secrets.filterKeys { it != me }.values

    /** As the app sends a game request: with the account's token when the player has one, so the game knows it. */
    private fun withAccountIfAny(request: () -> HttpRequestActionBuilder): ChainBuilder =
        doIfOrElse { session -> session.hasAccount() }.then(exec(request().asAccount())).orElse(exec(request()))

    private fun setupOf(table: Table): GameSetup = GameSetup(
        radiusMeters = ZONE_RADIUS_METERS,
        hidingMinutes = 1,
        seekingMinutes = roundMinutes.coerceIn(GameSetup.SEEKING_MINUTES),
    )

    private val host = withAccountIfAny {
        http("create game").post(ApiRoutes.GAMES)
            .body(
                StringBody { session ->
                    val table = session.table()
                    val settings = setupOf(table).settings(table.center).let {
                        if (table.radar) {
                            it.copy(features = GameFeatures(radar = FeatureMode.OPTIONAL, hiderSense = true))
                        } else {
                            it
                        }
                    }
                    json(CreateGameRequest(session.getString(NAME)!!, settings, leaveOtherGame = true))
                },
            )
            .check(
                status().shouldBe(200),
                jsonPath("$.session.gameId").saveAs(Keys.GAME_ID),
                jsonPath("$.session.playerId").saveAs(Keys.PLAYER_ID),
                jsonPath("$.session.token").saveAs(Keys.TOKEN),
                jsonPath("$.snapshot.joinCode").saveAs(JOIN_CODE),
            )
    }.exec { session ->
        session.getString(JOIN_CODE)?.let { session.table().open(it) }
        session
    }

    private val guest = asLongAs { session -> session.table().joinCode == null && running() }
        .on(pause(Duration.ofMillis(500)))
        .doIf { session -> session.table().joinCode != null }.then(
            withAccountIfAny {
                http("join").post(ApiRoutes.JOIN)
                    .body(
                        StringBody { session ->
                            json(
                                JoinGameRequest(
                                    checkNotNull(session.table().joinCode),
                                    session.getString(NAME)!!,
                                    requestId = UUID.randomUUID().toString(),
                                    leaveOtherGame = true,
                                ),
                            )
                        },
                    )
                    .check(
                        status().shouldBe(200),
                        jsonPath("$.session.gameId").saveAs(Keys.GAME_ID),
                        jsonPath("$.session.playerId").saveAs(Keys.PLAYER_ID),
                        jsonPath("$.session.token").saveAs(Keys.TOKEN),
                    )
            }.exec { session ->
                session.getString(Keys.PLAYER_ID)?.let { session.table().joined.add(PlayerId(it)) }
                session
            },
        )

    private val start = exec(
        http("start").post { session -> ApiRoutes.start(session.gameId()) }
            .asPlayer()
            .body(StringBody { session -> json(StartGameRequest(session.table().joined.take(session.table().seekers))) })
            .check(status().shouldBe(200), jsonPath("$.phase").saveAs(Keys.PHASE)),
    )

    /** A chat message, as a player writes one about once a minute: everybody in the lobby, the own team in the round. */
    private val chat = exec(
        http("chat").post { session -> ApiRoutes.chat(session.gameId()) }
            .asPlayer()
            .body(
                StringBody { session ->
                    json(
                        SendChatRequest(
                            text = CHAT_LINES.random(),
                            team = session.phase() != GamePhase.LOBBY,
                            chatAfter = if (session.contains(Keys.CHAT_AFTER)) session.getLong(Keys.CHAT_AFTER) else 0,
                            clientMessageId = UUID.randomUUID().toString(),
                        ),
                    )
                },
            )
            .check(status().shouldBe(200), jsonPath("$.chat[-1:].seq").ofLong().optional().saveAs(Keys.CHAT_AFTER)),
    )

    /** «Ще раз» on the results: the host opens the next lobby, the others follow into it; a new socket for it. */
    private val playAgain = exec(disconnect())
        .exec { session ->
            if (session.isHost()) session.table().reopen()
            session
        }
        .exec(
            http("play again").post { session -> ApiRoutes.playAgain(session.gameId()) }
                .asPlayer()
                .body(StringBody { json(PlayAgainRequest(UUID.randomUUID().toString(), leaveOtherGame = true)) })
                .check(
                    status().shouldBe(200),
                    jsonPath("$.session.gameId").saveAs(Keys.GAME_ID),
                    jsonPath("$.session.playerId").saveAs(Keys.PLAYER_ID),
                    jsonPath("$.session.token").saveAs(Keys.TOKEN),
                ),
        )
        .exec { session ->
            if (!session.isHost()) session.getString(Keys.PLAYER_ID)?.let { session.table().joined.add(PlayerId(it)) }
            session.removeAll(Keys.CHAT_AFTER, Keys.RADAR_SECRET, Keys.AGAIN_CODE)
                .set(Keys.PHASE, GamePhase.LOBBY.name)
                .set(FINISHED_SYNCS, 0)
        }
        .exec(connect())

    /** What the player does after a sync in an ordinary game, by its phase. */
    private val afterSync = exec { session -> registerSecret(session) }
        .doIf { session ->
            session.isHost() && session.phase() == GamePhase.LOBBY && session.table().starts &&
                session.table().ready() && session.table().claimStart()
        }.then(start)
        .doIf { session -> session.phase() != GamePhase.FINISHED && ThreadLocalRandom.current().nextInt(CHAT_SYNCS) == 0 }
        .then(chat)
        .doIf { session -> session.phase() == GamePhase.FINISHED }.then(
            exec { session -> session.set(FINISHED_SYNCS, session.getInt(FINISHED_SYNCS) + 1) }
                .doIf { session -> session.isHost() && session.getInt(FINISHED_SYNCS) == RESULTS_SYNCS }.then(
                    exec { session ->
                        val again = ThreadLocalRandom.current().nextBoolean() && running()
                        session.table().again = again
                        if (again) session else session.set(LEAVING, true)
                    }.doIf { session -> !session.contains(LEAVING) }.then(playAgain),
                )
                .doIf { session -> !session.isHost() && session.getInt(FINISHED_SYNCS) >= RESULTS_SYNCS }.then(
                    doIfOrElse { session -> session.contains(Keys.AGAIN_CODE) }.then(playAgain).orElse(
                        exec { session ->
                            // The host went home, or never offered the next lobby: the player finds another game.
                            val gone = session.table().again == false ||
                                session.getInt(FINISHED_SYNCS) >= RESULTS_SYNCS + AGAIN_WAIT_SYNCS
                            if (gone) session.set(LEAVING, true) else session
                        },
                    ),
                ),
        )

    private fun registerSecret(session: Session): Session {
        val secret = session.getString(Keys.RADAR_SECRET) ?: return session
        session.table().secrets.putIfAbsent(session.getString(NAME)!!, secret)
        return session
    }

    /** The sync loop of a player in a game, until [until] says the player leaves it; measured for the ladder. */
    private fun syncLoop(until: (Session) -> Boolean, then: ChainBuilder?): ChainBuilder =
        asLongAs { session -> running() && !until(session) }.on(
            pace(Duration.ofSeconds(SYNC_SECONDS.toLong()))
                .exec { session -> session.set(SYNC_STARTED, System.nanoTime()) }
                .exec(sync())
                .exec { session -> judge(session) }
                .stopLoadGeneratorIf({ session -> session.getString(STOP) }) { session -> session.contains(STOP) }
                .let { if (then != null) it.exec(then) else it },
        )

    /** Into a game, around its loop, and out: the next seat then, until the run's end. */
    private fun games(
        name: String,
        seats: Seats,
        stay: (Session) -> Boolean,
        then: ChainBuilder?,
        onLeave: ChainBuilder? = null,
    ): ScenarioBuilder =
        scenario(name)
            .exec(phone(accountPercent))
            .exec { session ->
                watch.players.incrementAndGet()
                session
            }
            .asLongAs { running() }.on(
                feed(seats)
                    .exec { session -> seat(session).remove(LEAVING) }
                    .doIfOrElse { session -> session.isHost() }.then(host).orElse(guest)
                    .doIf { session -> !session.isFailed && session.contains(Keys.TOKEN) }.then(
                        exec(connect())
                            .exec { session -> session.set(JOINED_AT, System.currentTimeMillis()) }
                            .exec(syncLoop(until = { session -> session.contains(LEAVING) || !stay(session) }, then))
                            .exec(disconnect())
                            .let { if (onLeave != null) it.exec(onLeave) else it },
                    )
                    .exec { session -> session.markAsSucceeded() },
            )
            .exec { session ->
                watch.players.decrementAndGet()
                session
            }

    private val roundsScenario = games("Rounds", Seats(starts = true), stay = { true }, then = afterSync)

    private val lobbyScenario = games(
        "Lobbies",
        Seats(starts = false),
        stay = { session -> System.currentTimeMillis() - session.getLong(JOINED_AT) < lobbyMinutes * 60_000L },
        then = doIf { ThreadLocalRandom.current().nextInt(CHAT_SYNCS) == 0 }.then(chat),
        onLeave = exec(
            http("leave").post { session -> ApiRoutes.leave(session.gameId()) }.asPlayer()
                .check(status().`in`(200, 204)),
        ),
    )

    private val bigScenario = scenario("Big game")
        .exec(phone(withAccountPercent = 100))
        .exec { session ->
            val random = Random(session.userId())
            val half = bigHalf()
            val start = CENTER.moveBy(random.nextDouble(-half, half) * 0.8, random.nextDouble(-half, half) * 0.8)
            session.set(Keys.WALKER, Walker(CENTER, half * 0.9, seed = session.userId().toInt(), start = start))
                .set(Keys.PHASE, GamePhase.LOBBY.name)
                .set(Keys.PHONE, Phone(session.userId().toInt(), radar = false) { emptyList() })
        }
        .exec(
            http("big game: sign up").post { session -> ApiRoutes.bigGameSignup(checkNotNull(bigGame)) }
                .asAccount()
                .check(status().shouldBe(200)),
        )
        .exec(
            http("big game: join").post { session -> ApiRoutes.bigGameJoin(checkNotNull(bigGame)) }
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
        .exec(connect())
        .exitHereIfFailed()
        .exec { session ->
            watch.players.incrementAndGet()
            session
        }
        .exec(syncLoop(until = { false }, then = null))
        .exec { session ->
            watch.players.decrementAndGet()
            session
        }
        .exec(disconnect())

    /** One visit of the start screen's other tabs: what a player looks at about once a minute. */
    private val browse = randomSwitch().on(
        percent(20.0).then(exec(accountGet("profile", ApiRoutes.ME))),
        percent(15.0).then(exec(accountGet("stats", ApiRoutes.ME_STATS))),
        percent(15.0).then(exec(accountGet("history", ApiRoutes.ME_GAMES))),
        percent(15.0).then(exec(accountGet("achievements", ApiRoutes.ME_ACHIEVEMENTS))),
        percent(15.0).then(exec(leaderboard(LeaderboardScope.WORLD))),
        percent(10.0).then(exec(leaderboard(LeaderboardScope.CITY))),
        percent(5.0).then(exec(leaderboard(LeaderboardScope.FRIENDS))),
        percent(5.0).then(exec(leaderboard(LeaderboardScope.LAST_GAME))),
    )

    private fun accountGet(name: String, path: String) =
        http(name).get(path).asAccount().check(status().shouldBe(200))

    private fun leaderboard(scope: LeaderboardScope) =
        http("leaderboard ${scope.name}").get("${ApiRoutes.ME_LEADERBOARD}?scope=${scope.name}").asAccount()
            .check(status().shouldBe(200))

    /** The start screen while [until] holds: the inbox every 10 s, and about every sixth time a look elsewhere. */
    private fun startScreen(until: (Session) -> Boolean): ChainBuilder = asLongAs { session -> running() && until(session) }
        .on(
            pace(Duration.ofSeconds(INBOX_SECONDS))
                .exec(http("inbox").get(ApiRoutes.INBOX).asAccount().check(status().shouldBe(200)))
                .doIf { ThreadLocalRandom.current().nextInt(BROWSE_EVERY) == 0 }.then(browse),
        )

    private val startScenario = scenario("Start screen")
        .exec { session -> session.set(Keys.ACCOUNT_TOKEN, SeedAccounts.token(account())) }
        .exec(accountGet("profile", ApiRoutes.ME))
        .exec(startScreen { true })

    private val loginScenario = scenario("Login")
        // Not everybody at the same second: the first login somewhere within one period.
        .pause(Duration.ZERO, Duration.ofSeconds(loginEverySeconds.toLong()))
        .asLongAs { running() }.on(
            exec { session ->
                val iteration = if (session.contains(ITERATION)) session.getInt(ITERATION) + 1 else 1
                session.set(LOGGED_IN_AT, System.currentTimeMillis()).set(ITERATION, iteration)
            }
                .randomSwitch().on(
                    percent(REGISTER_PERCENT).then(exec(register())),
                    percent(100.0 - REGISTER_PERCENT).then(exec(login())),
                )
                .doIf { session -> session.contains(Keys.ACCOUNT_TOKEN) }.then(
                    exec(accountGet("profile", ApiRoutes.ME))
                        .exec(
                            startScreen { session ->
                                System.currentTimeMillis() - session.getLong(LOGGED_IN_AT) < loginEverySeconds * 1000L
                            },
                        )
                        .exec(http("logout").post(ApiRoutes.LOGOUT).asAccount().check(status().`in`(200, 204)))
                        .exec { session -> session.remove(Keys.ACCOUNT_TOKEN) },
                )
                .exec { session -> session.markAsSucceeded() },
        )

    private fun register() = http("register").post(ApiRoutes.ACCOUNTS)
        .body(
            StringBody { session ->
                val nickname = "m${run}_${session.userId()}_${session.getInt(ITERATION)}"
                json(RegisterRequest(nickname, "$nickname@hovanki.test", SeedAccounts.PASSWORD))
            },
        )
        .check(status().shouldBe(200), jsonPath("$.token").saveAs(Keys.ACCOUNT_TOKEN))

    private fun login() = http("login").post(ApiRoutes.LOGIN)
        .body(StringBody { json(LoginRequest(SeedAccounts.login(account()), SeedAccounts.PASSWORD)) })
        .check(status().shouldBe(200), jsonPath("$.token").saveAs(Keys.ACCOUNT_TOKEN))

    /** The sync just made, for the ladder; a failed one counted, then forgotten, so the player plays on. */
    private fun judge(session: Session): Session {
        val now = System.currentTimeMillis()
        val tookMillis = (System.nanoTime() - session.getLong(SYNC_STARTED)) / 1_000_000
        watch.record(tookMillis, session.isFailed, now)
        val breakdown = (if (steps > 1) watch.breakdown(now) else null) ?: return session.markAsSucceeded()
        val why = "The ladder stops $breakdown"
        if (watch.stopped.compareAndSet(false, true)) println(why)
        return session.set(STOP, why).markAsSucceeded()
    }

    private fun bigHalf(): Double = sqrt(bigPlayers.coerceAtLeast(1) * SQUARE_METERS_PER_PLAYER * 1.2) / 2

    /** The admin, the server's features this mix needs and the big game, before the first player comes: not measured. */
    override fun before() {
        admin = LabAdmin.create(run)
        liveChannel = LiveChannel.openFor(needed = socketPercent > 0) { admin }
        admin.setFeature(ServerFeature.RADAR, true, "perf: the mix plays the radar")
        admin.setFeature(ServerFeature.HIDER_SENSE, true, "perf: the mix plays the radar")
        val now = System.currentTimeMillis()
        endsAtMillis = now + runSeconds * 1000L
        watch = LoadWatch(targetMillis, fromMillis = now + levelSeconds * 1000L)
        if (bigPlayers > 0) {
            val startsAtMillis = now + ((bigSteps - 1) * levelSeconds + rampSeconds + BIG_LOBBY_MARGIN_SECONDS) * 1000L
            val half = bigHalf()
            val game = admin.createBigGame(
                AdminBigGameRequest(
                    title = "Perf mix $run",
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
                        seekingMinutes = ((endsAtMillis - startsAtMillis) / 60_000 + 5).toInt()
                            .coerceIn(BigGameLimits.SEEKING_MINUTES),
                        seekers = (bigPlayers / 10).coerceAtLeast(1),
                    ),
                    playerLimit = bigPlayers,
                    reason = "perf: the Mix simulation",
                ),
            )
            admin.awaitLobby(game.id)
            bigGame = game.id
        }
        println(
            "Mix of $total players in $steps levels of ${levelSeconds}s: rounds $rounds, lobbies $lobbies, " +
                "big game $bigPlayers, start screen $starters, login $logins; $socketPercent % on sockets",
        )
    }

    override fun after() {
        bigGame?.let { id -> runCatching { admin.cancelBigGame(id, "perf: the run is over") } }
        runCatching { liveChannel?.close() }
    }

    /** [users] over the ladder's [levels] (all of them by default), the same share on every level's ramp. */
    private fun ladder(users: Int, levels: Int = steps): List<OpenInjectionStep> {
        if (users <= 0) return emptyList()
        val perLevel = users / levels
        return (0 until levels).flatMap { step ->
            val count = perLevel + if (step < users % levels) 1 else 0
            val ramp = rampUsers(count).during(Duration.ofSeconds(rampSeconds.toLong()))
            if (step == 0) listOf(ramp) else listOf(nothingFor(Duration.ofSeconds(minutes * 60L)), ramp)
        }
    }

    init {
        val populations: List<PopulationBuilder> = listOfNotNull(
            ladder(rounds).takeIf { it.isNotEmpty() }?.let { roundsScenario.injectOpen(it) },
            ladder(lobbies).takeIf { it.isNotEmpty() }?.let { lobbyScenario.injectOpen(it) },
            ladder(bigPlayers, bigSteps).takeIf { it.isNotEmpty() }?.let { bigScenario.injectOpen(it) },
            ladder(starters).takeIf { it.isNotEmpty() }?.let { startScenario.injectOpen(it) },
            ladder(logins).takeIf { it.isNotEmpty() }?.let { loginScenario.injectOpen(it) },
        )
        setUp(populations).protocols(hovankiHttp())
    }

    private companion object {
        /** Mariinskyi Park, Kyiv, like the e2e games; the fake buildings and streets are laid around every center. */
        val ORIGIN = GeoPoint(50.4476, 30.5396)

        /** The big game's square, away from the ordinary games' grid. */
        val CENTER: GeoPoint = ORIGIN.moveBy(eastMeters = -20_000.0, northMeters = -20_000.0)

        const val ROUNDS_PERCENT = 65
        const val LOBBY_PERCENT = 10
        const val BIG_PERCENT = 5
        const val START_PERCENT = 15
        const val LOGIN_PERCENT = 5
        const val REGISTER_PERCENT = 40.0

        const val ZONE_RADIUS_METERS = 500
        const val SQUARE_METERS_PER_PLAYER = 1_000.0
        const val START_PATIENCE_SECONDS = 30
        const val AGAIN_PATIENCE_SECONDS = 30
        const val BIG_LOBBY_MARGIN_SECONDS = 45

        /** A chat message about once a minute: one sync in twenty. */
        const val CHAT_SYNCS = 20

        /** The results stay on the screen this many syncs before the host decides on «Ще раз». */
        const val RESULTS_SYNCS = 5

        /** How long the others wait for the host's next lobby before they find another game. */
        const val AGAIN_WAIT_SYNCS = 10

        const val INBOX_SECONDS = 10L
        const val BROWSE_EVERY = 6

        const val TABLE = "table"
        const val SEAT = "seat"
        const val NAME = "name"
        const val JOIN_CODE = "joinCode"
        const val JOINED_AT = "joinedAt"
        const val FINISHED_SYNCS = "finishedSyncs"
        const val LEAVING = "leaving"
        const val LOGGED_IN_AT = "loggedInAt"
        const val ITERATION = "iteration"
        const val SYNC_STARTED = "syncStarted"
        const val STOP = "stop"
        val SYNC_SECONDS = GameRules().syncIntervalSeconds
        val CHAT_LINES = listOf("тут", "біжу", "де ви?", "ок", "я за школою", "😂", "хто бачив Сашка?", "ще 5 хв")
    }
}
