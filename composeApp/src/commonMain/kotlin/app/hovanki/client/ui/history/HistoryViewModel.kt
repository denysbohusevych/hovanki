package app.hovanki.client.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.history.HistoryManager
import app.hovanki.client.history.HistoryState
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.shared.protocol.GameHistoryEntry
import app.hovanki.shared.protocol.GameRecording
import app.hovanki.shared.protocol.GameRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The player's own history (docs/adr/0007-game-history-and-routes.md): the statistics and the «save my routes» switch
 * of the profile, the history panel and a route or a game's recording over it. One per main screen, shared by the
 * profile and the panels.
 *
 * One state, [uiState], and one way in, [onEvent] (docs/architecture.md, «Состояние экрана»). What this phone has open
 * ([HistoryLocal]) changes the state at once, on the caller's thread.
 */
class HistoryViewModel(private val history: HistoryManager) : ViewModel() {
    private val commands = CommandRunner(viewModelScope)

    private var local = HistoryLocal()
    private var inputs = HistoryInputs(history.state.value, commands.message.value, commands.isBusy.value)
    private val mutableUiState = MutableStateFlow(inputs.build(local))
    val uiState: StateFlow<HistoryUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(history.state, commands.message, commands.isBusy, ::HistoryInputs).collect {
                inputs = it
                publish()
            }
        }
    }

    fun onEvent(event: HistoryEvent) {
        when (event) {
            // Quietly: the profile shows what it has.
            HistoryEvent.Refresh -> viewModelScope.launch { history.refresh() }

            HistoryEvent.Open -> {
                commands.dismiss()
                update { it.copy(isOpen = true) }
                commands.execute({ history.refresh() })
            }

            HistoryEvent.Close -> {
                update { HistoryLocal(confirmingRoutesOff = it.confirmingRoutesOff) }
                commands.dismiss()
            }

            HistoryEvent.LoadMore -> commands.execute({ history.loadMore() })

            is HistoryEvent.OpenRoute -> commands.execute({ history.route(event.game.gameId) }) { route ->
                update { it.copy(route = OpenRoute(event.game, route)) }
            }

            HistoryEvent.CloseRoute -> closeRoute()

            is HistoryEvent.OpenRecording -> commands.execute({ history.recording(event.game.gameId) }) { recording ->
                update { it.copy(recording = OpenRecording(event.game, recording)) }
            }

            HistoryEvent.CloseRecording -> update { it.copy(recording = null) }

            HistoryEvent.AskDeleteRoute -> update { it.copy(confirmingRouteDelete = true) }

            HistoryEvent.CancelDeleteRoute -> update { it.copy(confirmingRouteDelete = false) }

            HistoryEvent.DeleteRoute -> {
                val open = local.route ?: return
                commands.execute({ history.deleteRoute(open.game.gameId) }) { closeRoute() }
            }

            is HistoryEvent.SetSaveRoutes -> setSaveRoutes(event.enabled)

            HistoryEvent.ConfirmRoutesOff -> commands.execute({ history.setSaveRoutes(false) }) {
                update { it.copy(confirmingRoutesOff = false) }
            }

            HistoryEvent.CancelRoutesOff -> update { it.copy(confirmingRoutesOff = false) }

            HistoryEvent.DismissMessage -> commands.dismiss()
        }
    }

    private fun publish() {
        mutableUiState.value = inputs.build(local)
    }

    private fun update(change: (HistoryLocal) -> HistoryLocal) {
        local = change(local)
        publish()
    }

    private fun closeRoute() = update { it.copy(route = null, confirmingRouteDelete = false) }

    /** On right away (the explanation is next to the switch); off asks first: every saved route is deleted. */
    private fun setSaveRoutes(enabled: Boolean) {
        commands.dismiss()
        if (enabled) {
            update { it.copy(confirmingRoutesOff = false) }
            commands.execute({ history.setSaveRoutes(true) })
        } else {
            update { it.copy(confirmingRoutesOff = true) }
        }
    }
}

/** Everything the profile's history cards and the history panels show (docs/architecture.md, «Состояние экрана»). */
data class HistoryUiState(
    /** The statistics and the games, as the server sent them. */
    val history: HistoryState = HistoryState(),
    /** The history panel is open. */
    val isOpen: Boolean = false,
    /** The route shown over the history, with its game; null: none. */
    val route: OpenRoute? = null,
    /** A game's recording shown over the history (docs/adr/0011-spectators-and-recordings.md); null: none. */
    val recording: OpenRecording? = null,
    /** «Save my routes» is being turned off: the player confirms that the saved routes go. */
    val confirmingRoutesOff: Boolean = false,
    /** Deleting the open route: the player confirms. */
    val confirmingRouteDelete: Boolean = false,
    val message: FormMessage? = null,
    val isBusy: Boolean = false,
)

sealed interface HistoryEvent {
    /** Loads the statistics and the newest games again, quietly. */
    data object Refresh : HistoryEvent

    data object Open : HistoryEvent

    data object Close : HistoryEvent

    data object LoadMore : HistoryEvent

    data class OpenRoute(val game: GameHistoryEntry) : HistoryEvent

    data object CloseRoute : HistoryEvent

    data class OpenRecording(val game: GameHistoryEntry) : HistoryEvent

    data object CloseRecording : HistoryEvent

    data object AskDeleteRoute : HistoryEvent

    data object CancelDeleteRoute : HistoryEvent

    data object DeleteRoute : HistoryEvent

    data class SetSaveRoutes(val enabled: Boolean) : HistoryEvent

    data object ConfirmRoutesOff : HistoryEvent

    data object CancelRoutesOff : HistoryEvent

    data object DismissMessage : HistoryEvent
}

/** A saved route and the game it belongs to (its numbers). */
data class OpenRoute(val game: GameHistoryEntry, val route: GameRoute)

/** A game's recording, everybody's way, and the game in the player's history. */
data class OpenRecording(val game: GameHistoryEntry, val recording: GameRecording)

/** What this phone has open of the history. */
private data class HistoryLocal(
    val isOpen: Boolean = false,
    val route: OpenRoute? = null,
    val recording: OpenRecording? = null,
    val confirmingRoutesOff: Boolean = false,
    val confirmingRouteDelete: Boolean = false,
)

private data class HistoryInputs(val history: HistoryState, val message: FormMessage?, val isBusy: Boolean) {
    fun build(local: HistoryLocal) = HistoryUiState(
        history = history,
        isOpen = local.isOpen,
        route = local.route,
        recording = local.recording,
        confirmingRoutesOff = local.confirmingRoutesOff,
        confirmingRouteDelete = local.confirmingRouteDelete,
        message = message,
        isBusy = isBusy,
    )
}
