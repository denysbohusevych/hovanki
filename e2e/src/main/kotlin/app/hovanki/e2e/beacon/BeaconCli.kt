package app.hovanki.e2e.beacon

import app.hovanki.client.device.DeviceInfo
import app.hovanki.client.location.LocationProvider
import app.hovanki.client.network.HttpGameApi
import app.hovanki.client.network.PollingGameConnection
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.createHttpClient
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.session.ServerClock
import app.hovanki.client.storage.ClientStorage
import app.hovanki.e2e.bot.FakeBackgroundTracker
import app.hovanki.e2e.bot.PhoneStorage
import app.hovanki.e2e.cli.CliArgs
import app.hovanki.e2e.cli.parseLocation
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.rules.stateAt
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * `e2e beacon` (docs/e2e-local.md, «Ноутбук вместо второго телефона»): a MacBook joins a game by its code as one more
 * player and takes part in the radar with its own Bluetooth ([MacRadio]), so one phone is enough to see whether the
 * radar hears and is heard. The player is the app's client code ([GameSessionManager]), like the e2e bots; it stands
 * still in the middle of the zone (or at `--at`), never catches and never shows a code. The host picks its role in
 * the lobby. Prints what the radar says; Ctrl+C leaves.
 *
 * Start it with `e2e/mac-beacon/run.sh --join <code>`, which builds the Bluetooth helper and passes `--helper` and
 * `--server`.
 */
fun main(args: Array<String>) {
    exitProcess(BeaconCli.run(CliArgs(args.toList())))
}

object BeaconCli {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun run(options: CliArgs): Int {
        val code = options.single("join")?.trim()?.uppercase()
        val serverUrl = options.single("server")
        val helper = options.single("helper")?.let(::File)
        if (code.isNullOrEmpty() || serverUrl.isNullOrEmpty() || helper == null) {
            System.err.println(USAGE)
            return 2
        }
        require(helper.canExecute()) { "No Bluetooth helper at $helper: start through e2e/mac-beacon/run.sh" }
        val name = options.single("name") ?: "MacBook"
        val fixedAt = options.single("at")?.let(::parseLocation)

        // The app's "main thread", as in the bots.
        val mainThread = Dispatchers.Default.limitedParallelism(1)
        val scope = CoroutineScope(SupervisorJob() + mainThread)
        val radio = MacRadio(helper, ::say)
        val location = StandStill(fixedAt)
        val api = HttpGameApi(createHttpClient(OkHttp.create(), logRequests = false), ServerUrl(serverUrl))
        val session = GameSessionManager(
            api,
            PollingGameConnection(api),
            ServerClock(),
            location,
            FakeBackgroundTracker(),
            ServerUrl(serverUrl),
            ClientStorage(PhoneStorage()),
            scope,
            radio = radio,
            // No model: the server's readings by phone model stay the phones'.
            deviceInfo = DeviceInfo.Unknown,
        )
        Runtime.getRuntime().addShutdownHook(
            Thread({
                // Ctrl+C: out of the game, so the host doesn't wait for a player that is gone.
                val state = session.state.value
                val playing = state.session?.takeIf { state.snapshot?.phase != GamePhase.FINISHED }
                if (playing !=
                    null
                ) {
                    runBlocking { withTimeoutOrNull(LEAVE_TIMEOUT_MILLIS) { runCatching { api.leave(playing) } } }
                }
                radio.close()
            }, "beacon-exit"),
        )

        return runBlocking {
            say("Bluetooth helper started, waiting for it to say it is on…")
            val joined = withContext(mainThread) { session.join(code, name) }
            if (!joined) {
                say("Could not join $code: ${session.state.value.lastError}")
                radio.close()
                scope.cancel()
                return@runBlocking 1
            }
            say("Joined $code on $serverUrl as «$name». The host picks the laptop's role in the lobby.")
            scope.launch { report(session, location, fixedAt) }
            session.state.first { it.snapshot?.phase == GamePhase.FINISHED || it.session == null }
            say("The game is over.")
            radio.close()
            scope.cancel()
            0
        }
    }

    /** Prints what changes: the phase, the role, whom the radar hears, the pulse; and keeps the laptop in the zone. */
    private suspend fun report(session: GameSessionManager, location: StandStill, fixedAt: GeoPoint?) {
        var last = emptyList<String>()
        var lastPulse = RadarBand.NONE
        while (true) {
            val snapshot = session.state.value.snapshot
            if (snapshot != null) {
                if (fixedAt == null) location.point = zoneCenter(snapshot)
                val lines = describe(snapshot)
                lines.filterNot { it in last }.forEach(::say)
                last = lines
            }
            val pulse = session.pulse.value
            if (pulse != lastPulse) {
                say("pulse: ${pulse.name.lowercase()}")
                lastPulse = pulse
            }
            delay(REPORT_EVERY_MILLIS)
        }
    }

    private fun describe(snapshot: GameSnapshot): List<String> {
        val me = snapshot.me
        val names = snapshot.players.associate { it.id to it.name }
        val lines = mutableListOf("phase: ${snapshot.phase.name.lowercase()}")
        if (snapshot.phase != GamePhase.LOBBY) lines += "role: ${me.role.name.lowercase()}"
        if (!snapshot.settings.features.hasRadar) lines += "this game has no radar: the host turns it on in the lobby"
        val contacts = me.radar?.contacts.orEmpty()
        if (snapshot.phase == GamePhase.SEEKING && me.radar != null) {
            lines += if (contacts.isEmpty()) {
                "radar: quiet"
            } else {
                "radar: " + contacts.joinToString { contact ->
                    val who = contact.playerId?.let(names::get) ?: if (me.role == Role.HIDER) "a seeker" else "?"
                    "$who ${contact.band.name.lowercase()}"
                }
            }
        }
        return lines
    }

    /** The middle of the zone right now: the laptop stays in the game wherever the zone goes. */
    private fun zoneCenter(snapshot: GameSnapshot): GeoPoint {
        val elapsed = snapshot.zoneStartedAtMillis?.let { snapshot.serverTimeMillis - it }?.coerceAtLeast(0) ?: 0L
        return snapshot.settings.zone.stateAt(elapsed).current.center
    }

    private fun say(text: String) {
        println("${LocalTime.now().format(TIME)}  $text")
    }

    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
    private const val REPORT_EVERY_MILLIS = 500L
    private const val LEAVE_TIMEOUT_MILLIS = 3_000L

    private val USAGE = """
        Usage: e2e/mac-beacon/run.sh --join <game code> [--name MacBook] [--at <lat,lon>] [--server https://...]
    """.trimIndent()
}

/** GPS of a laptop on the table: an accurate fix every few seconds at [point], nothing until it is known. */
private class StandStill(@Volatile var point: GeoPoint?) : LocationProvider {
    override fun hasPermission(): Boolean = true

    override fun locationUpdates(intervalMillis: Long): Flow<LocationSample> = flow {
        while (true) {
            point?.let { emit(LocationSample(it, ACCURACY_METERS, System.currentTimeMillis())) }
            delay(intervalMillis)
        }
    }

    private companion object {
        const val ACCURACY_METERS = 5.0
    }
}
