package app.hovanki.shared.live

import app.hovanki.shared.live.v2.Error
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.live.v2.ChatMessage as WireChatMessage

/** What a Down carries besides the state (docs/live-v2/spec.md §11, §13): the chat and the errors, both ways. */
object LiveDownlink {
    fun chatMessage(message: ChatMessage): WireChatMessage = WireChatMessage(
        seq = message.seq,
        player_id = message.playerId.value,
        text = message.text,
        sent_at_ms = message.sentAtMillis,
        channel = LiveEnums.wire(message.channel),
    )

    fun chatMessage(message: WireChatMessage): ChatMessage = ChatMessage(
        seq = message.seq,
        playerId = PlayerId(message.player_id),
        text = message.text,
        sentAtMillis = message.sent_at_ms,
        channel = LiveEnums.v1(message.channel),
    )

    /** The chat of one Down: the oldest messages that fit [LiveLimits.MAX_CHAT_BYTES], and whether more wait. */
    class Chat(val messages: List<WireChatMessage>, val more: Boolean)

    /** At least one message always goes, however long: a message is shorter than the limit (`ChatRules`). */
    fun fitChat(messages: List<ChatMessage>, maxBytes: Int = LiveLimits.MAX_CHAT_BYTES): Chat {
        val fitted = ArrayList<WireChatMessage>(messages.size)
        var bytes = 0
        for (message in messages) {
            val wire = chatMessage(message)
            // The field's tag and length come on top of the message itself.
            val size = WireChatMessage.ADAPTER.encodedSize(wire) + FIELD_OVERHEAD
            if (fitted.isNotEmpty() && bytes + size > maxBytes) return Chat(fitted, more = true)
            fitted += wire
            bytes += size
        }
        return Chat(fitted, more = false)
    }

    fun error(error: ApiError, retryAfterSeconds: Long? = null): Error = Error(
        code = LiveEnums.wire(error.code),
        reason = error.reason?.name.orEmpty(),
        message = error.message,
        until_ms = error.untilMillis,
        retry_after_s = retryAfterSeconds?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
    )

    /** A reason this phone doesn't know reads as null, as in v1's JSON. */
    fun apiError(error: Error): ApiError = ApiError(
        code = LiveEnums.v1(error.code),
        message = error.message,
        reason = ErrorReason.entries.firstOrNull { it.name == error.reason },
        untilMillis = error.until_ms,
    )

    private const val FIELD_OVERHEAD = 3
}
