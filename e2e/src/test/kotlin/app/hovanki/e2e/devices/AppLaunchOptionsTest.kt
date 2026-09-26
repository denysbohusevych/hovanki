package app.hovanki.e2e.devices

import app.hovanki.client.automation.LaunchOptions
import app.hovanki.e2e.bot.BotAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the device runs pass to the app, read back the way the debug app reads it ([LaunchOptions.read]). */
class AppLaunchOptionsTest {
    private val server = "http://10.0.2.2:8080"
    private val account = BotAccount("Android1_ab12c1", "android1_ab12c1@hovanki.test", "hide-and-seek-42")

    @Test
    fun theHostLogsInWithItsAccount() {
        val options = appLaunchOptions(server, guestName = "Android-1", account = account, hidingSeconds = 60)

        assertEquals(
            LaunchOptions(
                serverUrl = server,
                playerName = account.nickname,
                hidingSeconds = 60,
                forgetSavedGame = true,
                password = account.password,
            ),
            read(options),
        )
    }

    @Test
    fun aGuestStartsLoggedOutWithItsNameAndTheCode() {
        val options =
            appLaunchOptions(server, guestName = "iOS-1", account = null, joinCode = "ABC234", simulatedLocation = true)

        assertEquals(
            LaunchOptions(
                serverUrl = server,
                playerName = "iOS-1",
                joinCode = "ABC234",
                allowSimulatedLocation = true,
                forgetSavedGame = true,
                logOut = true,
            ),
            read(options),
        )
    }

    @Test
    fun aRelaunchKeepsTheSavedGameAndTheAccount() {
        val options = read(appLaunchOptions(server, "Android-1", account, forgetSavedGame = false))

        assertFalse(options.forgetSavedGame, "the app resumes the game")
        assertFalse(options.logOut, "the saved account stays")
        assertEquals(account.password, options.password)
    }

    @Test
    fun onlyKnownKeys() {
        val known = setOf(
            LaunchOptions.SERVER,
            LaunchOptions.NAME,
            LaunchOptions.JOIN_CODE,
            LaunchOptions.HIDING_SECONDS,
            LaunchOptions.ALLOW_SIMULATED_LOCATION,
            LaunchOptions.FORGET_SAVED_GAME,
            LaunchOptions.PASSWORD,
            LaunchOptions.LOG_OUT,
        )
        val all = appLaunchOptions(server, "Android-2", null, "ABC234", 60, simulatedLocation = true).keys +
            appLaunchOptions(server, "Android-1", account).keys

        assertTrue(known.containsAll(all), "unknown keys: ${all - known}")
    }

    @Test
    fun thePasswordNeverReachesTheCommandLog() {
        val android =
            listOf("adb", "-s", "emulator-5554", "shell", "am", "start", "--es", "hovanki.password", "secret-1")
        val ios = listOf("xcrun", "simctl", "launch", "UDID", "app.hovanki.ios", "-hovanki.password", "secret-1")

        assertEquals("adb -s emulator-5554 shell am start --es hovanki.password ***", Shell.commandLine(android))
        assertEquals("xcrun simctl launch UDID app.hovanki.ios -hovanki.password ***", Shell.commandLine(ios))
    }

    private fun read(options: Map<String, String>): LaunchOptions = checkNotNull(LaunchOptions.read { options[it] })
}
