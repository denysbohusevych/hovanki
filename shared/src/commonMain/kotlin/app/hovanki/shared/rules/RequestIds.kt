package app.hovanki.shared.rules

/**
 * Ids the app makes up for a request it may have to send again after getting no answer (`JoinGameRequest.requestId`,
 * `SendChatRequest.clientMessageId`): the server recognizes the repeated request and doesn't act twice. Long enough
 * to be unguessable, since a join id gives back a player; the app uses random UUIDs.
 */
object RequestIds {
    const val MIN_LENGTH = 16
    const val MAX_LENGTH = 64

    fun isValid(id: String): Boolean = id.length in MIN_LENGTH..MAX_LENGTH && id.all(::isIdChar)

    private fun isIdChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-'
}
