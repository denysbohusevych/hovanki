package app.hovanki.client.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Start parameters for UI automation (Maestro flows and e2e/run-devices.sh, see docs/e2e.md): they prefill the start
 * screen instead of typing. Only debug builds read them: Android in the `debug` source set of :androidApp, iOS only
 * in a debug binary. Keys: `hovanki.<key>` as Android intent extras or iOS launch arguments
 * (`-hovanki.server http://localhost:8080`), `<key>` in the Android debug deep link
 * `hovanki://join?server=...&name=...&joinCode=...`.
 */
data class LaunchOptions(
    val serverUrl: String? = null,
    val playerName: String? = null,
    val joinCode: String? = null,
    /** Hiding phase of a game created on this device; automated runs don't wait the default 5 minutes. */
    val hidingSeconds: Int? = null,
    /**
     * iOS Simulator: every location it simulates (`xcrun simctl location`) is flagged `isSimulatedBySoftware`,
     * which the server treats as spoofing. Lets simulator players take part in automated games.
     */
    val allowSimulatedLocation: Boolean = false,
) {
    companion object {
        const val KEY_PREFIX = "hovanki."
        const val SERVER = "server"
        const val NAME = "name"
        const val JOIN_CODE = "joinCode"
        const val HIDING_SECONDS = "hidingSeconds"
        const val ALLOW_SIMULATED_LOCATION = "allowSimulatedLocation"

        /** Options from [value] (key without the prefix → value); null when none is set. */
        fun read(value: (key: String) -> String?): LaunchOptions? {
            val options = LaunchOptions(
                serverUrl = value(SERVER)?.takeIf { it.isNotBlank() },
                playerName = value(NAME)?.takeIf { it.isNotBlank() },
                joinCode = value(JOIN_CODE)?.takeIf { it.isNotBlank() },
                hidingSeconds = value(HIDING_SECONDS)?.trim()?.toIntOrNull()?.takeIf { it >= 0 },
                allowSimulatedLocation =
                value(ALLOW_SIMULATED_LOCATION)?.trim()?.lowercase() in setOf("true", "1", "yes"),
            )
            return options.takeIf { it != LaunchOptions() }
        }
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
