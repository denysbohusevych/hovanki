package app.hovanki.client.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.account.AccountManager
import app.hovanki.client.social.SocialManager
import app.hovanki.shared.protocol.Inbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MainTab { PLAY, FRIENDS, RATING, GROUPS, PROFILE }

/** The main screen's state (docs/architecture.md, «Состояние экрана»). */
data class MainUiState(
    val tab: MainTab = MainTab.PLAY,
    /** Game invites and incoming friend requests, behind the tabs' badges. */
    val inbox: Inbox = Inbox(),
)

/** What the player does on the main screen: everything goes through [MainViewModel.onEvent]. */
sealed interface MainEvent {
    data class SelectTab(val tab: MainTab) : MainEvent
}

/** The main screen (logged in, email confirmed): which tab is open, and the inbox behind the tabs' badges. */
class MainViewModel(account: AccountManager, social: SocialManager) : ViewModel() {
    private val tab = MutableStateFlow(MainTab.PLAY)

    /**
     * Collecting the inbox polls the server (every 10 s), so the state follows it only while the main screen collects
     * the state, and stops at once when it no longer does.
     */
    val uiState: StateFlow<MainUiState> = combine(tab, social.inbox, ::MainUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), MainUiState(tab.value, social.inbox.value))

    init {
        // Somebody else logs in on this phone: they start on «Play».
        viewModelScope.launch {
            account.state.map { it.user?.id }.distinctUntilChanged().drop(1).collect { tab.value = MainTab.PLAY }
        }
    }

    fun onEvent(event: MainEvent) {
        when (event) {
            is MainEvent.SelectTab -> tab.value = event.tab
        }
    }
}
