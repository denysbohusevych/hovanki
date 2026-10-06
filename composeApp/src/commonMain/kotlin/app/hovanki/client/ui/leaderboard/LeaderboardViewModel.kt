package app.hovanki.client.ui.leaderboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.history.HistoryManager
import app.hovanki.client.network.ApiResult
import app.hovanki.client.session.ServerClock
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.client.ui.common.notice
import app.hovanki.shared.protocol.LeaderboardResponse
import app.hovanki.shared.protocol.LeaderboardScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * «Rating» (docs/adr/0020-leaderboard.md): the week's points of the last game's players, of the player and their
 * friends, of everybody. Each scope is asked once the tab shows it and kept while the tab lives; «Refresh» asks again.
 *
 * One state for the whole screen, [uiState], and one way in, [onEvent] (docs/architecture.md, «Состояние экрана»).
 */
class LeaderboardViewModel(private val history: HistoryManager, private val clock: ServerClock) : ViewModel() {
    private val mutableUiState = MutableStateFlow(LeaderboardUiState())
    val uiState: StateFlow<LeaderboardUiState> = mutableUiState.asStateFlow()

    fun onEvent(event: LeaderboardEvent) {
        when (event) {
            LeaderboardEvent.Refresh -> load(uiState.value.scope)

            is LeaderboardEvent.SelectScope -> {
                mutableUiState.update { it.copy(scope = event.scope, message = null) }
                if (event.scope !in uiState.value.boards) load(event.scope)
            }

            LeaderboardEvent.ToggleRules -> mutableUiState.update { it.copy(showRules = !it.showRules) }

            LeaderboardEvent.DismissMessage -> mutableUiState.update { it.copy(message = null) }
        }
    }

    private fun load(scope: LeaderboardScope) {
        mutableUiState.update { it.copy(loading = it.loading + scope) }
        viewModelScope.launch {
            val result = history.leaderboard(scope)
            mutableUiState.update { state ->
                val loaded = state.copy(loading = state.loading - scope, nowMillis = clock.now())
                if (result is ApiResult.Success) {
                    loaded.copy(boards = loaded.boards + (scope to result.value))
                } else {
                    loaded.copy(message = result.notice()?.let { FormMessage(it) })
                }
            }
        }
    }
}

/** What «Rating» shows. */
data class LeaderboardUiState(
    val scope: LeaderboardScope = LeaderboardScope.LAST_GAME,
    /** What each scope showed last; a scope not here is not loaded yet. */
    val boards: Map<LeaderboardScope, LeaderboardResponse> = emptyMap(),
    val loading: Set<LeaderboardScope> = emptySet(),
    /** The server's time of the last answer, for «the week ends in…». */
    val nowMillis: Long = 0,
    /** How the points are counted, under the title. */
    val showRules: Boolean = false,
    val message: FormMessage? = null,
) {
    val board: LeaderboardResponse? get() = boards[scope]
    val isLoading: Boolean get() = scope in loading
}

sealed interface LeaderboardEvent {
    /** The tab is shown. */
    data object Refresh : LeaderboardEvent

    data class SelectScope(val scope: LeaderboardScope) : LeaderboardEvent

    data object ToggleRules : LeaderboardEvent

    data object DismissMessage : LeaderboardEvent
}
