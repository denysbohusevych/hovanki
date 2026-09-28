package app.hovanki.e2e.devices

import app.hovanki.client.automation.LaunchOptions
import app.hovanki.client.automation.TestTags
import app.hovanki.e2e.bot.BotAccount
import app.hovanki.e2e.route.Route
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PlayerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A player on a real emulator/simulator running the debug app: taps go through Maestro flows. Until [placeAt], the
 * device keeps its own location; from then on the position is fed to the device's GPS once per second along a
 * [Route] (`adb emu geo fix` / `xcrun simctl location set`).
 *
 * With an [account], the app logs in with it at start and the player plays under its nickname; without one the
 * player is a guest named after the device.
 */
class DevicePlayer(val device: Device, private val run: DeviceRun) {
    /** The device's label, e.g. `Android-1`: the timeline's name for this player. */
    val name: String get() = device.label

    /** The account the app logs in with; null: a guest. Set before [launchApp]. */
    @Volatile var account: BotAccount? = null

    /** The player's name in the game (the server's lobby): the nickname when logged in, else the device's label. */
    val playerName: String get() = account?.nickname ?: name

    /** What the app shows outside a game: the «Play» tab when logged in, the welcome screen for a guest. */
    val startScreen: String get() = if (account != null) TestTags.HOME_SCREEN else TestTags.WELCOME_SCREEN

    private class Movement(val route: Route, val startedAtMillis: Long)

    @Volatile private var movement: Movement? = null

    /** Whether the scenario moves this device, see [placeAt]. */
    val isPlaced: Boolean get() = movement != null

    /** From now on the device is at [point], until it walks somewhere else. */
    fun placeAt(point: GeoPoint) {
        movement = Movement(Route.stay(point), System.currentTimeMillis())
    }

    /** Known once the player is in a game (looked up by name on the server). */
    @Volatile var playerId: PlayerId? = null

    val id: PlayerId get() = checkNotNull(playerId) { "$name has not joined a game" }

    val truePosition: GeoPoint
        get() = checkNotNull(movement) { "$name has not been placed" }.let {
            it.route.positionAt(System.currentTimeMillis() - it.startedAtMillis)
        }

    fun walkTo(target: GeoPoint, speed: Double = Route.WALKING): Route {
        val route = Route(listOf(truePosition, target), speed)
        movement = Movement(route, System.currentTimeMillis())
        log("walks ${route.lengthMeters.roundToInt()} m at $speed m/s")
        return route
    }

    suspend fun walkToAndArrive(target: GeoPoint, speed: Double = Route.WALKING) {
        walkTo(target, speed)
        val left = checkNotNull(movement).let {
            it.startedAtMillis + it.route.durationMillis -
                System.currentTimeMillis()
        }
        if (left > 0) delay(left)
    }

    /** Feeds the current position to the device's GPS every second, once placed, until the scope ends. */
    fun startGps(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            if (isPlaced) device.setLocation(truePosition)
            delay(1.seconds)
        }
    }

    /**
     * Starts the app fresh with debug launch options: logged in with [account] (or logged out, as a guest) and the
     * start screen prefilled. A game saved by an earlier run (another scenario on the same device) is dropped, unless
     * [forgetSavedGame] is false: the app then resumes it, like a player opening it again after it was killed.
     */
    fun launchApp(joinCode: String? = null, hidingSeconds: Int? = null, forgetSavedGame: Boolean = true) {
        val options = appLaunchOptions(
            serverUrl = device.serverUrl(run.port),
            guestName = name,
            account = account,
            joinCode = joinCode,
            hidingSeconds = hidingSeconds,
            simulatedLocation = device is IosDevice,
            forgetSavedGame = forgetSavedGame,
        )
        device.launchApp(options)
        val who = account?.let { "logged in as ${it.nickname}" } ?: "as a guest"
        log(if (forgetSavedGame) "app launched ($who)" else "app launched again ($who)")
    }

    fun killApp() {
        device.stopApp()
        log("app killed")
    }

    /** Runs a Maestro flow from `e2e/maestro/`; fails the scenario if the flow fails. */
    suspend fun flow(flow: String, vararg env: Pair<String, String>, timeout: Duration = 5.minutes) {
        val result = run.maestro.run(device, flow, env.toMap(), timeout)
        if (!result.ok) {
            run.screenshot("failed-$flow", listOf(this))
            val output = (result.stdout + "\n" + result.stderr).lines().filter { it.isNotBlank() }.takeLast(15)
            val actions = run.maestro.actionsLog(result)
            val screen = runCatching {
                run.maestro.hierarchy(device).describe()
            }.getOrElse { "(no hierarchy: ${it.message})" }
            throw AssertionError(
                "$name: Maestro flow '$flow' failed:\n" + output.joinToString("\n") +
                    (if (actions.isEmpty()) "" else "\nMaestro log:\n" + actions.joinToString("\n") { "  $it" }) +
                    "\nOn screen:\n$screen",
            )
        }
        log("UI: $flow${if (env.isEmpty()) "" else " " + env.joinToString { "${it.first}=${it.second}" }}")
    }

    /**
     * Runs [flow] whose first step taps [tapped]; if it fails with [tapped] still on screen and no error shown, the
     * tap had no effect and the flow runs once more, with a warning in the report. On the iOS simulator under Maestro
     * a tap on a Compose button sometimes never becomes a click: UIKit delivers the touch, onClick does not run.
     */
    suspend fun flowRetryingLostTap(flow: String, tapped: String, vararg env: Pair<String, String>) {
        val failure = runCatching { flow(flow, *env) }.exceptionOrNull() ?: return
        if (failure is CancellationException) throw failure
        val screen = runCatching { run.maestro.hierarchy(device) }.getOrNull()
        // An error or a problem on screen means the tap did work.
        val problem = screen?.let { it.contains(TestTags.BANNER_ERROR) || it.contains(TestTags.HOME_PROBLEM) }
        if (screen == null || !screen.contains(tapped) || problem == true) throw failure
        run.scenario.note("⚠ $name: the tap on $tapped had no effect, tapping again")
        flow(flow, *env)
    }

    /** Waits until the element with test tag [id] is on screen (Maestro `extendedWaitUntil`). */
    suspend fun awaitVisible(id: String) {
        flow("await-visible", "ID" to id)
        run.scenario.note("✓ $name shows $id")
    }

    /** Waits until the element with test tag [id] is gone from the screen. */
    suspend fun awaitGone(id: String) {
        flow("await-gone", "ID" to id)
        run.scenario.note("✓ $name no longer shows $id")
    }

    /**
     * Scrolls the screen [down] (or back up) until the element with test tag [id] is visible. Swipes along the edge
     * of the screen: one that starts on the map pans the map instead of scrolling.
     */
    suspend fun scrollAlongEdgeTo(id: String, down: Boolean = true) {
        flow(if (down) "scroll-edge-down" else "scroll-edge-up", "ID" to id)
        run.scenario.note("✓ $name shows $id")
    }

    /** Text of the element with test tag [id] on the current screen. */
    suspend fun readText(id: String): String? = run.maestro.hierarchy(device).textOf(id)

    /** The top edge of the element with test tag [id] on the current screen; null when it is not there. */
    suspend fun topOf(id: String): Double? = run.maestro.hierarchy(device).find(id)?.top

    /** Where the top of [startScreen] was when the scenario first saw it: the screen must not move after a round. */
    @Volatile var startScreenTop: Double? = null

    fun log(text: String) = run.timeline.log(name, text)
}

/**
 * The debug launch options ([LaunchOptions] keys without the `hovanki.` prefix) that start the app for one player:
 * - with [account]: its nickname and password, so the app logs in at start (it keeps a restored session of the same
 *   account, e.g. after a kill, and replaces one of another account);
 * - without: `logOut` (an account left on the device by an earlier scenario is dropped) and [guestName] for the
 *   welcome screen, where a guest joins by code.
 * [simulatedLocation]: an iOS simulator, whose every location is flagged as simulated.
 */
fun appLaunchOptions(
    serverUrl: String,
    guestName: String,
    account: BotAccount?,
    joinCode: String? = null,
    hidingSeconds: Int? = null,
    simulatedLocation: Boolean = false,
    forgetSavedGame: Boolean = true,
): Map<String, String> = buildMap {
    put(LaunchOptions.SERVER, serverUrl)
    if (account != null) {
        put(LaunchOptions.NAME, account.nickname)
        put(LaunchOptions.PASSWORD, account.password)
    } else {
        put(LaunchOptions.NAME, guestName)
        put(LaunchOptions.LOG_OUT, "true")
    }
    joinCode?.let { put(LaunchOptions.JOIN_CODE, it) }
    hidingSeconds?.let { put(LaunchOptions.HIDING_SECONDS, it.toString()) }
    if (simulatedLocation) put(LaunchOptions.ALLOW_SIMULATED_LOCATION, "true")
    if (forgetSavedGame) put(LaunchOptions.FORGET_SAVED_GAME, "true")
}
