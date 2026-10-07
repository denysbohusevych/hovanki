package app.hovanki.client.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.account.AccountManager
import app.hovanki.client.history.HistoryManager
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.social.SocialManager
import app.hovanki.client.ui.common.CommandRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The results screen: who played with an account, friend requests to them, keeping the player's route, the tracks
 * for the replay, and «Play again». The standings and awards come from the final snapshot.
 *
 * One state for the whole screen, [uiState], and one way in, [onEvent] (docs/architecture.md, «Состояние экрана»).
 */
class ResultsViewModel(
    private val sessionManager: GameSessionManager,
    private val social: SocialManager,
    private val history: HistoryManager,
    account: AccountManager,
) : ViewModel() {
    private val commands = CommandRunner(viewModelScope)
    private val builder = ResultsStateBuilder()
    private val progress = MutableStateFlow(PlayAgainProgress())

    val uiState: StateFlow<ResultsUiState> =
        combine(
            sessionManager.state,
            social.friends,
            account.state,
            commands.message,
            combine(commands.isBusy, progress, ::Pair),
        ) { session, friends, accountState, message, (busy, moving) ->
            builder.build(session, friends, accountState, message, busy, moving)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            builder.build(
                sessionManager.state.value,
                social.friends.value,
                account.state.value,
                commands.message.value,
                commands.isBusy.value,
                progress.value,
            ),
        )

    fun onEvent(event: ResultsEvent) {
        when (event) {
            is ResultsEvent.AddFriend -> commands.execute({ social.sendFriendRequest(event.userId) })

            ResultsEvent.TurnOnSaveRoutes -> commands.execute({ history.setSaveRoutes(true) })

            ResultsEvent.DismissMessage -> commands.dismiss()

            is ResultsEvent.PlayAgain -> playAgain(event.leaveOtherGame)

            ResultsEvent.DismissPlayAgainError -> {
                progress.value = PlayAgainProgress()
                sessionManager.clearError()
            }

            ResultsEvent.Leave -> {
                commands.dismiss()
                sessionManager.leave()
            }
        }
    }

    /** Into the next lobby; the app shows it as soon as the session moves there. */
    private fun playAgain(leaveOtherGame: Boolean) {
        if (progress.value.isRunning) return
        sessionManager.clearError()
        progress.value = PlayAgainProgress(isRunning = true)
        viewModelScope.launch {
            val moved = sessionManager.playAgain(leaveOtherGame)
            progress.value = PlayAgainProgress(failed = !moved)
        }
    }
}
