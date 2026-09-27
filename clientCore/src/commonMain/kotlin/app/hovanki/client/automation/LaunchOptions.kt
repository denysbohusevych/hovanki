package app.hovanki.client.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Start parameters for UI automation (Maestro flows and e2e/run-devices.sh, see docs/e2e.md): they prefill the start
 * screen and log in instead of typing. Only debug builds read them: Android in the `debug` source set of :androidApp,
 * iOS only in a debug binary. Keys: `hovanki.<key>` as Android intent extras or iOS launch arguments
 * (`-hovanki.server http://localhost:8080`), `<key>` in the Android debug deep link
 * `hovanki://join?server=...&name=...&joinCode=...`.
 */
data class LaunchOptions(
    val serverUrl: String? = null,
    /** A guest's name; with [password] the login (nickname or email) of the account to log in with. */
    val playerName: String? = null,
    val joinCode: String? = null,
    /** Hiding phase of a game created on this device; automated runs don't wait the default 5 minutes. */
    val hidingSeconds: Int? = null,
    /**
     * iOS Simulator: every location it simulates (`xcrun simctl location`) is flagged `isSimulatedBySoftware`,
     * which the server treats as spoofing. Lets simulator players take part in automated games.
     */
    val allowSimulatedLocation: Boolean = false,
    /** Start from the start screen: drop a game saved by an earlier run instead of resuming it. */
    val forgetSavedGame: Boolean = false,
    /** With [playerName] as the login: the app logs in with this account at start (after [logOut], if both). */
    val password: String? = null,
    /** Start logged out: drop an account saved by an earlier run (a guest player). */
    val logOut: Boolean = false,
) {
    /** Never prints the password. */
    override fun toString(): String =
        "LaunchOptions(serverUrl=$serverUrl, playerName=$playerName, joinCode=$joinCode, " +
            "hidingSeconds=$hidingSeconds, allowSimulatedLocation=$allowSimulatedLocation, " +
            "forgetSavedGame=$forgetSavedGame, password=${password?.let { "***" }}, logOut=$logOut)"

    companion object {
        const val KEY_PREFIX = "hovanki."
        const val SERVER = "server"
        const val NAME = "name"
        const val JOIN_CODE = "joinCode"
        const val HIDING_SECONDS = "hidingSeconds"
        const val ALLOW_SIMULATED_LOCATION = "allowSimulatedLocation"
        const val FORGET_SAVED_GAME = "forgetSavedGame"
        const val PASSWORD = "password"
        const val LOG_OUT = "logOut"

        /** Options from [value] (key without the prefix → value); null when none is set. */
        fun read(value: (key: String) -> String?): LaunchOptions? {
            val options = LaunchOptions(
                serverUrl = value(SERVER)?.takeIf { it.isNotBlank() },
                playerName = value(NAME)?.takeIf { it.isNotBlank() },
                joinCode = value(JOIN_CODE)?.takeIf { it.isNotBlank() },
                hidingSeconds = value(HIDING_SECONDS)?.trim()?.toIntOrNull()?.takeIf { it >= 0 },
                allowSimulatedLocation = value(ALLOW_SIMULATED_LOCATION).isTrue(),
                forgetSavedGame = value(FORGET_SAVED_GAME).isTrue(),
                // Taken as is: spaces may be part of a password.
                password = value(PASSWORD)?.takeIf { it.isNotEmpty() },
                logOut = value(LOG_OUT).isTrue(),
            )
            return options.takeIf { it != LaunchOptions() }
        }

        private fun String?.isTrue(): Boolean = this?.trim()?.lowercase() in setOf("true", "1", "yes")
    }
}

/** The platform entry point hands [LaunchOptions] over here; the start screen picks them up. */
class LaunchOptionsHolder {
    private val mutableOptions = MutableStateFlow<LaunchOptions?>(null)

    val options: StateFlow<LaunchOptions?> = mutableOptions.asStateFlow()

    fun offer(options: LaunchOptions) {
        mutableOptions.value = options
    }
}
