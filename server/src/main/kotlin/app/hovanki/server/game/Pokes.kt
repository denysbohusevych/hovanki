package app.hovanki.server.game

import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.PlayerId

/**
 * Whom a change of a game concerns (docs/adr/0015-websockets.md, section 5): the players whose phones are poked to sync
 * now instead of after their usual pause. [Game] collects them under its lock where its state changes in a way a player
 * should see soon, [GameService] hands them to the [PokeSink] after the lock is released. Positions moving are not a
 * poke: they come with every sync anyway. A chat message is kept with its readers ([addChat]): an app that takes the
 * chat's frames gets the message itself, an older one a poke.
 */
class Pokes {
    /** Every player of the game. */
    var everyone: Boolean = false
        private set

    private val players = HashSet<PlayerId>()

    private class ChatDelivery(val message: ChatMessage, val readers: Set<PlayerId>)

    private val chat = ArrayList<ChatDelivery>()

    val isEmpty: Boolean get() = !everyone && players.isEmpty() && chat.isEmpty()

    fun addEveryone() {
        everyone = true
        players.clear()
    }

    fun add(playerId: PlayerId) {
        if (!everyone) players += playerId
    }

    fun addAll(playerIds: Iterable<PlayerId>) {
        if (!everyone) players += playerIds
    }

    /** A new chat [message] that [readers] may read; nobody: nothing to tell anybody. */
    fun addChat(message: ChatMessage, readers: Collection<PlayerId>) {
        if (readers.isNotEmpty()) chat += ChatDelivery(message, readers.toHashSet())
    }

    /**
     * Whether [playerId]'s phone is to be poked: something changed for them, or a chat message came that the phone
     * takes only by a sync ([takesChat] false: an app without [chatFor]'s frames, or polling).
     */
    fun concerns(playerId: PlayerId, takesChat: Boolean = false): Boolean =
        everyone || playerId in players || (!takesChat && chat.any { playerId in it.readers })

    /** The new chat messages [playerId] may read, oldest first. */
    fun chatFor(playerId: PlayerId): List<ChatMessage> =
        chat.filter { playerId in it.readers }.map { it.message }.sortedBy { it.seq }

    override fun toString(): String {
        val whom = if (everyone) "everyone" else players.joinToString(prefix = "[", postfix = "]") { it.value }
        return if (chat.isEmpty()) whom else "$whom, ${chat.size} chat"
    }
}

/** Where the pokes of a game go: the open sockets of its players ([app.hovanki.server.live.GameSockets]). */
fun interface PokeSink {
    /** Pokes the phones of [gameId] that [pokes] names, except [except]'s: it has just got the fresh snapshot itself. */
    fun poke(gameId: GameId, pokes: Pokes, except: PlayerId?)
}
