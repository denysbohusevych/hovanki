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
import okio.ByteString.Companion.encodeUtf8

/**
 * The rare big structures that travel inside sections as v1 JSON (docs/live-v2/spec.md §10): settings, the board,
 * quests, perks, the capacity and the big game. One [Codec] per kind remembers the last value both ways, so a value
 * that did not change is neither encoded again on the server nor decoded again on the phone.
 */
class LiveJson {
    val settings = Codec(GameSettings.serializer())
    val items = Codec(ListSerializer(BoardItem.serializer()))
    val quests = Codec(ListSerializer(QuestView.serializer()))
    val perks = Codec(ListSerializer(PerkView.serializer()))
    val capacity = Codec(ZoneCapacity.serializer())
    val bigGame = Codec(BigGameInfo.serializer())

    /** One kind: [encode] a value to JSON bytes (protocolJson), [decode] them back. Not thread safe: one per user. */
    class Codec<T : Any>(private val serializer: KSerializer<T>) {
        private var lastValue: T? = null
        private var lastBytes: ByteString = ByteString.EMPTY

        /** Null: empty bytes (the v1 field is null). */
        fun encode(value: T?): ByteString {
            if (value == null) return ByteString.EMPTY
            val last = lastValue
            if (last != null && (last === value || last == value)) return lastBytes
            val bytes = protocolJson.encodeToString(serializer, value).encodeUtf8()
            remember(value, bytes)
            return bytes
        }

        /** Empty bytes: null. */
        fun decode(bytes: ByteString): T? {
            if (bytes.size == 0) return null
            val last = lastValue
            if (last != null && bytes == lastBytes) return last
            val value = protocolJson.decodeFromString(serializer, bytes.utf8())
            remember(value, bytes)
            return value
        }

        private fun remember(value: T, bytes: ByteString) {
            lastValue = value
            lastBytes = bytes
        }
    }
}
