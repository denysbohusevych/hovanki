package app.hovanki.e2e.scenario

import app.hovanki.e2e.bot.BotBehavior
import app.hovanki.e2e.bot.BotPlayer
import app.hovanki.e2e.bot.CommandResult
import app.hovanki.e2e.bot.SyncMetrics
import app.hovanki.e2e.observer.Observer
import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.route.Route
import app.hovanki.shared.debug.DebugCatch
import app.hovanki.shared.debug.DebugGameState
import app.hovanki.shared.debug.DebugPlayer
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.VisibilityReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runs one game against a real server, in real time, and writes its report (see [ScenarioReport]).
 * Fails with the whole timeline in the message, so a red CI run tells what happened.
 * Every scenario also checks that no bot ever received a position it may not see ([Scenario.checkPrivacy]).
 */
fun runScenario(name: String, serverUrl: String, timeout: Duration = 3.minutes, block: suspend Scenario.() -> Unit) {
    val scenario = Scenario(name, serverUrl)
    var failure: Throwable? = null
    try {
        runBlocking {
            withTimeout(timeout) {
                scenario.block()
                scenario.checkPrivacy()
            }
        }
    } catch (e: Throwable) {
        failure = e
    } finally {
        val finalState = runCatching { runBlocking { scenario.gameIdOrNull?.let { scenario.observer.game(it) } } }
        scenario.close()
        ScenarioReport.write(scenario, finalState.getOrNull(), failure)
    }
    if (failure != null) {
        throw AssertionError(
            "Scenario '$name' failed: ${failure.message}\n\nTimeline:\n${scenario.timeline.render()}",
            failure,
        )
    }
}

/**
 * The script of one game: who stands where, who walks where, what they press, and what must happen.
 * Players are [BotPlayer]s running the app's client code; checks read the server's truth via [observer].
 */
class Scenario(val name: String, val serverUrl: String) {
    val timeline = Timeline()
    val observer = Observer(serverUrl)
    val metrics = SyncMetrics()
    private val bots = CopyOnWriteArrayList<BotPlayer>()

    val players: List<BotPlayer> get() = bots.toList()

    @Volatile var gameIdOrNull: GameId? = null
        private set

    val gameId: GameId get() = checkNotNull(gameIdOrNull) { "No game created yet" }

    lateinit var joinCode: String
        private set

    /** A new phone with the app installed, standing at [at]. */
    fun player(
        name: String,
        at: GeoPoint,
        noise: GpsNoise = GpsNoise.openSky(seed = name.hashCode().toLong()),
        behavior: BotBehavior = BotBehavior(),
        clockSkew: Duration = Duration.ZERO,
        logChanges: Boolean = true,
    ): BotPlayer {
        val bot = BotPlayer(name, at, noise, behavior, serverUrl, timeline, metrics, logChanges)
        bot.clock.skewMillis = clockSkew.inWholeMilliseconds
        bots += bot
        return bot
    }

    fun note(text: String) = timeline.log("scenario", text)

    /** A game created elsewhere (on a device), found through the observer. */
    fun useGame(id: GameId, code: String) {
        gameIdOrNull = id
        joinCode = code
        note("game ${id.value}, join code $code")
    }

    // ---- Game setup ----

    suspend fun BotPlayer.createsGame(settings: GameSettings) {
        requireOk(createGame(settings), "$name creates a game")
        val snapshot = checkNotNull(snapshot)
        gameIdOrNull = snapshot.gameId
        joinCode = snapshot.joinCode
        note("game ${snapshot.gameId.value}, join code $joinCode")
    }

    suspend fun join(vararg players: BotPlayer) {
        for (player in players) requireOk(player.join(joinCode), "${player.name} joins")
    }

    suspend fun BotPlayer.startsGame(seekers: List<BotPlayer>) {
        requireOk(startGame(seekers), "$name starts the game")
    }

    // ---- Movement and the phone ----

    fun BotPlayer.walksTo(target: GeoPoint, speed: Double = Route.WALKING): Route {
        val route = gps.walkTo(target, speed)
        log("walks ${route.lengthMeters.roundToInt()} m at $speed m/s")
        return route
    }

    suspend fun BotPlayer.arrives() {
        val left = gps.arrivesAtMillis - System.currentTimeMillis()
        if (left > 0) delay(left)
    }

    suspend fun BotPlayer.walksToAndArrives(target: GeoPoint, speed: Double = Route.WALKING) {
        walksTo(target, speed)
        arrives()
    }

    fun BotPlayer.follows(route: Route) {
        gps.follow(route)
        log("follows a route of ${route.lengthMeters.roundToInt()} m")
    }

    fun BotPlayer.teleportsTo(target: GeoPoint) {
        log("teleports ${gps.truePosition.distanceTo(target).roundToInt()} m (GPS spoofing)")
        gps.teleport(target)
    }

    fun BotPlayer.turnsGpsOff() {
        gps.isEnabled = false
        log("GPS off")
    }

    fun BotPlayer.turnsGpsOn() {
        gps.isEnabled = true
        log("GPS on")
    }

    fun BotPlayer.startsMockingLocation() {
        gps.isMocked = true
        log("mock location on")
    }

    fun BotPlayer.stopsMockingLocation() {
        gps.isMocked = false
        log("mock location off")
    }

    fun BotPlayer.losesNetwork() {
        network.isOnline = false
        log("network down")
    }

    fun BotPlayer.regainsNetwork() {
        network.isOnline = true
        log("network up")
    }

    // ---- Catches ----

    suspend fun BotPlayer.claimsCatch(hider: BotPlayer) = requireOk(claimCatch(hider), "$name claims ${hider.name}")

    /** Reads the code off the hider's screen (waits until it is shown) and types it in. */
    suspend fun BotPlayer.entersCodeShownBy(hider: BotPlayer, within: Duration = 15.seconds) {
        val code = eventually("${hider.name} shows the code", within) { hider.shownCode() }
        requireOk(confirmCatch(code.code), "$name enters the code of ${hider.name}")
    }

    /** Runs to where the hider really is and waits until a few fixes of the new position reached the server. */
    suspend fun BotPlayer.catchesUpWith(hider: BotPlayer, speed: Double = Route.RUNNING) {
        walksToAndArrives(hider.gps.truePosition, speed)
        delay(2.seconds)
    }

    /** The whole catch: run to the hider, claim, read the code, type it in, confirmed. */
    suspend fun BotPlayer.catches(hider: BotPlayer, speed: Double = Route.RUNNING) {
        catchesUpWith(hider, speed)
        claimsCatch(hider)
        entersCodeShownBy(hider)
        awaitCatch(hider, CatchStatus.CONFIRMED)
    }

    // ---- Observations (server truth) ----

    suspend fun state(): DebugGameState = observer.game(gameId)

    suspend fun BotPlayer.onServer(): DebugPlayer = state().players.single { it.id == id }

    suspend fun lastClaimOn(hider: BotPlayer): DebugCatch? = state().catches.lastOrNull { it.hiderId == hider.id }

    suspend fun awaitPhase(phase: GamePhase, within: Duration = 30.seconds): DebugGameState =
        eventually("phase $phase", within) { state().takeIf { it.phase == phase } }

    suspend fun awaitCatch(hider: BotPlayer, status: CatchStatus, within: Duration = 20.seconds): DebugCatch =
        eventually("claim on ${hider.name} is $status", within) { lastClaimOn(hider)?.takeIf { it.status == status } }

    suspend fun awaitStatus(player: BotPlayer, status: PlayerStatus, within: Duration = 20.seconds): DebugPlayer =
        eventually("${player.name} is $status", within) { player.onServer().takeIf { it.status == status } }

    /** [seeker]'s own snapshot shows [hider] with [reason]. */
    suspend fun awaitReveal(hider: BotPlayer, reason: VisibilityReason, to: BotPlayer, within: Duration = 30.seconds) =
        eventually("${to.name} sees ${hider.name} ($reason)", within) {
            to.snapshot?.players?.single { it.id == hider.id }?.location?.takeIf { it.exactReason == reason }
        }

    /** Polls [probe] until it returns non-null; fails after [within]. Logs the check to the timeline. */
    suspend fun <T : Any> eventually(what: String, within: Duration = 30.seconds, probe: suspend () -> T?): T {
        val deadline = System.currentTimeMillis() + within.inWholeMilliseconds
        while (true) {
            val result = probe()
            if (result != null) {
                note("✓ $what")
                return result
            }
            if (System.currentTimeMillis() > deadline) throw AssertionError("Not within $within: $what")
            delay(POLL)
        }
    }

    /** Polls [condition] until it is true; fails after [within]. */
    suspend fun awaitThat(what: String, within: Duration = 30.seconds, condition: suspend () -> Boolean) {
        eventually(what, within) { condition().takeIf { it } }
    }

    /** [condition] stays true for the whole [period] (checked every poll). */
    suspend fun holdsFor(what: String, period: Duration, condition: suspend () -> Boolean) {
        val end = System.currentTimeMillis() + period.inWholeMilliseconds
        while (System.currentTimeMillis() < end) {
            if (!condition()) throw AssertionError("Stopped holding within $period: $what")
            delay(POLL)
        }
        note("✓ $what (for $period)")
    }

    fun check(condition: Boolean, what: String) {
        if (!condition) throw AssertionError(what)
        note("✓ $what")
    }

    fun requireOk(result: CommandResult, what: String) {
        if (result != CommandResult.Ok) throw AssertionError("$what: expected success, got $result")
    }

    fun expectRejected(result: CommandResult, code: ErrorCode, what: String) {
        check(result is CommandResult.Rejected && result.code == code, "$what: rejected with $code (got $result)")
    }

    /** No bot received anything it may not see. Runs at the end of every scenario. */
    fun checkPrivacy() {
        val violations = bots.flatMap { it.privacyViolations }
        if (violations.isNotEmpty()) {
            throw AssertionError("Privacy violations:\n" + violations.distinct().joinToString("\n"))
        }
        note("✓ privacy: no bot received a position it may not see")
    }

    fun close() {
        bots.forEach { it.close() }
        observer.close()
    }

    private companion object {
        val POLL = 250.milliseconds
    }
}
