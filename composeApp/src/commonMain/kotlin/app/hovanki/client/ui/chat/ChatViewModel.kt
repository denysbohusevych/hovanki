package app.hovanki.client.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.account.AccountManager
import app.hovanki.client.account.AccountState
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.chat_reported
import app.hovanki.client.resources.error_invalid_message
import app.hovanki.client.session.ChatLine
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.session.SessionError
import app.hovanki.client.session.SessionState
import app.hovanki.client.session.chatLines
import app.hovanki.client.session.unreadChatCount
import app.hovanki.client.social.SocialManager
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.client.ui.common.Notice
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.rules.ChatRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The game's chat, for the chat button (unread count) and the chat panel on the lobby, game and results screens:
 * messages ([GameSessionManager] keeps them, blocked users' left out), «everyone» or «my team», report a message or
 * block its sender. Everything the player sees while the panel is open counts as read.
 */
class ChatViewModel(
    private val sessionManager: GameSessionManager,
    private val social: SocialManager,
    account: AccountManager,
) : ViewModel() {
    private val commands = CommandRunner(viewModelScope)

    /** The game whose chat panel is open; per game, since this view model outlives the screens of one game. */
    private val openIn = MutableStateFlow<GameId?>(null)

    /** The message whose actions (report, block) are shown. */
    private val selectedSeq = MutableStateFlow<Long?>(null)

    /** Compose state: text fields need synchronous updates. */
    var text by mutableStateOf("")
        private set

    /** Send to the player's team only (when the phase has teams). */
    var toTeam by mutableStateOf(false)
        private set
    var isSending by mutableStateOf(false)
        private set

    val uiState: StateFlow<ChatUiState> =
        combine(sessionManager.state, social.blockedIds, account.state, openIn, selectedSeq) {
                state,
                blocked,
                accountState,
                open,
                selected,
            ->
            buildUiState(state, blocked, accountState, open, selected)
        }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                buildUiState(
                    sessionManager.state.value,
                    social.blockedIds.value,
                    account.state.value,
                    openIn.value,
                    selectedSeq.value,
                ),
            )

    /** Reporting or blocking failed, or a report went through. */
    val message: StateFlow<FormMessage?> = commands.message
    val isBusy: StateFlow<Boolean> = commands.isBusy

    init {
        // Another game: its chat starts with an empty input, to everyone.
        viewModelScope.launch {
            sessionManager.state.map { it.session?.gameId }.distinctUntilChanged().collect {
                text = ""
                toTeam = false
            }
        }
        // The phase changes (hiding, seeking, results): the panel makes way for what is new; it opens again with a tap.
        viewModelScope.launch {
            sessionManager.state.map { it.snapshot?.phase }.distinctUntilChanged().collect { close() }
        }
        // While the panel is open, whatever arrives is read.
        viewModelScope.launch {
            combine(sessionManager.state, openIn) { state, open ->
                val newest = state.chat.lastOrNull()?.seq ?: 0L
                open != null && open == state.session?.gameId && newest > state.chatReadSeq
            }.distinctUntilChanged().collect { hasUnseen -> if (hasUnseen) sessionManager.markChatRead() }
        }
    }

    fun open() {
        openIn.value = sessionManager.state.value.session?.gameId ?: return
        selectedSeq.value = null
        commands.dismiss()
        sessionManager.clearError()
    }

    fun close() {
        openIn.value = null
        selectedSeq.value = null
    }

    fun onTextChange(value: String) {
        text = value.take(ChatRules.MAX_LENGTH)
    }

    fun selectChannel(team: Boolean) {
        toTeam = team
    }

    fun send() {
        val cleaned = ChatRules.clean(text)
        if (isSending || cleaned.isEmpty()) return
        if (!ChatRules.isValid(cleaned)) {
            commands.show(Notice.Text(Res.string.error_invalid_message))
            return
        }
        val team = toTeam && uiState.value.hasTeamChannel
        isSending = true
        viewModelScope.launch {
            try {
                if (sessionManager.sendChat(cleaned, team)) text = ""
            } finally {
                isSending = false
            }
        }
    }

    /** A long press on another player's message: its actions. */
    fun select(line: ChatLine) {
        if (line.isMine) return
        selectedSeq.value = if (selectedSeq.value == line.seq) null else line.seq
        commands.dismiss()
    }

    fun cancelSelection() {
        selectedSeq.value = null
    }

    /** The message goes to the moderators with the sender's name; reporting it again changes nothing. */
    fun report(seq: Long) {
        selectedSeq.value = null
        viewModelScope.launch {
            if (sessionManager.reportChat(seq)) commands.show(Notice.Text(Res.string.chat_reported), isError = false)
        }
    }

    /** Hides the sender's messages (and ends friendship, requests and invites both ways). */
    fun block(userId: UserId) {
        commands.execute({ social.block(userId) }) { selectedSeq.value = null }
    }

    fun dismissMessage() {
        commands.dismiss()
        sessionManager.clearError()
    }

    private fun buildUiState(
        state: SessionState,
        blocked: Set<UserId>,
        accountState: AccountState,
        open: GameId?,
        selected: Long?,
    ): ChatUiState {
        val snapshot = state.snapshot
        return ChatUiState(
            isOpen = open != null && open == state.session?.gameId,
            lines = state.chatLines(blocked),
            unread = state.unreadChatCount(blocked),
            hasTeamChannel = snapshot != null && ChatRules.hasTeamChannel(snapshot.phase),
            canBlock = accountState.isLoggedIn,
            selectedSeq = selected,
            error = state.lastError,
        )
    }
}

data class ChatUiState(
    /** The panel of this game's chat is open. */
    val isOpen: Boolean = false,
    /** Oldest first, without blocked users' messages. */
    val lines: List<ChatLine> = emptyList(),
    val unread: Int = 0,
    /** «My team» can be picked (not in the lobby). */
    val hasTeamChannel: Boolean = false,
    /** Logged in: senders with an account can be blocked. */
    val canBlock: Boolean = false,
    val selectedSeq: Long? = null,
    /** A message could not be sent or reported (rate limit, too long, not visible...). */
    val error: SessionError? = null,
)
