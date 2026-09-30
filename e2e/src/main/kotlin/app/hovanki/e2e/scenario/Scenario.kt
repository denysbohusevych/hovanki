package app.hovanki.e2e.scenario

import app.hovanki.client.network.AdaptiveGameConnection
import app.hovanki.e2e.bot.BotAccount
import app.hovanki.e2e.bot.BotBehavior
import app.hovanki.e2e.bot.BotPlayer
import app.hovanki.e2e.bot.BotTransport
import app.hovanki.e2e.bot.CommandResult
import app.hovanki.e2e.bot.LabBot
import app.hovanki.e2e.bot.RadioWorld
import app.hovanki.e2e.bot.SyncMetrics
import app.hovanki.e2e.observer.EmailPurpose
import app.hovanki.e2e.observer.Observer
import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.route.Route
import app.hovanki.shared.debug.DebugCatch
import app.hovanki.shared.debug.DebugEmail
import app.hovanki.shared.debug.DebugGameState
import app.hovanki.shared.debug.DebugPlayer
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.Audience
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.GroupView
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.PlaceItemRequest
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.QuestId
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.rules.QuestCatalog
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
                // A run on the live channel: every scenario's bots sync over sockets.
                if (E2eTransport.isSocket) scenario.observer.enableFeatures(listOf(ServerFeature.LIVE_SOCKET.name))
                scenario.block()
                scenario.checkPrivacy()
                scenario.checkNoServerErrors()
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

    /** The air between the phones' Bluetooth (docs/adr/0012-nearby-radar.md). */
    val radio = RadioWorld()
    private val bots = CopyOnWriteArrayList<BotPlayer>()
    private val labBots = CopyOnWriteArrayList<LabBot>()

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
        platform: Platform = Platform.ANDROID,
        transport: BotTransport = BotTransport.APP,
        pollAfterSocketFailure: Duration = AdaptiveGameConnection.POLL_AFTER_FAILURE_MILLIS.milliseconds,
    ): BotPlayer {
        val bot = BotPlayer(
            name,
            at,
            noise,
            behavior,
            serverUrl,
            timeline,
            metrics,
            logChanges,
            platform,
            radio,
            transport,
            pollAfterSocketFailure.inWholeMilliseconds,
        )
        bot.clock.skewMillis = clockSkew.inWholeMilliseconds
        bots += bot
        return bot
    }

    /**
     * A phone with the debug build's radio lab open, standing at [at] in the hand, for a run on the server
     * (docs/adr/0017-radar-techniques-and-big-run.md §5): it hears the other phones of the scenario through [radio].
     * [label] is its name in the run.
     */
    fun labPhone(label: String, at: GeoPoint, platform: Platform = Platform.ANDROID): LabBot =
        LabBot(label, at, platform, serverUrl, radio, timeline).also { labBots += it }

    /** The same person on another phone: the app freshly installed there, nothing saved; they log in by hand. */
    fun BotPlayer.newPhone(phoneName: String = "$name (new phone)"): BotPlayer =
        player(phoneName, at = gps.truePosition, behavior = behavior)

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

    // ---- Accounts ----

    /** A new account for a person called [name], with a nickname and an email nobody else has ([BotAccount.unique]). */
    fun newAccount(name: String): BotAccount = BotAccount.unique(name)

    /**
     * The code in the next [purpose] email to [email], read like a person reads their inbox: the first email that is
     * not among [known] (the inbox before the action that sends it). Waits for it: the server sends asynchronously.
     */
    suspend fun emailedCode(email: String, purpose: EmailPurpose, known: List<DebugEmail> = emptyList()): String {
        val sent = observer.awaitEmail(email, purpose, known)
        note("✓ $purpose email to $email with code ${sent.code}")
        return checkNotNull(sent.code)
    }

    /**
     * Registers [account]: logged in and usable right away (games under the nickname, friends, groups, invites). The
     * email stays unconfirmed, as most players leave it; [confirmsEmail] confirms it.
     */
    suspend fun BotPlayer.signsUp(account: BotAccount = newAccount(name)): BotAccount {
        requireOk(register(account), "$name registers")
        return account
    }

    /**
     * Confirms the email of the logged-in account (optional, whenever the player likes) with the code of the first
     * verification email to it that is not among [known]: the one sent at registration, or, with the inbox before a
     * resend or an email change as [known], the new one.
     */
    suspend fun BotPlayer.confirmsEmail(known: List<DebugEmail> = emptyList()) {
        val email = checkNotNull(user?.email) { "$name is not logged in" }
        requireOk(verifyEmail(emailedCode(email, EmailPurpose.VERIFY_EMAIL, known)), "$name confirms the email")
    }

    /** Logs in with [account]'s password, by its nickname or by [login] (e.g. the email). */
    suspend fun BotPlayer.logsIn(account: BotAccount, login: String = account.nickname) {
        requireOk(logIn(login, account.password), "$name logs in as $login")
    }

    /** "Forgot password?" on this phone: a code by email, then the new password; logged in with it afterwards. */
    suspend fun BotPlayer.resetsPassword(account: BotAccount, newPassword: String): BotAccount {
        val known = observer.emails(account.email)
        requireOk(requestPasswordReset(account.email), "$name asks for a reset code")
        val code = emailedCode(account.email, EmailPurpose.RESET_PASSWORD, known)
        requireOk(resetPassword(account.email, code, newPassword), "$name sets a new password")
        return account.withPassword(newPassword)
    }

    // ---- Friends and groups ----

    /** Asks [other] to be friends by nickname, [other] accepts: friends on both sides, both lists up to date. */
    suspend fun BotPlayer.befriends(other: BotPlayer) {
        val me = checkNotNull(userId) { "$name is not logged in" }
        val nickname = checkNotNull(other.user?.nickname) { "${other.name} is not logged in" }
        requireOk(sendFriendRequest(nickname), "$name asks ${other.name} to be friends")
        requireOk(other.acceptFriendRequest(me), "${other.name} accepts $name's friend request")
        requireOk(refreshFriends(), "$name reloads the friends list")
    }

    /** A new group owned by this player with [members] (its friends), as the owner's app shows it. */
    suspend fun BotPlayer.createsGroup(groupName: String, members: List<BotPlayer>): GroupView {
        val memberIds = members.map { checkNotNull(it.userId) { "${it.name} is not logged in" } }
        requireOk(createGroup(groupName, memberIds), "$name creates the group $groupName")
        return groups.single { it.name == groupName && it.ownerId == userId }
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

    /** The server gets the next request to [pathSuffix] and answers, the answer never reaches the phone. */
    fun BotPlayer.losesResponseTo(pathSuffix: String, times: Int = 1) {
        network.loseResponseTo(pathSuffix, times)
        log("the next ${if (times == 1) "response" else "$times responses"} to …$pathSuffix will be lost")
    }

    /** Every request waits [latency] before it goes out. */
    fun BotPlayer.hasSlowNetwork(latency: Duration) {
        network.latency = latency
        log("slow network: +$latency per request")
    }

    /** [rate] of the requests fail before reaching the server (drawn from [seed]). */
    fun BotPlayer.hasFlakyNetwork(rate: Double, seed: Long = name.hashCode().toLong()) {
        network.failRequests(rate, seed)
        log("flaky network: ${(rate * 100).roundToInt()} % of requests fail")
    }

    /** No latency and no failures any more. */
    fun BotPlayer.hasGoodNetwork() {
        network.latency = Duration.ZERO
        network.failRequests(0.0)
        log("good network again")
    }

    /** The phone's clock is set [by] further (a manual change, a time zone mistake, an NTP correction). */
    fun BotPlayer.changesClockBy(by: Duration) {
        clock.skewMillis += by.inWholeMilliseconds
        log("phone clock moved by $by (now off by ${clock.skewMillis.milliseconds})")
    }

    // ---- The radar (docs/adr/0012-nearby-radar.md) ----

    /**
     * Every game feature on, as the operator would switch them in the admin; the games still pick their own. The live
     * channel stays as the run has it ([E2eTransport]): it changes how every scenario on the server syncs.
     */
    suspend fun enableAllFeatures() {
        observer.setFeatures(ServerFeature.entries.filter { it != ServerFeature.LIVE_SOCKET }.map { it.name })
        note("every server feature is on")
    }

    fun BotPlayer.putsPhoneInPocket() {
        putInPocket(true)
        log("puts the phone in the pocket")
    }

    fun BotPlayer.takesPhoneOut() {
        putInPocket(false)
        log("takes the phone out")
    }

    fun BotPlayer.turnsBluetoothOff() {
        turnBluetooth(false)
        log("Bluetooth off")
    }

    fun BotPlayer.turnsBluetoothOn() {
        turnBluetooth(true)
        log("Bluetooth on")
    }

    /** A seeker's radar about [other] as their own app shows it. */
    fun BotPlayer.radarBandOn(other: BotPlayer): RadarBand =
        snapshot?.me?.radar?.contacts?.firstOrNull { it.playerId == other.id }?.band ?: RadarBand.NONE

    suspend fun awaitBand(seeker: BotPlayer, hider: BotPlayer, band: RadarBand, within: Duration = 30.seconds) =
        eventually("${seeker.name}'s radar says $band about ${hider.name}", within) {
            seeker.radarBandOn(hider).takeIf { it == band }
        }

    // ---- The board (docs/adr/0013-quests-sparks-and-sensors.md) ----

    suspend fun BotPlayer.placesItem(request: PlaceItemRequest) =
        requireOk(placeItem(request), "$name places ${request.kind}")

    suspend fun BotPlayer.scansCheckpoint(text: String) = requireOk(scanCheckpoint(text), "$name scans a checkpoint")

    suspend fun BotPlayer.usesPerk(perk: PerkKind, target: BotPlayer? = null, point: GeoPoint? = null) =
        requireOk(usePerk(perk, target?.id, point), "$name uses $perk")

    suspend fun BotPlayer.addsQuest(
        text: String,
        audience: Audience = Audience.ALL,
        sparks: Int = QuestCatalog.CUSTOM_DEFAULT_SPARKS,
    ) = requireOk(addQuest(text, audience, sparks), "$name adds a quest")

    suspend fun BotPlayer.saysQuestDone(questId: QuestId) = requireOk(questDone(questId), "$name says it is done")

    suspend fun BotPlayer.reviewsQuest(questId: QuestId, player: BotPlayer, approved: Boolean) =
        requireOk(reviewQuest(questId, player.id, approved), "$name reviews ${player.name}'s quest")

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

    /** Waits until the server's clock reaches [atMillis] (server time, as in every timestamp of the game). */
    suspend fun awaitServerTime(atMillis: Long) {
        val left = atMillis - state().serverTimeMillis
        if (left > 0) delay(left.milliseconds)
    }

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

    /**
     * Presses again while the network fails ([CommandResult.Failed]), like a person on a bad network; a refusal by the
     * server or [attempts] failures in a row fail the scenario.
     */
    suspend fun pressesUntilOk(what: String, attempts: Int = 10, action: suspend () -> CommandResult) {
        repeat(attempts) {
            when (val result = action()) {
                CommandResult.Ok -> return
                is CommandResult.Failed -> delay(1.seconds)
                is CommandResult.Rejected -> throw AssertionError("$what: expected success, got $result")
            }
        }
        throw AssertionError("$what: still failing after $attempts attempts")
    }

    fun expectRejected(result: CommandResult, code: ErrorCode, what: String) {
        check(result is CommandResult.Rejected && result.code == code, "$what: rejected with $code (got $result)")
    }

    fun expectRejected(result: CommandResult, reason: ErrorReason, what: String) {
        check(result is CommandResult.Rejected && result.reason == reason, "$what: rejected with $reason (got $result)")
    }

    /** No bot received anything it may not see. Runs at the end of every scenario. */
    fun checkPrivacy() {
        val violations = bots.flatMap { it.privacyViolations }
        if (violations.isNotEmpty()) {
            throw AssertionError("Privacy violations:\n" + violations.distinct().joinToString("\n"))
        }
        note("✓ privacy: no bot received a position or a chat message it may not see")
    }

    /** The server never failed: no bot got a 5xx (the observer fails on any error by itself). After [checkPrivacy]. */
    fun checkNoServerErrors() {
        val errors = metrics.serverErrors
        if (errors.isNotEmpty()) {
            throw AssertionError("The server failed ${errors.size} times:\n" + errors.distinct().joinToString("\n"))
        }
        note("✓ no server errors (5xx)")
    }

    fun close() {
        bots.forEach { it.close() }
        labBots.forEach { it.close() }
        radio.close()
        observer.close()
    }

    private companion object {
        val POLL = 250.milliseconds
    }
}
