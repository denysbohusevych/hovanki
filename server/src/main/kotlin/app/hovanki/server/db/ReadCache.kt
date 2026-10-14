package app.hovanki.server.db

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What a hot read of the database returned, kept in memory for [ttl]: an account token's session, a user's inbox.
 * The server's own writes that change a value [forget] it, at once and again once their transaction commits, and a
 * read that began before a forget never puts its value in; so the server never answers with what it changed itself.
 * A change made around the server (SQL by hand) shows within [ttl]. [Duration.ZERO] keeps nothing.
 */
class ReadCache<K : Any, V : Any>(ttl: Duration, private val clock: Clock) {
    private val ttlMillis = ttl.toMillis()
    private val entries = ConcurrentHashMap<K, Entry<V>>()

    /** Grows with every forget: a read that began before one doesn't keep what it read. */
    private val forgets = AtomicLong()

    private class Entry<V>(val value: V, val readAtMillis: Long)

    /** The value of [key] read at most [ttl] ago, else [read]'s, kept. A null from [read] is not kept. */
    fun get(key: K, read: () -> V?): V? {
        if (ttlMillis <= 0) return read()
        val now = clock.millis()
        entries[key]?.takeIf { now - it.readAtMillis < ttlMillis }?.let { return it.value }
        val before = forgets.get()
        val value = read() ?: return null
        entries.compute(key) { _, old -> if (forgets.get() == before) Entry(value, now) else old }
        return value
    }

    /** Changes the kept value of [key], if any, without making it younger. */
    fun update(key: K, change: (V) -> V) {
        entries.computeIfPresent(key) { _, entry -> Entry(change(entry.value), entry.readAtMillis) }
    }

    fun forget(key: K) = forgetNowAndAfterCommit { entries.remove(key) }

    /** Forgets the values [matches] picks; looks at every one kept. */
    fun forgetIf(matches: (K, V) -> Boolean) =
        forgetNowAndAfterCommit { entries.entries.removeIf { (key, entry) -> matches(key, entry.value) } }

    fun forgetAll() = forgetNowAndAfterCommit { entries.clear() }

    /** Drops the values older than [ttl]: [get] would read them again anyway. */
    fun sweep() {
        val oldest = clock.millis() - ttlMillis
        entries.values.removeIf { it.readAtMillis <= oldest }
    }

    /** Values kept right now (for tests). */
    fun size(): Int = entries.size

    private fun forgetNowAndAfterCommit(forget: () -> Unit) {
        val once = {
            forgets.incrementAndGet()
            forget()
        }
        once()
        // Until the commit, a read sees the rows as they were and may keep them again.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = once()
                },
            )
        }
    }
}
