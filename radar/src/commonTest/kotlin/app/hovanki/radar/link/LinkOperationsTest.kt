package app.hovanki.radar.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkOperationsTest {
    private val started = mutableListOf<String>()
    private val refused = mutableListOf<String>()
    private val operations = LinkOperations(refused = { refused += it }, timeoutMillis = 1_000)

    private fun op(name: String, accepts: Boolean = true): () -> Boolean = {
        started += name
        accepts
    }

    @Test
    fun oneAtATimeInOrder() {
        assertTrue(operations.add("read", 0, op("read")))
        assertTrue(operations.add("subscribed", 0, op("subscribed")))
        assertEquals(listOf("read"), started)
        assertEquals("read", operations.running)
        operations.done(10)
        assertEquals(listOf("read", "subscribed"), started)
        operations.done(20)
        assertNull(operations.running)
        assertTrue(operations.add("wrote", 30, op("wrote")))
        assertEquals("wrote", operations.running)
    }

    @Test
    fun aWaitingNameIsNotQueuedTwice() {
        operations.add("read", 0, op("read"))
        assertTrue(operations.add("wrote", 0, op("wrote")))
        assertFalse(operations.add("wrote", 0, op("wrote")))
        assertEquals(listOf("wrote"), operations.waiting)
        // The running one may be queued again: it is not waiting.
        assertTrue(operations.add("read", 0, op("read")))
    }

    @Test
    fun aRefusedStartIsReportedAndTheNextOneStarts() {
        operations.add("read", 0, op("read", accepts = false))
        assertEquals(listOf("read"), refused)
        assertNull(operations.running)
        operations.add("wrote", 0, op("wrote"))
        operations.add("read_rssi", 0) { error("the stack threw") }
        operations.done(5)
        assertEquals(listOf("read", "read_rssi"), refused)
        assertNull(operations.running)
    }

    @Test
    fun anOperationWithoutAnAnswerIsGivenUp() {
        operations.add("wrote", 0, op("wrote"))
        operations.add("read_rssi", 0, op("read_rssi"))
        assertNull(operations.tick(999))
        assertEquals("wrote", operations.tick(1_000))
        assertEquals("read_rssi", operations.running)
        operations.clear()
        assertNull(operations.running)
        assertTrue(operations.waiting.isEmpty())
    }
}
