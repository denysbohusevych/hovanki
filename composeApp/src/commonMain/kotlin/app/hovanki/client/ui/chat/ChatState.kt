package app.hovanki.client.ui.chat

import app.hovanki.client.account.AccountState
import app.hovanki.client.session.ChatLine
import app.hovanki.client.session.SessionError
import app.hovanki.client.session.SessionState
import app.hovanki.client.session.chatLines
import app.hovanki.client.session.unreadChatCount
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.rules.ChatRules

/**
 * Everything the chat button and the chat panel show, in one object (docs/architecture.md, «Состояние экрана»): the
 * game's messages and what this phone is writing.
 */
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
    /** The message whose actions (report, block) are shown. */
    val selectedSeq: Long? = null,
    /** A message could not be sent or reported (rate limit, too long, not visible...). */
    val error: SessionError? = null,
    /** The input. */
    val text: String = "",
    /** Send to the player's team only (when the phase has teams). */
    val toTeam: Boolean = false,
    val isSending: Boolean = false,
    /** Something to send and nothing on its way. */
    val canSend: Boolean = false,
    /** Reporting or blocking failed, or a report went through. */
    val message: FormMessage? = null,
    /** Blocking a sender is on its way. */
    val isBusy: Boolean = false,
)

sealed interface ChatEvent {
    data object Open : ChatEvent

    data object Close : ChatEvent

    data class EditText(val text: String) : ChatEvent

    data class SelectChannel(val toTeam: Boolean) : ChatEvent

    data object Send : ChatEvent

    /** A long press on another player's message: its actions. */
    data class Select(val line: ChatLine) : ChatEvent

    data object CancelSelection : ChatEvent

    data class Report(val seq: Long) : ChatEvent

    data class Block(val userId: UserId) : ChatEvent

    data object DismissMessage : ChatEvent
}

/** This phone's own part of the chat. */
internal data class ChatLocal(
    /** The game whose chat panel is open; per game, since the view model outlives the screens of one game. */
    val openIn: GameId? = null,
    val selectedSeq: Long? = null,
    val text: String = "",
    val toTeam: Boolean = false,
    val isSending: Boolean = false,
)

/** What the chat comes from, outside this phone's choices. */
internal data class ChatInputs(
    val session: SessionState,
    val blocked: Set<UserId>,
    val account: AccountState,
    val message: FormMessage?,
    val isBusy: Boolean,
)

internal fun buildChatUiState(inputs: ChatInputs, local: ChatLocal): ChatUiState {
    val state = inputs.session
    val snapshot = state.snapshot
    return ChatUiState(
        isOpen = local.openIn != null && local.openIn == state.session?.gameId,
        lines = state.chatLines(inputs.blocked),
        unread = state.unreadChatCount(inputs.blocked),
        hasTeamChannel = snapshot != null && ChatRules.hasTeamChannel(snapshot.phase),
        canBlock = inputs.account.isLoggedIn,
        selectedSeq = local.selectedSeq,
        error = state.lastError,
        text = local.text,
        toTeam = local.toTeam,
        isSending = local.isSending,
        canSend = !local.isSending && ChatRules.clean(local.text).isNotEmpty(),
        message = inputs.message,
        isBusy = inputs.isBusy,
    )
}

/** The panel is open on this game and a message came that the player has not read. */
internal fun hasUnseen(session: SessionState, local: ChatLocal): Boolean {
    val newest = session.chat.lastOrNull()?.seq ?: 0L
    return local.openIn != null && local.openIn == session.session?.gameId && newest > session.chatReadSeq
}
