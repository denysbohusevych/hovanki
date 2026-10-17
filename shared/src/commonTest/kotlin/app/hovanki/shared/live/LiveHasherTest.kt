package app.hovanki.shared.live

import app.hovanki.shared.live.v2.BoardSection
import app.hovanki.shared.live.v2.LocationPart
import app.hovanki.shared.live.v2.Presence
import app.hovanki.shared.live.v2.State
import okio.ByteString.Companion.toByteString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveHasherTest {
    @Test
    fun theHashIsTheHashOfTheMessagesBytes() {
        val hasher = LiveHasher()
        val state = LiveSections.fromSnapshot(LiveFixtures.everything())
        // Every section and every part of every item, one after another through the same buffer.
        assertEquals(XxHash64.hash(State.ADAPTER.encode(state)), hasher.hash(State.ADAPTER, state))
        for (item in state.players!!.upsert) {
            val location = item.location ?: LocationPart()
            val bytes = LocationPart.ADAPTER.encode(location)
            assertEquals(XxHash64.hash(bytes), hasher.hash(LocationPart.ADAPTER, location))
            assertEquals(bytes.size.toLong(), hasher.size)
        }
        assertEquals(XxHash64.hash(ByteArray(0)), hasher.hash(Presence.ADAPTER, Presence()))
        assertEquals(0L, hasher.size)
    }

    @Test
    fun aMessageLongerThanABufferSegment() {
        val hasher = LiveHasher()
        val board = BoardSection(items_json = Random(7).nextBytes(20_000).toByteString())
        assertEquals(XxHash64.hash(BoardSection.ADAPTER.encode(board)), hasher.hash(BoardSection.ADAPTER, board))
        // And a short one after it, in the same buffer.
        val presence = Presence(last_seen_ms = 7L)
        assertEquals(XxHash64.hash(Presence.ADAPTER.encode(presence)), hasher.hash(Presence.ADAPTER, presence))
    }
}
