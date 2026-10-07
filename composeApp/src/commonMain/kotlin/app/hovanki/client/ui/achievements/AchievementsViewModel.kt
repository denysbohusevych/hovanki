package app.hovanki.client.ui.achievements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.history.HistoryManager
import app.hovanki.client.network.ApiResult
import app.hovanki.shared.protocol.AchievementProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The profile's «Achievements» (docs/adr/0021-achievements.md): asked every time the profile opens. The levels new
 * since the player last looked keep their «New» mark while the screen lives; the server is told they were seen.
 *
 * One state for the whole screen, [uiState], and one way in, [onEvent] (docs/architecture.md, «Состояние экрана»).
 */
class AchievementsViewModel(private val history: HistoryManager) : ViewModel() {
    private val mutableUiState = MutableStateFlow(AchievementsUiState())
    val uiState: StateFlow<AchievementsUiState> = mutableUiState.asStateFlow()

    fun onEvent(event: AchievementsEvent) {
        when (event) {
            AchievementsEvent.Refresh -> load()
        }
    }

    private fun load() {
        viewModelScope.launch {
            val result = history.achievements()
            if (result !is ApiResult.Success) return@launch
            val shown = result.value.achievements.filter { AchievementTexts.isKnown(it.id) }
            mutableUiState.update { it.copy(achievements = shown, isLoaded = true) }
            newestUnlock(shown.filter { it.isNew })?.let { history.achievementsSeen(it) }
        }
    }
}

/** What «Achievements» shows. */
data class AchievementsUiState(
    /** In the server's order, only those this app can name. */
    val achievements: List<AchievementProgress> = emptyList(),
    val isLoaded: Boolean = false,
) {
    val reached: Int get() = achievements.count { it.level > 0 }
}

sealed interface AchievementsEvent {
    /** The profile is shown. */
    data object Refresh : AchievementsEvent
}

/** The newest `unlockedAtMillis` of [achievements]: up to when they were seen; null when none is reached. */
internal fun newestUnlock(achievements: List<AchievementProgress>): Long? =
    achievements.mapNotNull { it.unlockedAtMillis }.maxOrNull()
