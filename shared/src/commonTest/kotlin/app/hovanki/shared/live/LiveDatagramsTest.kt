package app.hovanki.shared.live

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fragments, the replay window and the RESET of the UDP datagrams (docs/live-v2/spec.md §5, §18). */
class LiveDatagramsTest {
    private val now = 1_000L

    private fun message(size: Int) = Random(size).nextBytes(size)

    @Test
    fun aMessageThatFitsGoesWhole() {
        val bytes = message(LiveLimits.MAX_MESSAGE_IN_DATAGRAM)
        val plaintexts = LiveDatagrams.plaintexts(bytes, messageId = 1)
        assertEquals(1, plaintexts.size)
        assertEquals(LiveLimits.MAX_PLAINTEXT_BYTES, plaintexts.single().size)
        assertContentEquals(bytes, LiveReassembler().accept(plaintexts.single(), now))
    }

    @Test
    fun biggerMessagesAreCutAndPutTogether() {
        for ((size, count) in listOf(1_171 to 2, 2_332 to 2, 2_333 to 3, 74_624 to 64)) {
            val bytes = message(size)
            val plaintexts = LiveDatagrams.plaintexts(bytes, messageId = 65_535)
            assertEquals(count, plaintexts.size, "$size bytes")
            assertTrue(plaintexts.all { it.size <= LiveLimits.MAX_PLAINTEXT_BYTES })
            val reassembler = LiveReassembler()
            // Any order: the last fragment to come completes the message.
            val shuffled = plaintexts.shuffled(Random(size))
            shuffled.dropLast(1).forEach { assertNull(reassembler.accept(it, now)) }
            assertContentEquals(bytes, reassembler.accept(shuffled.last(), now), "$size bytes")
        }
        assertFailsWith<IllegalArgumentException> { LiveDatagrams.plaintexts(message(74_625), messageId = 1) }
    }

    @Test
    fun aLostFragmentLosesTheMessage() {
        val plaintexts = LiveDatagrams.plaintexts(message(5_000), messageId = 7)
        for (lost in plaintexts.indices) {
            val reassembler = LiveReassembler()
            plaintexts.filterIndexed { i, _ -> i != lost }.forEach { assertNull(reassembler.accept(it, now)) }
            // It comes too late: the message was given up on.
            assertNull(reassembler.accept(plaintexts[lost], now + LiveLimits.FRAGMENT_TIMEOUT_MILLIS + 1))
        }
    }

    @Test
    fun aRepeatedFragmentIsIgnoredAndOnlyTwoMessagesAreBuiltAtOnce() {
        val first = LiveDatagrams.plaintexts(message(3_000), messageId = 1)
        val second = LiveDatagrams.plaintexts(message(3_001), messageId = 2)
        val third = LiveDatagrams.plaintexts(message(3_002), messageId = 3)
        val reassembler = LiveReassembler()
        assertNull(reassembler.accept(first[0], now))
        assertNull(reassembler.accept(first[0], now))
        assertNull(reassembler.accept(second[0], now))
        // The third pushes the oldest (the first) out.
        assertNull(reassembler.accept(third[0], now))
        assertContentEquals(message(3_001), second.drop(1).map { reassembler.accept(it, now) }.last())
        // The first lost its beginning: the rest never completes it.
        first.drop(1).forEach { assertNull(reassembler.accept(it, now)) }
    }

    @Test
    fun malformedPlaintextsAreDropped() {
        val reassembler = LiveReassembler()
        assertNull(reassembler.accept(byteArrayOf(), now))
        assertNull(reassembler.accept(byteArrayOf(0x03, 1, 2), now))
        // A fragment with count 1, an index past the count, or no bytes.
        assertNull(reassembler.accept(byteArrayOf(0x02, 0, 1, 0, 1, 9), now))
        assertNull(reassembler.accept(byteArrayOf(0x02, 0, 1, 2, 2, 9), now))
        assertNull(reassembler.accept(byteArrayOf(0x02, 0, 1, 0, 2), now))
    }

    @Test
    fun theReplayWindowAtItsEdges() {
        for ((back, passes) in listOf(63 to true, 64 to false, 65 to false)) {
            val window = LiveReplayWindow()
            assertTrue(window.accept(100))
            assertEquals(passes, window.accept(100 - back), "$back below the highest")
        }
        val window = LiveReplayWindow()
        assertFalse(window.accept(0))
        assertTrue(window.accept(1))
        assertFalse(window.accept(1))
        assertTrue(window.accept(3))
        assertTrue(window.accept(2))
        assertFalse(window.accept(2))
        // A jump further than the window forgets everything below it.
        assertTrue(window.accept(200))
        assertFalse(window.accept(3))
        assertTrue(window.accept(199))
        assertTrue(window.accept(137))
        assertFalse(window.accept(136))
    }

    @Test
    fun aResetNeedsTheSessionsOwnTag() {
        val tag = ByteArray(16) { it.toByte() }
        val reset = LiveDatagrams.reset(42L, tag)
        assertTrue(LiveDatagrams.isReset(reset, 42L, tag))
        assertFalse(LiveDatagrams.isReset(reset, 43L, tag))
        assertFalse(LiveDatagrams.isReset(reset, 42L, ByteArray(16)))
        assertFalse(LiveDatagrams.isReset(reset.copyOf(24), 42L, tag))
        assertEquals(LiveDatagrams.TYPE_RESET, LiveDatagrams.head(reset)?.type)
        // The RESET is smaller than the smallest DATA it answers.
        assertTrue(LiveDatagrams.RESET_BYTES < LiveDatagrams.MIN_DATA_FOR_RESET)
    }

    @Test
    fun theHeadIsReadBeforeDecrypting() {
        val datagram = LiveDatagrams.header(-2L, 70_000) + ByteArray(LiveLimits.TAG_BYTES)
        val head = LiveDatagrams.head(datagram)
        assertEquals(LiveDatagrams.TYPE_DATA, head?.type)
        assertEquals(-2L, head?.sessionId)
        assertEquals(70_000, head?.pn)
        assertNull(LiveDatagrams.head(datagram.copyOf(LiveLimits.HEADER_BYTES)))
        assertNull(LiveDatagrams.head(byteArrayOf(0x31) + datagram.copyOfRange(1, datagram.size)))
    }
}
