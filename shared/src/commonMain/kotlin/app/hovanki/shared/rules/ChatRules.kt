package app.hovanki.shared.rules

import app.hovanki.shared.protocol.ChatChannel
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.Role

/**
 * In-game chat (docs/adr/0004-accounts-friends-chat.md): who sees which message, and what a message may contain.
 * The server decides; the app uses the same rules to label messages and to check the input field.
 */
object ChatRules {
    const val MAX_LENGTH = 300

    /** Messages a game keeps; older ones are dropped. */
    const val HISTORY_SIZE = 200

    /** At most this many (the newest) messages per response. */
    const val MAX_PER_RESPONSE = 100

    /** Rate limit per player: [RATE_LIMIT_MESSAGES] within [RATE_LIMIT_WINDOW_MILLIS]. */
    const val RATE_LIMIT_MESSAGES = 5
    const val RATE_LIMIT_WINDOW_MILLIS = 10_000L

    /**
     * The text as sent to others: line breaks and tabs become spaces, other control characters and the invisible
     * bidirectional overrides (which can make a message read differently than it is written) are removed, then trimmed.
     */
    fun clean(text: String): String = buildString(text.length) {
        for (c in text) {
            when {
                c == '\n' || c == '\r' || c == '\t' -> append(' ')
                c.isISOControl() || c in BIDI_CONTROLS -> Unit
                else -> append(c)
            }
        }
    }.trim()

    /** 1..300 characters after [clean]. */
    fun isValid(text: String): Boolean = clean(text).length in 1..MAX_LENGTH

    /** The channel of a message sent in [phase] by a player with [role]; the lobby has only [ChatChannel.ALL]. */
    fun channelFor(phase: GamePhase, role: Role, team: Boolean): ChatChannel = when {
        !team || phase == GamePhase.LOBBY -> ChatChannel.ALL
        role == Role.SEEKER -> ChatChannel.SEEKERS
        else -> ChatChannel.HIDERS
    }

    /** Whether a player with [role] sees messages of [channel]. */
    fun canSee(channel: ChatChannel, role: Role): Boolean = when (channel) {
        ChatChannel.ALL -> true
        ChatChannel.SEEKERS -> role == Role.SEEKER
        ChatChannel.HIDERS -> role == Role.HIDER
    }

    /** Whether "my team" can be picked at all: not in the lobby, where roles are not assigned yet. */
    fun hasTeamChannel(phase: GamePhase): Boolean = phase != GamePhase.LOBBY

    private const val BIDI_CONTROLS = "؜‎‏‪‫‬‭‮⁦⁧⁨⁩"
}
