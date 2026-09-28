package app.hovanki.shared.rules

import app.hovanki.shared.protocol.RadarBand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProximityTest {
    private val secret = "00112233445566778899aabbccddeeff00112233"

    @Test
    fun tokensChangeEverySlotAndAreShort() {
        val now = 1_700_000_000_000L
        val token = RadarToken.at(secret, now)
        assertTrue(RadarToken.isWellFormed(token), token)
        assertEquals(token, RadarToken.at(secret, now + RadarToken.SLOT_MILLIS - 1 - now % RadarToken.SLOT_MILLIS))
        assertNotEquals(token, RadarToken.at(secret, now + RadarToken.SLOT_MILLIS))
        assertNotEquals(token, RadarToken.at("ffeeddccbbaa99887766554433221100ffeeddcc", now))
        assertFalse(RadarToken.isWellFormed("nope"))
    }

    @Test
    fun theServerAcceptsTheNeighbouringSlots() {
        val now = 1_700_000_000_000L
        val candidates = RadarToken.candidates(secret, now)
        assertEquals(3, candidates.distinct().size)
        assertTrue(RadarToken.at(secret, now) in candidates)
        assertTrue(RadarToken.at(secret, now - RadarToken.SLOT_MILLIS) in candidates)
        assertTrue(RadarToken.at(secret, now + RadarToken.SLOT_MILLIS) in candidates)
    }

    @Test
    fun bandsGoUpAtTheEntryAndDownAtTheExit() {
        assertEquals(RadarBand.NONE, ProximityRules.bandFor(-95.0, RadarBand.NONE))
        assertEquals(RadarBand.WARM, ProximityRules.bandFor(-84.0, RadarBand.NONE))
        assertEquals(RadarBand.HOT, ProximityRules.bandFor(-69.0, RadarBand.WARM))
        assertEquals(RadarBand.BURNING, ProximityRules.bandFor(-59.0, RadarBand.NONE))
        // Hysteresis: just below the entry threshold the band holds, below the exit it drops.
        assertEquals(RadarBand.HOT, ProximityRules.bandFor(-74.0, RadarBand.HOT))
        assertEquals(RadarBand.WARM, ProximityRules.bandFor(-77.0, RadarBand.HOT))
        assertEquals(RadarBand.HOT, ProximityRules.bandFor(-72.0, RadarBand.BURNING))
        assertEquals(RadarBand.NONE, ProximityRules.bandFor(-91.0, RadarBand.WARM))
    }

    @Test
    fun theSmootherForgetsAnOldSignal() {
        val smoother = RadarSmoother()
        var now = 1_700_000_000_000L
        smoother.add(-58, now)
        assertEquals(RadarBand.BURNING, smoother.bandAt(now))
        assertTrue(smoother.wasBurningWithin(now + 5_000, windowMillis = 30_000))
        now += 3_000
        smoother.add(-70, now)
        // Smoothed (-62.8 dBm): still above the exit of «burning».
        assertEquals(RadarBand.BURNING, smoother.bandAt(now))
        repeat(6) {
            now += 2_000
            smoother.add(-80, now)
        }
        assertEquals(RadarBand.WARM, smoother.bandAt(now))
        assertEquals(RadarBand.NONE, smoother.bandAt(now + ProximityRules.SIGNAL_TTL_MILLIS + 1))
        assertFalse(smoother.wasBurningWithin(now + 60_000, windowMillis = 30_000))
    }

    @Test
    fun aReadingOutOfOrderIsIgnored() {
        val smoother = RadarSmoother()
        smoother.add(-90, 2_000)
        smoother.add(-50, 1_000)
        assertEquals(-90.0, smoother.levelDbm)
    }

    @Test
    fun aClaimNeedsTheBurningToLast() {
        val smoother = RadarSmoother(dwellMillis = 3_000)
        var now = 1_700_000_000_000L
        smoother.add(-55, now)
        assertEquals(RadarBand.BURNING, smoother.bandAt(now))
        assertFalse(smoother.wasBurningWithin(now, windowMillis = 30_000), "one spike is not a meeting")
        now += 2_000
        smoother.add(-55, now)
        assertFalse(smoother.wasBurningWithin(now, windowMillis = 30_000))
        now += 1_000
        smoother.add(-56, now)
        assertTrue(smoother.wasBurningWithin(now, windowMillis = 30_000), "burning for three seconds")
        assertEquals(now - 3_000, smoother.burningSinceMillis)

        // A break resets the count (the smoothed signal needs a few readings to come back up); after a silence
        // longer than the signal's life the count starts again from the first reading.
        now += 1_000
        smoother.add(-90, now)
        assertEquals(null, smoother.burningSinceMillis)
        now += ProximityRules.SIGNAL_TTL_MILLIS + 1
        smoother.add(-55, now)
        assertEquals(now, smoother.burningSinceMillis)
        assertTrue(smoother.wasBurningWithin(now, windowMillis = 30_000), "the earlier meeting still counts")
        assertFalse(smoother.wasBurningWithin(now + 40_000, windowMillis = 30_000))
    }

    @Test
    fun theHeartbeatFollowsTheBand() {
        assertEquals(null, HeartbeatRules.periodMillis(RadarBand.NONE))
        assertEquals(HeartbeatRules.WARM_PERIOD_MILLIS, HeartbeatRules.periodMillis(RadarBand.WARM))
        assertTrue(HeartbeatRules.HOT_PERIOD_MILLIS < HeartbeatRules.WARM_PERIOD_MILLIS)
        assertTrue(HeartbeatRules.BURNING_PERIOD_MILLIS < HeartbeatRules.HOT_PERIOD_MILLIS)
    }

    @Test
    fun aTokenFitsAnIBeaconsMajorAndMinor() {
        val token = RadarToken.at(secret, 1_700_000_000_000L)
        val (major, minor) = RadarToken.toMajorMinor(token)
        assertTrue(major in 0..0xFFFF && minor in 0..0xFFFF)
        assertEquals(token, RadarToken.fromMajorMinor(major, minor))
        assertEquals("00010002", RadarToken.fromMajorMinor(1, 2))
        assertEquals(0xFFFF to 0, RadarToken.toMajorMinor("ffff0000"))
    }
}
