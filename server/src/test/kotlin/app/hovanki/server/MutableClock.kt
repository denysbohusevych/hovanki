package app.hovanki.server

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** A clock that stands still until the test moves it. Starts now, in whole milliseconds (like timestamps in the DB). */
class MutableClock(
    start: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS),
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    @Volatile
    private var now: Instant = start

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}
