package app.hovanki.client.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.BuildInfo
import app.hovanki.client.automation.LaunchOptions
import app.hovanki.client.automation.LaunchOptionsHolder
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.session.SessionError
import app.hovanki.client.storage.ClientStorage
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val sessionManager: GameSessionManager,
    private val serverUrl: ServerUrl,
    private val launchOptions: LaunchOptionsHolder,
    private val storage: ClientStorage,
    buildInfo: BuildInfo,
) : ViewModel() {
    // Text field values are Compose state rather than StateFlow: text fields need synchronous updates,
    // otherwise fast typing can lose characters. The ViewModel keeps them when the player returns from a game;
    // the name and the server address that worked last time are also kept between launches (ClientStorage).
    var playerName by mutableStateOf(storage.playerName.orEmpty())
        private set
    var joinCode by mutableStateOf("")
        private set
    var serverAddress by mutableStateOf(serverUrl.value)
        private set

    /** Version, build number and commit, shown small at the bottom so testers can name the build. */
    val buildLabel: String = buildInfo.label

    private val mutableStatus = MutableStateFlow(HomeStatus())
    val status: StateFlow<HomeStatus> = mutableStatus.asStateFlow()

    val sessionError: StateFlow<SessionError?> = sessionManager.state
        .map { it.lastError }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), sessionManager.state.value.lastError)

    init {
        // Debug builds under UI automation: start parameters prefill the form (see LaunchOptions).
        viewModelScope.launch { launchOptions.options.filterNotNull().collect(::prefill) }
    }

    fun onPlayerNameChange(value: String) {
        playerName = value.take(MAX_NAME_LENGTH)
    }

    fun onJoinCodeChange(value: String) {
        // Join codes are Latin letters and digits; drop anything else (spaces, a pasted dash).
        joinCode = value.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(MAX_JOIN_CODE_LENGTH)
    }

    fun onServerAddressChange(value: String) {
        serverAddress = value
        serverUrl.value = value
    }

    /** Checks the form before the location permission is requested; shows what is missing. */
    fun canCreateGame(): Boolean = requireName() && requireServer()

    fun canJoinGame(): Boolean =
        requireName() && validate(joinCode.isNotBlank(), HomeProblem.CODE_MISSING) && requireServer()

    /** The zone is centered on the host, so a game can only be created with a GPS fix. */
    fun createGame(locationGranted: Boolean) {
        if (!validate(locationGranted, HomeProblem.LOCATION_DENIED)) return
        launchWork(HomeActivity.LOCATING) {
            val fix = sessionManager.currentLocation(LOCATION_TIMEOUT_MILLIS)
            if (fix == null) {
                mutableStatus.update { it.copy(problem = HomeProblem.NO_LOCATION_FIX) }
                return@launchWork
            }
            mutableStatus.update { it.copy(activity = HomeActivity.CONNECTING) }
            if (sessionManager.create(playerName, gameSettings(fix.point))) rememberFields()
        }
    }

    /** Joining works without location too, but the lobby keeps asking for it. */
    fun joinGame() {
        launchWork(HomeActivity.CONNECTING) {
            if (sessionManager.join(joinCode, playerName)) rememberFields()
        }
    }

    fun dismissProblems() {
        mutableStatus.update { it.copy(problem = null) }
        sessionManager.clearError()
    }

    private fun gameSettings(center: GeoPoint): GameSettings {
        val defaults = GameSessionManager.defaultSettings(center)
        val hidingSeconds = launchOptions.options.value?.hidingSeconds ?: return defaults
        return defaults.copy(hidingSeconds = hidingSeconds)
    }

    private fun prefill(options: LaunchOptions) {
        options.serverUrl?.let(::onServerAddressChange)
        options.playerName?.let(::onPlayerNameChange)
        options.joinCode?.let(::onJoinCodeChange)
    }

    private fun rememberFields() {
        storage.rememberPlayer(playerName.trim(), serverUrl.value)
    }

    private fun requireName(): Boolean = validate(playerName.isNotBlank(), HomeProblem.NAME_MISSING)

    /** Non-debug builds start without a server until one is deployed (`hovanki.serverUrl`). */
    private fun requireServer(): Boolean = validate(!serverUrl.isBlank, HomeProblem.SERVER_MISSING)

    private fun validate(condition: Boolean, problem: HomeProblem): Boolean {
        mutableStatus.update { it.copy(problem = if (condition) null else problem) }
        return condition
    }

    private fun launchWork(activity: HomeActivity, work: suspend () -> Unit) {
        // Ignore double taps while something is already running.
        if (mutableStatus.value.activity != null) return
        sessionManager.clearError()
        mutableStatus.update { it.copy(activity = activity, problem = null) }
        viewModelScope.launch {
            try {
                work()
            } finally {
                mutableStatus.update { it.copy(activity = null) }
            }
        }
    }

    private companion object {
        /** Same limit as the server. */
        const val MAX_NAME_LENGTH = 32
        const val MAX_JOIN_CODE_LENGTH = 6
        const val LOCATION_TIMEOUT_MILLIS = 20_000L
    }
}

data class HomeStatus(
    /** What the screen is busy with; null when idle. */
    val activity: HomeActivity? = null,
    val problem: HomeProblem? = null,
)

enum class HomeActivity { LOCATING, CONNECTING }

enum class HomeProblem { NAME_MISSING, CODE_MISSING, SERVER_MISSING, LOCATION_DENIED, NO_LOCATION_FIX }
