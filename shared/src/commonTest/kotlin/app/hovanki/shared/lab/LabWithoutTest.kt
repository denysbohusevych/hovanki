package app.hovanki.shared.lab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Без X» on made-up logs: the pairs' bands with every channel against the bands without one. */
class LabWithoutTest {
    private val a = RadarTestLog("A", "aaaa0001")
    private val b = RadarTestLog("B", "bbbb0002")

    private fun RadarTestLog.heard(from: RadarTestLog, tech: String, rssi: Int, start: Long, end: Long) {
        var t = start
        while (t < end) {
            event(
                t,
                "rx",
                "token" to from.token,
                "rssi" to rssi,
                "api" to "corebluetooth",
                "via" to "name",
                "tech" to tech,
            )
            t += 200
        }
    }

    private val seconds = 1_000L..40_000L step 1_000

    @Test
    fun theOnlyChannelIsMissedWithoutIt() {
        // A hears B by its name for 29 s, and by the iBeacon only the first 9.
        a.heard(b, NAME, -65, 1_000, 30_000)
        a.heard(b, IBEACON, -65, 1_000, 10_000)
        val merge = radarMerge(a, b)
        val results = WithoutChannel.all(merge.events, merge::sender, listOf(IBEACON, NAME), seconds)
            .associateBy { it.tech }

        val withoutName = results.getValue(NAME)
        assertTrue(withoutName.same < withoutName.seconds, "$withoutName")
        assertTrue(withoutName.onlyChannel >= 15, "the name alone from ~20 s to 40 s: $withoutName")
        assertEquals(39, withoutName.seconds, "every second with a band one way or the other: 1 s to 39 s")

        val withoutBeacon = results.getValue(IBEACON)
        assertEquals(withoutBeacon.seconds, withoutBeacon.same, "the name covers every second: $withoutBeacon")
        assertEquals(0, withoutBeacon.onlyChannel)
    }

    @Test
    fun oldLogsNameTheChannelByApiAndVia() {
        // Before the channels an `rx` had no `tech`: its channel is `api/via`.
        a.hears(b, -65, 1_000, 10_000)
        val merge = radarMerge(a, b)
        val result = WithoutChannel.all(merge.events, merge::sender, listOf("corebluetooth/name"), seconds).single()
        assertEquals(0, result.same, "without its only channel the pair heard nothing")
        assertEquals(result.seconds, result.onlyChannel)
        assertTrue(result.seconds > 0)
    }

    @Test
    fun nothingHeardNothingCounted() {
        val merge = radarMerge(a, b)
        assertEquals(
            listOf(WithoutResult(NAME, 0, 0, 0)),
            WithoutChannel.all(merge.events, merge::sender, listOf(NAME), seconds),
        )
    }

    private companion object {
        const val NAME = "ble.name"
        const val IBEACON = "ble.ibeacon"
    }
}
