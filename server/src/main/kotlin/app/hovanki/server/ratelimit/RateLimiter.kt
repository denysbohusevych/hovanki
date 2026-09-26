package app.hovanki.server.ratelimit

import app.hovanki.server.game.GameException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * In-memory sliding-window rate limits ([RateLimit], configured in [RateLimitProperties]); time from [Clock]. One
 * server instance, so memory is enough: a restart forgets the counts, which only makes the limits softer.
 *
 * Keys are whatever the caller limits by: a client IP, a user id, an email or nickname key. Limits are separate, so
 * the same key under two limits never collides. Hitting a limit throws [GameException.tooManyRequests] (HTTP 429 with
 * `Retry-After`). Memory is bounded: windows without recent events are dropped every minute, and at most
 * [RateLimitProperties.maxKeys] are kept.
 *
 * Usage: `acquire(RateLimit.FRIEND_REQUESTS, userId.value)` before an action that counts; for limits on failures
 * (logins), [check] before and [record] after a failure.
 */
@Component
class RateLimiter(private val properties: RateLimitProperties, private val clock: Clock) {
    private data class Key(val limit: RateLimit, val key: String)

    /** Event times (epoch millis) inside the window, oldest first; never more than the limit's count. */
    private val windows = ConcurrentHashMap<Key, ArrayDeque<Long>>()

    /**
     * Counts one event of [limit] for [key]; when [key] has used up [limit] within its window, throws instead (and
     * counts nothing).
     */
    fun acquire(limit: RateLimit, key: String) {
        if (!properties.enabled) return
        val (count, window) = properties.limitOf(limit)
        val now = clock.millis()
        var retryAt: Long? = null
        // compute() keeps each window's updates atomic, also against the cleanup removing it.
        windows.compute(Key(limit, key)) { _, current ->
            val events = (current ?: ArrayDeque()).prune(now - window.toMillis())
            if (events.size >= count) {
                retryAt = events.firstOrNull()?.plus(window.toMillis()) ?: (now + window.toMillis())
            } else {
                events.addLast(now)
            }
            events
        }
        retryAt?.let { throw GameException.tooManyRequests(Duration.ofMillis(it - now)) }
        boundMemory()
    }

    /** Throws like [acquire] when [key] has used up [limit], but counts nothing: see [record]. */
    fun check(limit: RateLimit, key: String) {
        if (!properties.enabled) return
        val (count, window) = properties.limitOf(limit)
        val now = clock.millis()
        var retryAt: Long? = null
        windows.computeIfPresent(Key(limit, key)) { _, events ->
            events.prune(now - window.toMillis())
            if (events.size >= count) retryAt = events.firstOrNull()?.plus(window.toMillis())
            events.ifEmpty { null }
        }
        retryAt?.let { throw GameException.tooManyRequests(Duration.ofMillis(it - now)) }
    }

    /** Counts one event of [limit] for [key] without checking, e.g. a failed login. */
    fun record(limit: RateLimit, key: String) {
        if (!properties.enabled) return
        val (count, window) = properties.limitOf(limit)
        val now = clock.millis()
        windows.compute(Key(limit, key)) { _, current ->
            val events = (current ?: ArrayDeque()).prune(now - window.toMillis())
            events.addLast(now)
            while (events.size > count.coerceAtLeast(1)) events.removeFirst()
            events
        }
        boundMemory()
    }

    /** Drops the windows without events left in them. */
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    fun cleanUp() {
        val now = clock.millis()
        for (key in windows.keys) {
            val window = properties.limitOf(key.limit).window
            windows.computeIfPresent(key) { _, events -> events.prune(now - window.toMillis()).ifEmpty { null } }
        }
    }

    /** Windows tracked right now (for tests). */
    fun size(): Int = windows.size

    /**
     * Beyond [RateLimitProperties.maxKeys] (a flood of distinct keys), drops a tenth of the windows, whichever they
     * are: the limits get softer for some keys, the memory stays bounded.
     */
    private fun boundMemory() {
        val max = properties.maxKeys
        if (windows.size <= max) return
        val excess = windows.size - max + max / 10
        windows.keys.asSequence().take(excess).toList().forEach(windows::remove)
    }

    private fun ArrayDeque<Long>.prune(since: Long): ArrayDeque<Long> {
        while (isNotEmpty() && first() <= since) removeFirst()
        return this
    }
}
