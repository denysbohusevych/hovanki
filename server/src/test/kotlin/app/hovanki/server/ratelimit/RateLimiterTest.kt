package app.hovanki.server.ratelimit

import app.hovanki.server.MutableClock
import app.hovanki.server.game.GameException
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RateLimiterTest {
    private val clock = MutableClock(Instant.parse("2026-06-01T12:00:00Z"))
    private val limiter = RateLimiter(RateLimitProperties(), clock)

    @Test
    fun allowsTheCountThenSaysWhenToComeBack() {
        repeat(20) { limiter.acquire(RateLimit.REGISTER_PER_IP, "192.0.2.1") }
        clock.advance(Duration.ofMinutes(10))

        val refused = assertFailsWith<GameException> { limiter.acquire(RateLimit.REGISTER_PER_IP, "192.0.2.1") }
        assertEquals(ErrorCode.WRONG_STATE, refused.code)
        assertEquals(ErrorReason.TOO_MANY_REQUESTS, refused.reason)
        // The first of the 20 leaves the window an hour after it came.
        assertEquals(Duration.ofMinutes(50).seconds, refused.retryAfterSeconds)

        // Other keys and other limits are counted apart.
        limiter.acquire(RateLimit.REGISTER_PER_IP, "192.0.2.2")
        limiter.acquire(RateLimit.PASSWORD_RESET_PER_IP, "192.0.2.1")
    }

    @Test
    fun theWindowSlides() {
        limiter.acquire(RateLimit.EMAIL_PER_MINUTE, "user")
        clock.advance(Duration.ofSeconds(30))
        val refused = assertFailsWith<GameException> { limiter.acquire(RateLimit.EMAIL_PER_MINUTE, "user") }
        assertEquals(30, refused.retryAfterSeconds)
        // A refused attempt does not count.
        clock.advance(Duration.ofSeconds(30))
        limiter.acquire(RateLimit.EMAIL_PER_MINUTE, "user")
        assertFailsWith<GameException> { limiter.acquire(RateLimit.EMAIL_PER_MINUTE, "user") }
    }

    @Test
    fun onlyRecordedFailuresCount() {
        repeat(20) { limiter.check(RateLimit.LOGIN_PER_LOGIN, "bob") }
        repeat(10) { limiter.record(RateLimit.LOGIN_PER_LOGIN, "bob") }

        val refused = assertFailsWith<GameException> { limiter.check(RateLimit.LOGIN_PER_LOGIN, "bob") }
        assertEquals(Duration.ofMinutes(15).seconds, refused.retryAfterSeconds)
        limiter.check(RateLimit.LOGIN_PER_LOGIN, "alice")
        clock.advance(Duration.ofMinutes(15))
        limiter.check(RateLimit.LOGIN_PER_LOGIN, "bob")
    }

    @Test
    fun offMeansNoLimits() {
        val off = RateLimiter(RateLimitProperties(enabled = false), clock)
        repeat(100) {
            off.acquire(RateLimit.EMAIL_PER_MINUTE, "user")
            off.record(RateLimit.LOGIN_PER_LOGIN, "bob")
        }
        off.check(RateLimit.LOGIN_PER_LOGIN, "bob")
        assertEquals(0, off.size())
    }

    @Test
    fun idleWindowsAreDropped() {
        limiter.acquire(RateLimit.EMAIL_PER_MINUTE, "user")
        limiter.acquire(RateLimit.EMAIL_PER_HOUR, "user")
        clock.advance(Duration.ofMinutes(2))
        limiter.cleanUp()
        assertEquals(1, limiter.size())
        clock.advance(Duration.ofHours(1))
        limiter.cleanUp()
        assertEquals(0, limiter.size())
    }

    @Test
    fun memoryIsBounded() {
        val small = RateLimiter(RateLimitProperties(maxKeys = 100), clock)
        repeat(1000) { small.acquire(RateLimit.REGISTER_PER_IP, "key-$it") }
        assertTrue(small.size() <= 100, "${small.size()}")
    }

    @Test
    fun retryAfterIsWholeSecondsRoundedUp() {
        assertEquals(1, GameException.tooManyRequests(Duration.ZERO).retryAfterSeconds)
        assertEquals(1, GameException.tooManyRequests(Duration.ofMillis(1000)).retryAfterSeconds)
        assertEquals(2, GameException.tooManyRequests(Duration.ofMillis(1001)).retryAfterSeconds)
    }
}
