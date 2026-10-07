package app.hovanki.client.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.account.AccountManager
import app.hovanki.client.history.HistoryManager
import app.hovanki.client.network.ApiResult
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.social.SocialManager
import app.hovanki.client.ui.achievements.AchievementTexts
import app.hovanki.client.ui.achievements.newestUnlock
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.shared.protocol.AchievementProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The results screen: who played with an account, friend requests to them, keeping the player's route, the tracks
 * for the replay, the achievements the game reached and «Play again». The standings and awards come from the final
 * snapshot.
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
    private val newAchievements = MutableStateFlow<List<AchievementProgress>>(emptyList())
    private var achievementsAsked = false
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
        }.combine(newAchievements) { state, achievements ->
            state.copy(newAchievements = achievements)
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

            ResultsEvent.Shown -> askAchievements()

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

    /**
     * The game's achievements, once per screen: the server saves the game's history a moment after it ends, so they
     * are asked again after [ACHIEVEMENT_RETRIES_MILLIS] until a new one shows. The shown ones are no longer new.
     */
    private fun askAchievements() {
        if (achievementsAsked || uiState.value.isGuest) return
        achievementsAsked = true
        viewModelScope.launch {
            for (wait in ACHIEVEMENT_RETRIES_MILLIS) {
                delay(wait)
                val result = history.achievements()
                if (result !is ApiResult.Success) continue
                val fresh = result.value.achievements.filter { it.isNew && AchievementTexts.isKnown(it.id) }
                if (fresh.isEmpty()) continue
                newAchievements.value = fresh
                newestUnlock(fresh)?.let { history.achievementsSeen(it) }
                return@launch
            }
        }
    }

    private companion object {
        val ACHIEVEMENT_RETRIES_MILLIS = listOf(1_000L, 3_000L, 6_000L)
    }
}
