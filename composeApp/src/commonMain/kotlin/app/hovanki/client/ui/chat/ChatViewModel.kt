package app.hovanki.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.account.AccountManager
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.chat_reported
import app.hovanki.client.resources.error_invalid_message
import app.hovanki.client.session.ChatLine
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.social.SocialManager
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.client.ui.common.Notice
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.rules.ChatRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The game's chat, for the chat button (unread count) and the chat panel on the lobby, game and results screens:
 * messages ([GameSessionManager] keeps them, blocked users' left out), «everyone» or «my team», report a message or
 * block its sender. Everything the player sees while the panel is open counts as read.
 *
 * One state, [uiState], and one way in, [onEvent] (docs/architecture.md, «Состояние экрана»). This phone's own part
 * ([ChatLocal]) changes the state at once, on the caller's thread: a text field's edit is there before the next frame.
 */
class ChatViewModel(
    private val sessionManager: GameSessionManager,
    private val social: SocialManager,
    private val account: AccountManager,
) : ViewModel() {
    private val commands = CommandRunner(viewModelScope)

    private var local = ChatLocal()
    private var inputs = currentInputs()

    /** Whether the open panel had unread messages last time: the chat is marked read when it starts to. */
    private var hadUnseen = false
    private val mutableUiState = MutableStateFlow(buildChatUiState(inputs, local))
    val uiState: StateFlow<ChatUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            var gameId = inputs.session.session?.gameId
            var phase = inputs.session.snapshot?.phase
            val commandState = combine(commands.message, commands.isBusy) { message, busy -> message to busy }
            combine(
                sessionManager.state,
                social.blockedIds,
                account.state,
                commandState,
            ) { session, blocked, accountState, (message, busy) ->
                ChatInputs(session, blocked, accountState, message, busy)
            }.collect { next ->
                var changed = local
                // Another game: its chat starts with an empty input, to everyone.
                val nextGameId = next.session.session?.gameId
                if (nextGameId != gameId) changed = changed.copy(text = "", toTeam = false)
                gameId = nextGameId
                // The phase changes (hiding, seeking, results): the panel makes way for what is new; it opens again
                // with a tap.
                val nextPhase = next.session.snapshot?.phase
                if (nextPhase != phase) changed = changed.copy(openIn = null, selectedSeq = null)
                phase = nextPhase
                inputs = next
                local = changed
                publish()
            }
        }
    }

    fun onEvent(event: ChatEvent) {
        when (event) {
            ChatEvent.Open -> open()

            ChatEvent.Close -> update { it.copy(openIn = null, selectedSeq = null) }

            is ChatEvent.EditText -> update { it.copy(text = event.text.take(ChatRules.MAX_LENGTH)) }

            is ChatEvent.SelectChannel -> update { it.copy(toTeam = event.toTeam) }

            ChatEvent.Send -> send()

            is ChatEvent.Select -> select(event.line)

            ChatEvent.CancelSelection -> update { it.copy(selectedSeq = null) }

            is ChatEvent.Report -> report(event.seq)

            is ChatEvent.Block -> block(event.userId)

            ChatEvent.DismissMessage -> {
                commands.dismiss()
                sessionManager.clearError()
            }
        }
    }

    private fun currentInputs() = ChatInputs(
        session = sessionManager.state.value,
        blocked = social.blockedIds.value,
        account = account.state.value,
        message = commands.message.value,
        isBusy = commands.isBusy.value,
    )

    private fun publish() {
        mutableUiState.value = buildChatUiState(inputs, local)
        // While the panel is open, whatever arrives is read.
        val unseen = hasUnseen(inputs.session, local)
        if (unseen && !hadUnseen) sessionManager.markChatRead()
        hadUnseen = unseen
    }

    /** Changes this phone's own part of the chat; the state follows at once. */
    private fun update(change: (ChatLocal) -> ChatLocal) {
        local = change(local)
        publish()
    }

    private fun open() {
        val gameId = sessionManager.state.value.session?.gameId ?: return
        update { it.copy(openIn = gameId, selectedSeq = null) }
        commands.dismiss()
        sessionManager.clearError()
    }

    private fun send() {
        val current = local
        val cleaned = ChatRules.clean(current.text)
        if (current.isSending || cleaned.isEmpty()) return
        if (!ChatRules.isValid(cleaned)) {
            commands.show(Notice.Text(Res.string.error_invalid_message))
            return
        }
        val team = current.toTeam && uiState.value.hasTeamChannel
        update { it.copy(isSending = true) }
        viewModelScope.launch {
            var sent = false
            try {
                sent = sessionManager.sendChat(cleaned, team)
            } finally {
                update { if (sent) it.copy(isSending = false, text = "") else it.copy(isSending = false) }
            }
        }
    }

    private fun select(line: ChatLine) {
        if (line.isMine) return
        update { it.copy(selectedSeq = if (it.selectedSeq == line.seq) null else line.seq) }
        commands.dismiss()
    }

    /** The message goes to the moderators with the sender's name; reporting it again changes nothing. */
    private fun report(seq: Long) {
        update { it.copy(selectedSeq = null) }
        viewModelScope.launch {
            if (sessionManager.reportChat(seq)) commands.show(Notice.Text(Res.string.chat_reported), isError = false)
        }
    }

    /** Hides the sender's messages (and ends friendship, requests and invites both ways). */
    private fun block(userId: UserId) {
        commands.execute({ social.block(userId) }) { update { it.copy(selectedSeq = null) } }
    }
}
