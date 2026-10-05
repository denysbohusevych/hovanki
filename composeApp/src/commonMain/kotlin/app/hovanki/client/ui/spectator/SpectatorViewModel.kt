package app.hovanki.client.ui.spectator

import androidx.lifecycle.ViewModel
import app.hovanki.client.spectator.SpectatorManager
import app.hovanki.client.spectator.SpectatorState
import kotlinx.coroutines.flow.StateFlow

/**
 * Watching an open game (docs/adr/0011-spectators-and-recordings.md): what [SpectatorManager] has, and leaving. One
 * state, [uiState] (the manager's own, already one immutable state), and one way in, [onEvent] (docs/architecture.md,
 * «Состояние экрана»).
 */
class SpectatorViewModel(private val spectator: SpectatorManager) : ViewModel() {
    val uiState: StateFlow<SpectatorState> = spectator.state

    fun onEvent(event: SpectatorEvent) {
        when (event) {
            SpectatorEvent.Leave -> spectator.stop()
        }
    }
}

sealed interface SpectatorEvent {
    /** Stops watching, and back to the main screen. */
    data object Leave : SpectatorEvent
}
