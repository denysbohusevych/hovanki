package app.hovanki.client.session

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerClockTest {
    private var deviceMillis = 1_000_000L
    private val clock = ServerClock { deviceMillis }

    @Test
    fun usesTheDeviceClockUntilTheServerTimeIsKnown() {
        assertEquals(1_000_000L, clock.now())
    }

    @Test
    fun followsTheServerWhenTheDeviceClockIsBehind() {
        clock.onServerTime(1_120_000L)
        assertEquals(1_120_000L, clock.now())

        deviceMillis += 5_000
        assertEquals(1_125_000L, clock.now())
        // A GPS fix taken a second ago by the device clock.
        assertEquals(1_124_000L, clock.toServerTime(deviceMillis - 1_000))
    }

    @Test
    fun followsTheServerWhenTheDeviceClockIsAhead() {
        clock.onServerTime(880_000L)
        assertEquals(880_000L, clock.now())
        assertEquals(879_000L, clock.toServerTime(999_000L))
    }

    @Test
    fun theLatestServerTimeWins() {
        clock.onServerTime(1_120_000L)
        clock.onServerTime(1_000_500L)
        assertEquals(1_000_500L, clock.now())
    }
}
