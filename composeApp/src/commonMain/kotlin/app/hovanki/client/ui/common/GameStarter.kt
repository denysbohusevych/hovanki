package app.hovanki.client.ui.common

import app.hovanki.client.automation.LaunchOptionsHolder
import app.hovanki.client.session.GameSessionManager
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Creating and joining games from the screens outside a game (welcome: guests join; «Play»: create, join, accept an
 * invite; a group's panel: play with the group). Owns what the phone finds out before the server is asked (the GPS
 * fix a new game is centered on, a missing name or code) and the progress; the server's refusals end up in the
 * session's `lastError`. Used by view models with their `viewModelScope`.
 */
class GameStarter(
    private val sessionManager: GameSessionManager,
    private val launchOptions: LaunchOptionsHolder,
    private val scope: CoroutineScope,
) {
    private val mutableStatus = MutableStateFlow(StartStatus())
    val status: StateFlow<StartStatus> = mutableStatus.asStateFlow()

    /**
     * The zone is centered on the host, so a game can only be created with a GPS fix. [onCreated] runs once the player
     * is in the new game's lobby, e.g. to invite a group. [playerName] is only used for guests.
     */
    fun create(playerName: String, locationGranted: Boolean, onCreated: suspend () -> Unit = {}) {
        if (!validate(locationGranted, StartProblem.LOCATION_DENIED)) return
        launchWork(StartActivity.LOCATING) {
            val fix = sessionManager.currentLocation(LOCATION_TIMEOUT_MILLIS)
            if (fix == null) {
                mutableStatus.update { it.copy(problem = StartProblem.NO_LOCATION_FIX) }
                return@launchWork
            }
            mutableStatus.update { it.copy(activity = StartActivity.CONNECTING) }
            if (sessionManager.create(playerName, gameSettings(fix.point))) onCreated()
        }
    }

    /** Joining works without location too, but the lobby keeps asking for it. [onJoined] runs in the lobby. */
    fun join(code: String, playerName: String, onJoined: () -> Unit = {}) {
        launchWork(StartActivity.CONNECTING) {
            if (sessionManager.join(code, playerName)) onJoined()
        }
    }

    /** Shows [problem] unless [condition] holds (a form check before asking for the location permission). */
    fun validate(condition: Boolean, problem: StartProblem): Boolean {
        mutableStatus.update { it.copy(problem = if (condition) null else problem) }
        return condition
    }

    fun dismissProblems() {
        mutableStatus.update { it.copy(problem = null) }
        sessionManager.clearError()
    }

    private fun gameSettings(center: GeoPoint): GameSettings {
        val defaults = GameSessionManager.defaultSettings(center)
        // Debug builds under UI automation don't wait the default hiding time (see LaunchOptions).
        val hidingSeconds = launchOptions.options.value?.hidingSeconds ?: return defaults
        return defaults.copy(hidingSeconds = hidingSeconds)
    }

    private fun launchWork(activity: StartActivity, work: suspend () -> Unit) {
        // Ignore double taps while something is already running.
        if (mutableStatus.value.activity != null) return
        sessionManager.clearError()
        mutableStatus.update { it.copy(activity = activity, problem = null) }
        scope.launch {
            try {
                work()
            } finally {
                mutableStatus.update { it.copy(activity = null) }
            }
        }
    }

    companion object {
        const val JOIN_CODE_LENGTH = 6

        /** Same limit as the server. */
        const val MAX_NAME_LENGTH = 32
        private const val LOCATION_TIMEOUT_MILLIS = 20_000L

        /** Join codes are Latin letters and digits; anything else (spaces, a pasted dash) is dropped. */
        fun joinCodeInput(value: String): String =
            value.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(JOIN_CODE_LENGTH)
    }
}

data class StartStatus(
    /** What is under way; null when idle. */
    val activity: StartActivity? = null,
    val problem: StartProblem? = null,
) {
    val isBusy: Boolean get() = activity != null
}

enum class StartActivity { LOCATING, CONNECTING }

/** Why a game could not be created or joined, found on the phone. */
enum class StartProblem { NAME_MISSING, CODE_MISSING, LOCATION_DENIED, NO_LOCATION_FIX }
