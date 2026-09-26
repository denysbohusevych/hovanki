package app.hovanki.e2e.devices

import app.hovanki.client.session.GameSessionManager
import app.hovanki.e2e.bot.BotBehavior
import app.hovanki.e2e.bot.BotPlayer
import app.hovanki.e2e.bot.CommandResult
import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.Timeline
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A throwaway game of two bots before the devices play: on a busy CI machine the server's first requests (class
 * loading, JIT) took longer than the app's 15 s request timeout, and the app on the device showed "Cannot reach the
 * server" for a server that was only warming up. The scenarios find their game by the host device's name, so this
 * one does not get in the way; the server's janitor removes it later.
 */
suspend fun warmUpServer(serverUrl: String) {
    val timeline = Timeline()
    fun bot(name: String) =
        BotPlayer(name, GameSetups.PARK, GpsNoise.NONE, BotBehavior(), serverUrl, timeline, logChanges = false)
    val host = bot("Warm-up host")
    val guest = bot("Warm-up guest")
    val deviceLike = bot("Warm-up app")
    try {
        withTimeout(3.minutes) {
            // The settings a phone creates a game with (see HomeViewModel), besides the bots' short timers.
            deviceLike.createGame(GameSessionManager.defaultSettings(GameSetups.PARK))
            check(host.createGame(GameSetups.fast()) == CommandResult.Ok) {
                "Warm-up: the server did not create a game"
            }
            val joinCode = checkNotNull(host.snapshot?.joinCode) { "Warm-up: no join code" }
            check(guest.join(joinCode) == CommandResult.Ok) { "Warm-up: could not join" }
            // A few rounds of sync for both.
            delay(5.seconds)
        }
    } finally {
        host.close()
        guest.close()
        deviceLike.close()
    }
}
