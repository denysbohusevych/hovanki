package app.hovanki.client.ui.spectator

import androidx.lifecycle.ViewModel
import app.hovanki.client.spectator.SpectatorManager
import app.hovanki.client.spectator.SpectatorState
import kotlinx.coroutines.flow.StateFlow

/** Watching an open game (docs/adr/0011-spectators-and-recordings.md): what [SpectatorManager] has, and leaving. */
class SpectatorViewModel(private val spectator: SpectatorManager) : ViewModel() {
    val state: StateFlow<SpectatorState> = spectator.state

    /** Stops watching, and back to the main screen. */
    fun leave() = spectator.stop()
}
