package app.hovanki.shared.live

import app.hovanki.shared.protocol.BigGameInfo
import app.hovanki.shared.protocol.BoardItem
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.PerkView
import app.hovanki.shared.protocol.QuestView
import app.hovanki.shared.protocol.ZoneCapacity
import app.hovanki.shared.protocol.protocolJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.concurrent.Volatile

/**
 * The rare big structures that travel inside sections as v1 JSON (docs/live-v2/spec.md §10): settings, the board,
 * quests, perks, the capacity and the big game. One [Codec] per kind remembers the last value both ways, so a value
 * that did not change is neither encoded again on the server nor decoded again on the phone.
 *
 * The kinds every player of a game sees alike are in [game]: the server gives the sessions of one game the same one,
 * so their bytes are kept once per game and not once per player (docs/live-v2/server.md §3.4).
 */
class LiveJson(val game: Game = Game()) {
    val settings: Codec<GameSettings> get() = game.settings
    val capacity: Codec<ZoneCapacity> get() = game.capacity
    val bigGame: Codec<BigGameInfo> get() = game.bigGame

    /** Per player: the board's items by role, the player's quests and perks. */
    val items = Codec(ITEMS)
    val quests = Codec(QUESTS)
    val perks = Codec(PERKS)

    /** The kinds of a whole game: settings, capacity, big game. */
    class Game {
        val settings = Codec(GameSettings.serializer())
        val capacity = Codec(ZoneCapacity.serializer())
        val bigGame = Codec(BigGameInfo.serializer())
    }

    /**
     * One kind: [encode] a value to JSON bytes (protocolJson), [decode] them back. Safe to share between threads: the
     * value and its bytes are remembered together, and two threads that miss at once only both encode.
     */
    class Codec<T : Any>(private val serializer: KSerializer<T>) {
        private class Last<T>(val value: T, val bytes: ByteString)

        @Volatile
        private var last: Last<T>? = null

        /** Null: empty bytes (the v1 field is null). */
        fun encode(value: T?): ByteString {
            if (value == null) return ByteString.EMPTY
            val known = last
            if (known != null && (known.value === value || known.value == value)) return known.bytes
            // Without the string's own copy, which an encoded ByteString keeps (it also holds its text).
            val bytes = protocolJson.encodeToString(serializer, value).encodeToByteArray().toByteString()
            last = Last(value, bytes)
            return bytes
        }

        /** Empty bytes: null. */
        fun decode(bytes: ByteString): T? {
            if (bytes.size == 0) return null
            val known = last
            if (known != null && bytes == known.bytes) return known.value
            val value = protocolJson.decodeFromString(serializer, bytes.utf8())
            last = Last(value, bytes)
            return value
        }
    }

    private companion object {
        val ITEMS = ListSerializer(BoardItem.serializer())
        val QUESTS = ListSerializer(QuestView.serializer())
        val PERKS = ListSerializer(PerkView.serializer())
    }
}
