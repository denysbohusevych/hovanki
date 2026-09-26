package app.hovanki.e2e.devices

import app.hovanki.client.automation.TestTags
import app.hovanki.e2e.bot.BotPlayer
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.shared.debug.DebugPlayer
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.totp.catchCodeTotp
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Scenarios on emulators/simulators, by the name `e2e/run-devices.sh --scenario` takes. */
object DeviceScenarios {
    val all: Map<String, suspend DeviceRun.() -> Unit> = linkedMapOf(
        "full-round" to { fullRound() },
        "restart" to { restartMidRound() },
    )
}

/**
 * Hiding phase of the device-created games: long enough for everybody to walk to their spot, and for every device to
 * show the hiding screen (one Maestro check per device, ~15 s each on a busy CI emulator; 30 s once lost that race).
 */
private const val HIDING_SECONDS = 60

private val PARK = GameSetups.PARK

/** Who plays what: the first device hosts; the seeker is a device if there is one for it, otherwise a bot. */
private class Lineup(
    val host: DevicePlayer,
    val seekerDevice: DevicePlayer?,
    val seekerBot: BotPlayer?,
    val deviceHiders: List<DevicePlayer>,
    val botHiders: List<BotPlayer>,
) {
    val seekerId: PlayerId get() = seekerDevice?.id ?: checkNotNull(seekerBot).id
}

/**
 * Full round with every UI path that matters: the host creates the game on the phone, phones join by code, bots
 * join through the API, the host picks the seeker; screens follow the phases; the app keeps reporting its position
 * in the background; the seeker catches bots (code typed by hand) and phones (code read off the hider's screen and
 * checked against the server); everybody ends on the results screen.
 */
private suspend fun DeviceRun.fullRound() = with(scenario) {
    val lineup = setUpGame(seekerOnDevice = true)
    val seeker = checkNotNull(lineup.seekerDevice)

    hide(lineup)
    awaitPhase(GamePhase.SEEKING, within = (HIDING_SECONDS + 30).seconds)
    for (player in devicePlayers) player.awaitVisible(TestTags.phase(GamePhase.SEEKING))
    screenshot("seeking")

    checkBackgroundTracking(lineup.deviceHiders.firstOrNull() ?: seeker)

    for (bot in lineup.botHiders) {
        seeker.catchesUpWith(bot.gps.truePosition)
        seeker.flowRetryingLostTap("claim-catch", TestTags.claimButton(bot.id), "HIDER_ID" to bot.id.value)
        val code = eventually("${bot.name} shows the code") { bot.shownCode() }
        seeker.flow("enter-code", "CODE" to code.code)
        awaitClaim(bot.id, CatchStatus.CONFIRMED)
    }
    for (hider in lineup.deviceHiders) {
        seeker.catchesUpWith(hider.truePosition)
        seeker.flowRetryingLostTap("claim-catch", TestTags.claimButton(hider.id), "HIDER_ID" to hider.id.value)
        hider.awaitVisible(TestTags.CATCH_CODE)
        val shown = checkNotNull(hider.readText(TestTags.CATCH_CODE)) {
            "${hider.name} shows no code"
        }.filter(Char::isDigit)
        screenshot("code on the hider's screen", listOf(hider, seeker))
        val server = state()
        val secret = checkNotNull(server.players.single { it.id == hider.id }.catchCodeSecret)
        check(
            catchCodeTotp(secret, server.settings.rules).verify(shown, server.serverTimeMillis),
            "${hider.name}'s screen shows the code the server expects ($shown)",
        )
        seeker.flow("enter-code", "CODE" to shown)
        awaitClaim(hider.id, CatchStatus.CONFIRMED)
    }

    awaitPhase(GamePhase.FINISHED)
    for (player in devicePlayers) player.awaitVisible(TestTags.RESULTS_SCREEN)
    screenshot("results")
}

/**
 * The app is killed mid-round and started again. While it is dead, the server keeps the player and reveals their last
 * point to the seekers (stale signal). The relaunched app resumes the session saved on the device: the game screen
 * again, fresh fixes hide the player, and a claim against them is confirmed with the code on the resumed app's screen,
 * not by the code timeout.
 */
private suspend fun DeviceRun.restartMidRound() = with(scenario) {
    val lineup = setUpGame(seekerOnDevice = false)
    val host = lineup.host
    hide(lineup)
    val seeking = awaitPhase(GamePhase.SEEKING, within = (HIDING_SECONDS + 30).seconds)
    val rules = seeking.settings.rules
    host.awaitVisible(TestTags.phase(GamePhase.SEEKING))

    host.killApp()
    check(playerOnServer(host.id).status == PlayerStatus.ACTIVE, "the server still counts ${host.name} in")
    eventually(
        "seekers see ${host.name}'s last point (stale signal)",
        (rules.staleLocationRevealSeconds + 20).seconds,
    ) {
        playerOnServer(host.id).takeIf { it.revealedToSeekers == VisibilityReason.STALE_SIGNAL }
    }
    lineup.seekerDevice?.let { screenshot("seeker sees the stale signal", listOf(it)) }

    host.launchApp(forgetSavedGame = false)
    host.awaitVisible(TestTags.phase(GamePhase.SEEKING))
    screenshot("resumed after the restart", listOf(host))
    eventually("fresh fixes hide ${host.name} from the seekers again", 60.seconds) {
        playerOnServer(host.id).takeIf { it.revealedToSeekers == null }
    }

    val seekerDevice = lineup.seekerDevice
    val seekerBot = lineup.seekerBot
    if (seekerDevice != null) {
        seekerDevice.catchesUpWith(host.truePosition)
        seekerDevice.flowRetryingLostTap("claim-catch", TestTags.claimButton(host.id), "HIDER_ID" to host.id.value)
    } else {
        val bot = checkNotNull(seekerBot)
        bot.gps.walkTo(host.truePosition, 4.0)
        delay(bot.gps.arrivesAtMillis - System.currentTimeMillis() + 3_000)
        requireOk(bot.claimCatch(host.id, host.name), "${bot.name} claims ${host.name}")
    }
    host.awaitVisible(TestTags.CATCH_CODE)
    val shown = checkNotNull(host.readText(TestTags.CATCH_CODE)) { "${host.name} shows no code" }.filter(Char::isDigit)
    screenshot("code on the resumed app", listOfNotNull(host, seekerDevice))
    if (seekerDevice != null) {
        seekerDevice.flow("enter-code", "CODE" to shown)
    } else {
        requireOk(
            checkNotNull(seekerBot).confirmCatch(shown),
            "${seekerBot.name} enters the code on ${host.name}'s screen",
        )
    }
    val claim = awaitClaim(host.id, CatchStatus.CONFIRMED)
    check(
        claim.deadlineMillis < claim.createdAtMillis + rules.catchCodeTimeoutSeconds * 1000L,
        "confirmed by the code on ${host.name}'s screen, before the code timeout",
    )
    check(playerOnServer(host.id).status == PlayerStatus.CAUGHT, "${host.name} is caught")
}

private suspend fun DeviceRun.setUpGame(seekerOnDevice: Boolean): Lineup = with(scenario) {
    val host = devicePlayers.first()
    val seekerDevice = if (seekerOnDevice) devicePlayers.getOrElse(1) { host } else devicePlayers.getOrNull(1)
    val seekerBot = if (seekerDevice == null) player("Bot-seeker", at = PARK.offset(eastMeters = -8.0)) else null
    val botHiders = (1..botCount).map { player("Bot-$it", at = PARK.offset(northMeters = -8.0 * it)) }

    host.launchApp(hidingSeconds = HIDING_SECONDS)
    host.awaitVisible(TestTags.HOME_SCREEN)
    screenshot("start screen", listOf(host))
    createGameOnDevice(host)
    // The join code on the lobby screen names the game: a timed-out first attempt can leave another one behind.
    val shownCode = host.readText(TestTags.LOBBY_JOIN_CODE)?.filter(Char::isLetterOrDigit)
    val game = eventually("${host.name}'s game is on the server", 30.seconds) {
        observer.games().games.lastOrNull {
            host.name in it.playerNames && it.phase == GamePhase.LOBBY &&
                (shownCode == null || it.joinCode == shownCode)
        }
    }
    useGame(game.gameId, game.joinCode)

    for (player in devicePlayers.drop(1)) {
        player.launchApp(joinCode = game.joinCode)
        player.awaitVisible(TestTags.HOME_SCREEN)
        player.flow("join-game")
    }
    for (bot in listOfNotNull(seekerBot) + botHiders) requireOk(bot.join(game.joinCode), "${bot.name} joins")
    val expected = devicePlayers.size + botHiders.size + listOfNotNull(seekerBot).size
    val lobby = eventually("all $expected players are in the lobby") { state().takeIf { it.players.size == expected } }
    for (player in devicePlayers) player.playerId = lobby.players.single { it.name == player.name }.id
    screenshot("lobby")

    val lineup = Lineup(host, seekerDevice, seekerBot, devicePlayers.filter { it !== seekerDevice }, botHiders)
    host.flow("start-game", "SEEKER_ID" to lineup.seekerId.value)
    awaitPhase(GamePhase.HIDING)
    for (player in devicePlayers) player.awaitVisible(TestTags.phase(GamePhase.HIDING))
    screenshot("hiding")
    lineup
}

/**
 * On the macOS CI runner the server sometimes answered the app's first "create game" after more than the app's 15 s
 * request timeout (access log: 15.9 s, then 0.3 s for the same request): the app shows "Cannot reach the server".
 * A player would tap Create again, and so does the scenario, once and with a note in the report; any other failure,
 * or a second one, fails the scenario.
 */
private suspend fun DeviceRun.createGameOnDevice(host: DevicePlayer) {
    val firstTry = runCatching { host.flowRetryingLostTap("create-game", tapped = TestTags.HOME_CREATE) }
    val failure = firstTry.exceptionOrNull() ?: return
    if (failure is CancellationException) throw failure
    val banner = runCatching { maestro.hierarchy(host.device).textOf(TestTags.BANNER_ERROR) }.getOrNull()
    if (banner == null) throw failure
    scenario.note("⚠ ${host.name} could not create the game on the first try (\"$banner\"): tapping Create again")
    host.flow("create-game-again")
}

/** Hiders walk to spots around the park; the seeker stays. */
private fun DeviceRun.hide(lineup: Lineup) {
    val spots = List(lineup.deviceHiders.size + lineup.botHiders.size) { index ->
        val angle = 2 * PI * index / (lineup.deviceHiders.size + lineup.botHiders.size)
        PARK.offset(eastMeters = 60 * cos(angle), northMeters = 60 * sin(angle))
    }
    lineup.deviceHiders.forEachIndexed { index, hider -> hider.walkTo(spots[index], speed = 3.0) }
    lineup.botHiders.forEachIndexed { index, bot -> bot.gps.walkTo(spots[lineup.deviceHiders.size + index], 3.0) }
}

/** The app goes to the background; its position must keep reaching the server (foreground service / location mode). */
private suspend fun DeviceRun.checkBackgroundTracking(player: DevicePlayer) = with(scenario) {
    val before = playerOnServer(player.id)
    player.device.sendAppToBackground()
    player.log("app in the background")
    delay(3.seconds)
    screenshot("app in the background", listOf(player))
    player.walkTo(player.truePosition.offset(eastMeters = 40.0), speed = 2.0)
    delay(25.seconds)
    val after = playerOnServer(player.id)
    check(
        after.fixes.accepted >= before.fixes.accepted + 5,
        "fixes keep arriving in the background (${before.fixes.accepted} → ${after.fixes.accepted})",
    )
    val moved = after.latestFix?.point?.distanceTo(checkNotNull(before.latestFix).point) ?: 0.0
    check(moved >= 20.0, "the server follows the walk in the background (${moved.toInt()} m)")
    // Seekers always see each other (TEAMMATE); a hider in the background must not show up at all.
    val expected = if (after.role == Role.SEEKER) VisibilityReason.TEAMMATE else null
    check(
        after.revealedToSeekers == expected,
        "a backgrounded app is not revealed as stale (shown to seekers: ${after.revealedToSeekers ?: "no"})",
    )
    player.device.bringAppToFront()
    player.awaitVisible(TestTags.GAME_SCREEN)
}

private suspend fun DevicePlayer.catchesUpWith(target: GeoPoint) {
    walkToAndArrive(target, speed = 4.0)
    // A few fixes of the new position have to reach the server before the claim.
    delay(6.seconds)
}

private suspend fun DeviceRun.playerOnServer(id: PlayerId): DebugPlayer = scenario.state().players.single {
    it.id == id
}

private suspend fun DeviceRun.awaitClaim(hiderId: PlayerId, status: CatchStatus, within: Duration = 30.seconds) =
    scenario.eventually("claim on ${scenario.state().players.single { it.id == hiderId }.name} is $status", within) {
        scenario.state().catches.lastOrNull { it.hiderId == hiderId }?.takeIf { it.status == status }
    }
