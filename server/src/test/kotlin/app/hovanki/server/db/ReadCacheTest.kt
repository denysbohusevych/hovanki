package app.hovanki.server.db

import app.hovanki.server.MutableClock
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadCacheTest {
    private val clock = MutableClock()
    private val cache = ReadCache<String, String>(Duration.ofMinutes(1), clock)
    private var stored = "a"
    private var reads = 0

    private fun read(): String? {
        reads++
        return stored
    }

    @Test
    fun keepsAValueForItsTtl() {
        assertEquals("a", cache.get("k", ::read))
        stored = "b"
        clock.advance(Duration.ofSeconds(59))
        assertEquals("a", cache.get("k", ::read))
        assertEquals(1, reads)
        clock.advance(Duration.ofSeconds(1))
        assertEquals("b", cache.get("k", ::read))
        assertEquals(2, reads)
    }

    @Test
    fun forgettingReadsAgainAtOnce() {
        cache.get("k", ::read)
        cache.get("other", ::read)
        stored = "b"
        cache.forget("k")
        assertEquals("b", cache.get("k", ::read))
        assertEquals("a", cache.get("other", ::read))

        stored = "c"
        cache.forgetIf { key, _ -> key == "other" }
        assertEquals("c", cache.get("other", ::read))
        assertEquals("b", cache.get("k", ::read))

        stored = "d"
        cache.forgetAll()
        assertEquals("d", cache.get("k", ::read))
        assertEquals("d", cache.get("other", ::read))
    }

    @Test
    fun aReadThatBeganBeforeAForgetKeepsNothing() {
        // The change commits and forgets while the read is under way: what it read may be the old row.
        assertEquals("a", cache.get("k") { read().also { cache.forget("k") } })
        stored = "b"
        assertEquals("b", cache.get("k", ::read))
    }

    @Test
    fun forgetsAgainWhenTheTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization()
        try {
            cache.forget("k")
            // Before the commit a read still sees the old row and keeps it.
            assertEquals("a", cache.get("k", ::read))
            stored = "b"
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
        assertEquals("b", cache.get("k", ::read))
    }

    @Test
    fun aMissingValueIsNotKept() {
        stored = "a"
        assertNull(cache.get("k") { null })
        assertEquals("a", cache.get("k", ::read))
    }

    @Test
    fun updateKeepsTheAge() {
        cache.get("k", ::read)
        clock.advance(Duration.ofSeconds(30))
        cache.update("k") { "$it!" }
        cache.update("missing") { "never" }
        assertEquals("a!", cache.get("k", ::read))
        assertEquals(1, cache.size())
        clock.advance(Duration.ofSeconds(30))
        stored = "b"
        assertEquals("b", cache.get("k", ::read))
    }

    @Test
    fun sweepDropsWhatIsOld() {
        cache.get("old", ::read)
        clock.advance(Duration.ofSeconds(40))
        cache.get("young", ::read)
        clock.advance(Duration.ofSeconds(20))
        cache.sweep()
        assertEquals(1, cache.size())
    }

    @Test
    fun aZeroTtlKeepsNothing() {
        val none = ReadCache<String, String>(Duration.ZERO, clock)
        none.get("k", ::read)
        none.get("k", ::read)
        assertEquals(2, reads)
        assertEquals(0, none.size())
    }
}
