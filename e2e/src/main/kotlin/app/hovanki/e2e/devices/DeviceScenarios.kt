package app.hovanki.e2e.devices

import app.hovanki.client.automation.TestTags
import app.hovanki.e2e.bot.BotPlayer
import app.hovanki.e2e.route.BuildingSearch
import app.hovanki.e2e.route.offset
import app.hovanki.shared.debug.DebugPlayer
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.totp.catchCodeTotp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
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

/** How far the backgrounded app is moved to see that its locations flow before the walk is measured. */
private const val FIRST_STEP_METERS = 5.0

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
 * Full round with every UI path that matters: the host, logged in, creates the game on the phone, the other phones
 * join by code as guests, bots join through the API, the host picks the seeker; screens follow the phases; the app
 * keeps reporting its position in the background; the map shows the zone's buildings on every phone; a phone hider
 * walks into a building, is warned, seen by the seekers and walks out again; the seeker catches bots (code typed by
 * hand) and phones (code read off the hider's screen and checked against the server); everybody ends on the results
 * screen, and «Back» leads the host to the «Play» tab and the guests to the welcome screen.
 */
private suspend fun DeviceRun.fullRound() = with(scenario) {
    val lineup = setUpGame(seekerOnDevice = true)
    val seeker = checkNotNull(lineup.seekerDevice)

    hide(lineup)
    awaitPhase(GamePhase.SEEKING, within = (HIDING_SECONDS + 30).seconds)
    for (player in devicePlayers) player.awaitVisible(TestTags.phase(GamePhase.SEEKING))
    screenshot("seeking")
    checkMap()

    checkBackgroundTracking(lineup.deviceHiders.firstOrNull() ?: seeker)
    lineup.deviceHiders.firstOrNull()?.let { checkBuildingRule(it, seeker) }
    lineup.deviceHiders.firstOrNull()?.let { checkMyCode(it) }

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
    // «Back» on the results: the host is on the «Play» tab again, still logged in; the guests on the welcome screen.
    for (player in devicePlayers) player.flow("close-results", "UNTIL" to player.startScreen)
    screenshot("back on the start screen")
    checkStartScreensInPlace()
}

/**
 * The start screens begin where they began before the round. After a round (the app in the background, a code typed)
 * iOS once left the app without its safe area: the greeting ran under the clock, the tab bar onto the home indicator.
 */
private suspend fun DeviceRun.checkStartScreensInPlace() = with(scenario) {
    for (player in devicePlayers) {
        val before = player.startScreenTop ?: continue
        val after = player.topOf(player.startScreen)
        check(after == before, "${player.name}'s start screen keeps clear of the status bar (top $before, now $after)")
    }
}

/**
 * The app is killed mid-round and started again. While it is dead, the server keeps the player and reveals their last
 * point to the seekers (stale signal). The relaunched app restores the host's account and resumes the session saved
 * on the device: the game screen again, fresh fixes hide the player, and a claim against them is confirmed with the
 * code on the resumed app's screen, not by the code timeout.
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

/**
 * The host creates the game where it is: at its own location, like a player (or at `--location`). Everything else
 * is placed around the zone center from then on: the other devices, the bots, the hiding spots.
 */
private suspend fun DeviceRun.setUpGame(seekerOnDevice: Boolean): Lineup = with(scenario) {
    val host = devicePlayers.first()
    location?.let(::placeDevices)
    // Only a logged-in player creates games in the app. The account is made through the API (registration, the code
    // from the email); the app logs in with it at start, from its launch options.
    val account = accounts.create(host.name)
    host.account = account
    note("${host.name} has the confirmed account ${account.nickname} (registered through the API)")
    host.launchApp(hidingSeconds = HIDING_SECONDS)
    host.awaitVisible(host.startScreen)
    host.startScreenTop = host.topOf(host.startScreen)
    screenshot("start screen", listOf(host))
    createGameOnDevice(host)
    // The join code on the lobby screen names the game: a timed-out first attempt can leave another one behind.
    val shownCode = host.readText(TestTags.LOBBY_JOIN_CODE)?.filter(Char::isLetterOrDigit)
    val game = eventually("${host.name}'s game is on the server", 30.seconds) {
        observer.games().games.lastOrNull {
            host.playerName in it.playerNames && it.phase == GamePhase.LOBBY &&
                (shownCode == null || it.joinCode == shownCode)
        }
    }
    useGame(game.gameId, game.joinCode)
    origin = state().settings.zone.initial.center
    // To the kilometer: enough to tell where the map is, not where somebody's emulator really sits.
    val around = "%.2f, %.2f".format(Locale.ROOT, origin.lat, origin.lon)
    note(
        when {
            location != null -> "the game is at the given location (--location), around $around"
            host.isPlaced -> "⚠ ${host.name} has no location of its own: the game is at the fallback location"
            else -> "the game is where ${host.name} is: its own location, around $around"
        },
    )
    // From here on the scenario moves the devices: the host stays at the zone center, the others come over.
    placeDevices(origin)
    val seekerDevice = if (seekerOnDevice) devicePlayers.getOrElse(1) { host } else devicePlayers.getOrNull(1)
    val seekerBot = if (seekerDevice == null) player("Bot-seeker", at = origin.offset(eastMeters = -8.0)) else null
    val botHiders = (1..botCount).map { player("Bot-$it", at = origin.offset(northMeters = -8.0 * it)) }

    // The other devices are guests: logged out, they join by code from the welcome screen.
    for (player in devicePlayers.drop(1)) {
        player.launchApp(joinCode = game.joinCode)
        player.awaitVisible(player.startScreen)
        player.startScreenTop = player.topOf(player.startScreen)
        player.flow("join-game")
    }
    for (bot in listOfNotNull(seekerBot) + botHiders) requireOk(bot.join(game.joinCode), "${bot.name} joins")
    val expected = devicePlayers.size + botHiders.size + listOfNotNull(seekerBot).size
    val lobby = eventually("all $expected players are in the lobby") { state().takeIf { it.players.size == expected } }
    for (player in devicePlayers) player.playerId = lobby.players.single { it.name == player.playerName }.id
    check(
        lobby.players.single { it.id == host.id }.userId != null,
        "${host.name} plays under the nickname ${account.nickname}, with the account",
    )
    check(
        devicePlayers.drop(1).all { player -> lobby.players.single { it.id == player.id }.userId == null },
        "the other devices play as guests",
    )
    awaitBuildings(listOfNotNull(seekerBot) + botHiders)
    screenshot("lobby")

    val lineup = Lineup(host, seekerDevice, seekerBot, devicePlayers.filter { it !== seekerDevice }, botHiders)
    host.flow("start-game", "SEEKER_ID" to lineup.seekerId.value)
    awaitPhase(GamePhase.HIDING)
    for (player in devicePlayers) player.awaitVisible(TestTags.phase(GamePhase.HIDING))
    screenshot("hiding")
    lineup
}

/**
 * Tapping Create can fail for two reasons a player would simply try again after, once, with a note in the report:
 * - the host device has no location of its own (a fresh simulator; the app gives up after 20 s): the scenario puts
 *   the devices at [DeviceRun.FALLBACK_LOCATION] first;
 * - on the macOS CI runner the server sometimes answered the first "create game" after more than the app's 15 s
 *   request timeout (access log: 15.9 s, then 0.3 s for the same request): "Cannot reach the server".
 *
 * Any other failure, or a second one, fails the scenario.
 */
private suspend fun DeviceRun.createGameOnDevice(host: DevicePlayer) {
    val firstTry = runCatching { host.flowRetryingLostTap("create-game", tapped = TestTags.HOME_CREATE) }
    val failure = firstTry.exceptionOrNull() ?: return
    if (failure is CancellationException) throw failure
    val screen = runCatching { maestro.hierarchy(host.device) }.getOrNull() ?: throw failure
    val banner = screen.textOf(TestTags.BANNER_ERROR)
    when {
        // On iOS the problem's text is a sibling of the tagged element, not inside it: only its presence counts.
        screen.contains(TestTags.HOME_PROBLEM) && !host.isPlaced -> {
            scenario.note("⚠ ${host.name} got no location fix of its own: using the fallback location")
            placeDevices(DeviceRun.FALLBACK_LOCATION)
        }

        banner != null -> {
            scenario.note(
                "⚠ ${host.name} could not create the game on the first try (\"$banner\"): tapping Create again",
            )
        }

        else -> throw failure
    }
    host.flow("create-game-again")
}

/**
 * The zone's buildings as the server judges them: real ones around the devices (the device runs' default: the map
 * tiles, as in production), or the fake test quarter. The bots' app loads them like the phones' app does, so the
 * scenario reads them there to keep hiding spots in the open. Without data the game runs without the building rule,
 * and the phones say so.
 */
private suspend fun DeviceRun.awaitBuildings(bots: List<BotPlayer>): Unit = with(scenario) {
    // The map data may take a while: a slow tile server, a pause, one more attempt (Overpass: the next instance too).
    val loaded = eventually("the server has looked up the zone's buildings", 150.seconds) {
        state().buildings?.takeIf { it != BuildingsState.LOADING }
    }
    if (loaded != BuildingsState.READY) {
        note("⚠ no building data for the zone ($loaded): the game runs without the building rule")
        for (player in devicePlayers) player.awaitVisible(TestTags.BUILDING_RULE_OFF)
        return
    }
    val bot = bots.firstOrNull() ?: return note("⚠ no bot to read the buildings from: hiding spots may be indoors")
    // The app fetches them with the next snapshot; on the busy macOS runner syncs once took up to 38 s for a minute.
    val response = eventually("${bot.name}'s app loaded the zone's buildings", 90.seconds) { bot.state.buildings }
    buildings = BuildingSearch(response, origin)
    note("the zone has ${response.buildings.size} buildings and ${response.passages.size} passages")
}

/** Hiders walk to spots in the open around the zone center; the seeker stays. */
private fun DeviceRun.hide(lineup: Lineup) {
    val count = lineup.deviceHiders.size + lineup.botHiders.size
    val spots = List(count) { index ->
        val angle = 2 * PI * index / count
        val wanted = origin.offset(eastMeters = 60 * cos(angle), northMeters = 60 * sin(angle))
        buildings?.openSpotNear(wanted) ?: wanted
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
    // Right after the app leaves the screen the device can be sluggish for half a minute (the iOS simulator starts
    // Settings, and `simctl location set` takes seconds meanwhile): a walk started now could be over before its points
    // reach the app, and a phone standing still sends no new fixes. A first step shows that locations flow again; the
    // walk is measured from there.
    player.walkTo(player.truePosition.offset(eastMeters = FIRST_STEP_METERS), speed = 2.0)
    val stepped = eventually("a first step reaches the server from the background", 60.seconds) {
        playerOnServer(player.id).takeIf { it.fixes.accepted > before.fixes.accepted }
    }
    val east = player.truePosition.offset(eastMeters = 40.0)
    val walk = player.walkTo(buildings?.openSpotNear(east, searchMeters = 30.0) ?: east, speed = 2.0)
    delay(25.seconds)
    val after = playerOnServer(player.id)
    check(
        after.fixes.accepted >= stepped.fixes.accepted + 5,
        "fixes keep arriving in the background (${before.fixes.accepted} → ${stepped.fixes.accepted} → " +
            "${after.fixes.accepted})",
    )
    val moved = after.latestFix?.point?.distanceTo(checkNotNull(stepped.latestFix).point) ?: 0.0
    check(
        moved >= minOf(20.0, walk.lengthMeters / 2),
        "the server follows the walk in the background (${moved.toInt()} m)",
    )
    // Seekers always see each other (TEAMMATE); a hider in the background must not show up at all.
    val expected = if (after.role == Role.SEEKER) VisibilityReason.TEAMMATE else null
    check(
        after.revealedToSeekers == expected,
        "a backgrounded app is not revealed as stale (shown to seekers: ${after.revealedToSeekers ?: "no"})",
    )
    player.device.bringAppToFront()
    player.awaitVisible(TestTags.GAME_SCREEN)
}

/**
 * «My code» (one scan): the hider opens the code without a claim, and it is the one the server expects right now; then
 * hides it again. The seeker's side needs a camera, which emulators don't have: bots cover it.
 */
private suspend fun DeviceRun.checkMyCode(hider: DevicePlayer) = with(scenario) {
    hider.flowRetryingLostTap("show-my-code", TestTags.MY_CODE_OPEN)
    val shown = checkNotNull(hider.readText(TestTags.MY_CODE_DIGITS)) { "${hider.name} shows no code" }
        .filter(Char::isDigit)
    screenshot("my code", listOf(hider))
    val server = state()
    val secret = checkNotNull(server.players.single { it.id == hider.id }.catchCodeSecret)
    check(
        catchCodeTotp(secret, server.settings.rules).verify(shown, server.serverTimeMillis),
        "${hider.name}'s «My code» is the code the server expects ($shown)",
    )
    hider.flow("hide-my-code")
}

/**
 * The building rule on a phone, with the zone's real buildings: the hider walks into a building deep enough for the
 * server to be sure, is warned, stays until the seekers see them (never eliminated), and walks out into the open,
 * which lifts both. Skipped with a warning when the game has no building data or no building near the center is deep
 * enough for the device's GPS accuracy.
 */
private suspend fun DeviceRun.checkBuildingRule(hider: DevicePlayer, seeker: DevicePlayer?): Unit = with(scenario) {
    val search = buildings ?: return note("⚠ building rule not checked: the game has no building data")
    val game = state()
    val rules = game.settings.rules
    val zone = game.zone ?: game.settings.zone.initial
    val accuracy = playerOnServer(hider.id).latestUsableFix?.accuracyMeters ?: 0.0
    // Clearly inside takes a fix deeper than accuracy + margin; a few meters more for the walk's last fixes.
    val needed = accuracy + rules.buildingWallMarginMeters + 3.0
    val target = search.insideNear(
        hider.truePosition,
        zone.center,
        (zone.radiusMeters - 60).coerceAtMost(400.0),
        needed,
    )
    if (target == null || target.depthMeters <= needed) {
        val deepest = target?.depthMeters?.roundToInt() ?: 0
        return note("⚠ building rule not checked: no building ${needed.roundToInt()} m deep here (at most $deepest)")
    }
    val depth = target.depthMeters.roundToInt()
    note("${hider.name} walks into a building: $depth m from its walls, fixes ± ${accuracy.roundToInt()} m")
    hider.walkToAndArrive(target.point, speed = 4.0)
    eventually("the server is sure ${hider.name} is inside", 60.seconds) {
        playerOnServer(hider.id).insideBuildingSinceMillis
    }
    hider.awaitVisible(TestTags.GAME_IN_BUILDING)
    screenshot("warned inside a building", listOf(hider))

    eventually("the seekers see ${hider.name} inside", (rules.insideBuildingRevealSeconds + 20).seconds) {
        playerOnServer(hider.id).takeIf { it.revealedToSeekers == VisibilityReason.INSIDE_BUILDING }
    }
    check(playerOnServer(hider.id).status == PlayerStatus.ACTIVE, "${hider.name} is revealed, not eliminated")
    val banner = hider.readText(TestTags.GAME_IN_BUILDING).orEmpty()
    check(!COUNTDOWN.containsMatchIn(banner), "${hider.name}'s app says the seekers see them now (\"$banner\")")
    // Both maps: the hider's dot in a red building, and the seeker's marker "in a building".
    val onMap = listOfNotNull(hider, seeker?.takeIf { it !== hider })
    for (player in onMap) player.scrollAlongEdgeTo(TestTags.MAP_ATTRIBUTION)
    screenshot("seen inside a building", onMap)
    for (player in onMap) player.scrollAlongEdgeTo(TestTags.phase(GamePhase.SEEKING), down = false)

    hider.walkToAndArrive(search.openSpotNear(target.point, searchMeters = 200.0) ?: origin, speed = 4.0)
    eventually("${hider.name} is out again: the seekers no longer see them", 60.seconds) {
        playerOnServer(hider.id).takeIf { it.insideBuildingSinceMillis == null && it.revealedToSeekers == null }
    }
    hider.awaitGone(TestTags.GAME_IN_BUILDING)
}

/** A countdown like "0:45" in the building warning; gone once the seekers see the player. */
private val COUNTDOWN = Regex("""\d:\d\d""")

/**
 * The map on every phone: the app has fetched the buildings it draws (the server notes it), the OpenStreetMap credit
 * is on screen (tiles come from the network); a screenshot, then back to the top of the screen.
 */
private suspend fun DeviceRun.checkMap() = with(scenario) {
    if (state().buildings == BuildingsState.READY) {
        for (player in devicePlayers) {
            eventually("${player.name}'s map has the zone's buildings", 60.seconds) {
                playerOnServer(player.id).buildingsLoadedAtMillis
            }
        }
    }
    for (player in devicePlayers) {
        player.scrollAlongEdgeTo(TestTags.MAP_ATTRIBUTION)
        val credit = player.readText(TestTags.MAP_ATTRIBUTION).orEmpty()
        check("OpenStreetMap" in credit, "${player.name} shows the map credit (\"$credit\")")
    }
    screenshot("map")
    for (player in devicePlayers) player.scrollAlongEdgeTo(TestTags.phase(GamePhase.SEEKING), down = false)
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
