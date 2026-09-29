package app.hovanki.client.ui.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.account.AccountManager
import app.hovanki.client.account.AccountState
import app.hovanki.client.history.HistoryManager
import app.hovanki.client.history.HistoryState
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.shared.protocol.GameHistoryEntry
import app.hovanki.shared.protocol.GameRecording
import app.hovanki.shared.protocol.GameRoute
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The player's own history (docs/adr/0007-game-history-and-routes.md): the statistics and the «save my routes» switch
 * of the profile, the history panel and a route or a game's recording over it. One per main screen, shared by the
 * profile and the panels.
 */
class HistoryViewModel(private val history: HistoryManager, account: AccountManager) : ViewModel() {
    private val commands = CommandRunner(viewModelScope)

    val state: StateFlow<HistoryState> = history.state
    val accountState: StateFlow<AccountState> = account.state
    val message: StateFlow<FormMessage?> = commands.message
    val isBusy: StateFlow<Boolean> = commands.isBusy

    /** The history panel is open. */
    var isOpen by mutableStateOf(false)
        private set

    /** The route shown over the history, with its game; null: none. */
    var route by mutableStateOf<OpenRoute?>(null)
        private set

    /** A game's recording shown over the history (docs/adr/0011-spectators-and-recordings.md); null: none. */
    var recording by mutableStateOf<OpenRecording?>(null)
        private set

    /** «Save my routes» is being turned off: the player confirms that the saved routes go. */
    var confirmingRoutesOff by mutableStateOf(false)
        private set

    /** Deleting the open route: the player confirms. */
    var confirmingRouteDelete by mutableStateOf(false)
        private set

    /** Loads the statistics and the newest games again, quietly: the profile shows what it has. */
    fun refresh() {
        viewModelScope.launch { history.refresh() }
    }

    fun open() {
        commands.dismiss()
        isOpen = true
        commands.execute({ history.refresh() })
    }

    fun close() {
        closeRoute()
        closeRecording()
        commands.dismiss()
        isOpen = false
    }

    fun loadMore() = commands.execute({ history.loadMore() })

    fun openRoute(game: GameHistoryEntry) =
        commands.execute({ history.route(game.gameId) }) { route = OpenRoute(game, it) }

    fun closeRoute() {
        route = null
        confirmingRouteDelete = false
    }

    fun openRecording(game: GameHistoryEntry) =
        commands.execute({ history.recording(game.gameId) }) { recording = OpenRecording(game, it) }

    fun closeRecording() {
        recording = null
    }

    fun askDeleteRoute() {
        confirmingRouteDelete = true
    }

    fun cancelDeleteRoute() {
        confirmingRouteDelete = false
    }

    fun deleteRoute() {
        val open = route ?: return
        commands.execute({ history.deleteRoute(open.game.gameId) }) { closeRoute() }
    }

    /** On right away (the explanation is next to the switch); off asks first: every saved route is deleted. */
    fun setSaveRoutes(enabled: Boolean) {
        commands.dismiss()
        if (enabled) {
            confirmingRoutesOff = false
            commands.execute({ history.setSaveRoutes(true) })
        } else {
            confirmingRoutesOff = true
        }
    }

    fun confirmRoutesOff() = commands.execute({ history.setSaveRoutes(false) }) { confirmingRoutesOff = false }

    fun cancelRoutesOff() {
        confirmingRoutesOff = false
    }

    fun dismissMessage() = commands.dismiss()
}

/** A saved route and the game it belongs to (its numbers). */
data class OpenRoute(val game: GameHistoryEntry, val route: GameRoute)

/** A game's recording, everybody's way, and the game in the player's history. */
data class OpenRecording(val game: GameHistoryEntry, val recording: GameRecording)
