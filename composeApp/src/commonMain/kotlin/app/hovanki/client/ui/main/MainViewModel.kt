package app.hovanki.client.ui.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.account.AccountManager
import app.hovanki.client.social.SocialManager
import app.hovanki.shared.protocol.Inbox
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The main screen (logged in, email confirmed): which tab is open, and the inbox behind the tabs' badges. */
class MainViewModel(account: AccountManager, social: SocialManager) : ViewModel() {
    var tab by mutableStateOf(MainTab.PLAY)
        private set

    /**
     * Game invites and incoming friend requests. Collecting it polls the server (every 10 s), so the main screen
     * collects it only while it is shown.
     */
    val inbox: StateFlow<Inbox> = social.inbox

    init {
        // Somebody else logs in on this phone: they start on «Play».
        viewModelScope.launch {
            account.state.map { it.user?.id }.distinctUntilChanged().drop(1).collect { tab = MainTab.PLAY }
        }
    }

    fun select(tab: MainTab) {
        this.tab = tab
    }
}

enum class MainTab { PLAY, FRIENDS, GROUPS, PROFILE }
