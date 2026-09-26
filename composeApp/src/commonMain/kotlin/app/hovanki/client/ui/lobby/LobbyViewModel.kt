package app.hovanki.client.ui.lobby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.session.ConnectionStatus
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.session.SessionError
import app.hovanki.client.session.SessionState
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.PlayerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LobbyViewModel(private val sessionManager: GameSessionManager) : ViewModel() {
    /** Seekers picked by the host; only sent to the server on start. */
    private val selectedSeekers = MutableStateFlow<Set<PlayerId>>(emptySet())
    private val isStarting = MutableStateFlow(false)

    val uiState: StateFlow<LobbyUiState?> =
        combine(sessionManager.state, selectedSeekers, isStarting) { state, seekers, starting ->
            buildUiState(state, seekers, starting)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            buildUiState(sessionManager.state.value, selectedSeekers.value, isStarting.value),
        )

    fun toggleSeeker(playerId: PlayerId) {
        selectedSeekers.update { if (playerId in it) it - playerId else it + playerId }
    }

    fun start() {
        val state = uiState.value ?: return
        if (!state.canStart || isStarting.value) return
        isStarting.value = true
        viewModelScope.launch {
            try {
                sessionManager.start(state.players.filter { it.isSeeker }.map { it.id })
            } finally {
                isStarting.value = false
            }
        }
    }

    fun leave() {
        selectedSeekers.value = emptySet()
        sessionManager.leave()
    }

    fun dismissError() = sessionManager.clearError()

    fun onLocationPermissionGranted() = sessionManager.onLocationPermissionGranted()

    private fun buildUiState(state: SessionState, seekers: Set<PlayerId>, starting: Boolean): LobbyUiState? {
        val snapshot = state.snapshot ?: return null
        val players = snapshot.players.map { player ->
            LobbyPlayer(
                id = player.id,
                name = player.name,
                isMe = player.id == snapshot.me.playerId,
                isHost = player.id == snapshot.hostId,
                // Selections of an earlier game don't match any id here.
                isSeeker = player.id in seekers,
            )
        }
        val seekerCount = players.count { it.isSeeker }
        return LobbyUiState(
            joinCode = snapshot.joinCode,
            players = players,
            isHost = snapshot.hostId == snapshot.me.playerId,
            // At least one seeker and at least one hider.
            canStart = seekerCount in 1 until players.size,
            isStarting = starting,
            connectionStatus = state.connectionStatus,
            isSharingLocation = state.isSharingLocation,
            error = state.lastError,
            isBuildingRuleOff = snapshot.buildings == BuildingsState.UNAVAILABLE,
        )
    }
}

data class LobbyUiState(
    val joinCode: String,
    val players: List<LobbyPlayer>,
    val isHost: Boolean,
    val canStart: Boolean,
    val isStarting: Boolean,
    val connectionStatus: ConnectionStatus,
    val isSharingLocation: Boolean,
    val error: SessionError?,
    /** The server could not load the zone's buildings: the game will run without that rule. */
    val isBuildingRuleOff: Boolean,
)

data class LobbyPlayer(
    val id: PlayerId,
    val name: String,
    val isMe: Boolean,
    val isHost: Boolean,
    val isSeeker: Boolean,
)
