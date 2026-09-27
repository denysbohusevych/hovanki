package app.hovanki.client.session

import app.hovanki.shared.protocol.ChatChannel
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.rules.ChatRules

// The in-game chat as the chat panel (and the e2e bots) read it: GameSessionManager keeps the messages
// (SessionState.chat), these turn them into lines. Blocking is per account and hides messages on this device only.

/** One chat message ready to show. */
data class ChatLine(
    val seq: Long,
    val playerId: PlayerId,
    /** The sender's name in this game; null if the sender is not among the snapshot's players. */
    val senderName: String?,
    /** The sender's account; null for a guest. */
    val senderUserId: UserId?,
    val text: String,
    val sentAtMillis: Long,
    val channel: ChatChannel,
    /** Sent by the viewer. */
    val isMine: Boolean,
) {
    /** The sender has no account: they can't be added as a friend or blocked. */
    val isGuest: Boolean get() = senderUserId == null

    /** Only the sender's team sees it. */
    val isTeam: Boolean get() = channel != ChatChannel.ALL
}

/**
 * [messages] (oldest first) as lines, with the senders' names and accounts from [players]; messages of users in
 * [blocked] are left out.
 */
fun chatLines(
    messages: List<ChatMessage>,
    players: List<PlayerView>,
    me: PlayerId,
    blocked: Set<UserId> = emptySet(),
): List<ChatLine> {
    val byId = players.associateBy { it.id }
    return messages.mapNotNull { message ->
        val sender = byId[message.playerId]
        if (sender?.userId != null && sender.userId in blocked) return@mapNotNull null
        ChatLine(
            seq = message.seq,
            playerId = message.playerId,
            senderName = sender?.name,
            senderUserId = sender?.userId,
            text = message.text,
            sentAtMillis = message.sentAtMillis,
            channel = message.channel,
            isMine = message.playerId == me,
        )
    }
}

/** Messages after [readSeq] the viewer has not read: never their own, never from users in [blocked]. */
fun unreadChatCount(
    messages: List<ChatMessage>,
    players: List<PlayerView>,
    me: PlayerId,
    readSeq: Long,
    blocked: Set<UserId> = emptySet(),
): Int = chatLines(messages.filter { it.seq > readSeq }, players, me, blocked).count { !it.isMine }

/** The current game's chat as lines; empty without a snapshot. */
fun SessionState.chatLines(blocked: Set<UserId> = emptySet()): List<ChatLine> {
    val snapshot = snapshot ?: return emptyList()
    return chatLines(chat, snapshot.players, snapshot.me.playerId, blocked)
}

/** Unread messages of the current game (see [SessionState.chatReadSeq]). */
fun SessionState.unreadChatCount(blocked: Set<UserId> = emptySet()): Int {
    val snapshot = snapshot ?: return 0
    return unreadChatCount(chat, snapshot.players, snapshot.me.playerId, chatReadSeq, blocked)
}

/**
 * [known] plus [received], by seq and oldest first, without duplicates; only the newest [limit] kept (the server keeps
 * no more either).
 */
internal fun mergeChat(
    known: List<ChatMessage>,
    received: List<ChatMessage>,
    limit: Int = ChatRules.HISTORY_SIZE,
): List<ChatMessage> {
    if (received.isEmpty()) return known
    val newest = known.lastOrNull()?.seq ?: Long.MIN_VALUE
    // The usual case: only newer messages, already in order.
    if (received.first().seq > newest && received.zipWithNext().all { (a, b) -> a.seq < b.seq }) {
        return (known + received).takeLast(limit)
    }
    return (known + received).associateBy { it.seq }.values.sortedBy { it.seq }.takeLast(limit)
}
