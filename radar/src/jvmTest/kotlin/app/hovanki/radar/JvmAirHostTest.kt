package app.hovanki.radar

import app.hovanki.shared.protocol.BluetoothState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The simulated phones' Bluetooth (ADR 0017 §2.2): the game's channels reach the game, the shadow's only the journal,
 * and only with one; the advertisement's layout and the iBeacon region go into it as on a phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JvmAirHostTest {
    @Test
    fun anAndroidSeekerHearsAnIPhoneHiderOnTheScreenAndNotInThePocket() = runTest {
        val air = TestAir()
        var hiderOnScreen = true
        val seeker = JvmAirHost(AirPlatform.ANDROID, air, { true }, { testScheduler.currentTime }, peer = "droid")
        val hider = JvmAirHost(AirPlatform.IOS, air, { hiderOnScreen }, { testScheduler.currentTime }, peer = "a")
        val heard = CopyOnWriteArrayList<RadioSighting>()
        backgroundScope.launch { seeker.run(MutableStateFlow("aaaa0001"), asSeeker = true).collect { heard += it } }
        backgroundScope.launch { hider.run(MutableStateFlow("bbbb0002"), asSeeker = false).collect { } }
        runCurrent()

        tickAndWait(air)
        assertEquals(listOf("bbbb0002"), heard.map { it.token }.distinct())
        assertEquals("ble.name", heard.first().tech)
        assertEquals(setOf("aaaa0001"), hider.heardTokens, "the iPhone ranges the seeker's iBeacon")

        heard.clear()
        hiderOnScreen = false
        tickAndWait(air)
        assertTrue(heard.isEmpty(), "a locked iPhone's token is nobody's to read: $heard")
        assertEquals(setOf("aaaa0001"), hider.heardTokens)
    }

    @Test
    fun withAJournalAnAndroidReadsALockedIPhonesMaskInTheShadow() = runTest {
        val air = TestAir()
        val journal = Journal()
        val android = JvmAirHost(AirPlatform.ANDROID, air, { false }, { testScheduler.currentTime }, journal, "droid")
        val iphone = JvmAirHost(AirPlatform.IOS, air, { false }, { testScheduler.currentTime }, journal, "a")
        val heard = CopyOnWriteArrayList<RadioSighting>()
        backgroundScope.launch { android.run(MutableStateFlow("aaaa0001"), asSeeker = true).collect { heard += it } }
        backgroundScope.launch { iphone.run(MutableStateFlow("bbbb0002"), asSeeker = false).collect { } }
        runCurrent()
        tickAndWait(air)
        assertTrue(heard.isEmpty(), "the mask never reaches the game: $heard")
        assertTrue("shadow ble.overflow [bbbb0002] overflow_raw" in journal.lines, "${journal.lines}")
        assertTrue(journal.lines.any { it.startsWith("frame ble.overflow") }, "${journal.lines}")
        // The iPhone in the pocket still ranges the seeker, and its region was entered.
        assertTrue("region enter inside" in journal.lines, "${journal.lines}")
        assertEquals(setOf("aaaa0001"), iphone.heardTokens)

        // Thirty seconds without the seeker: the region is left.
        android.state.value = BluetoothState.OFF
        repeat(32) { tickAndWait(air, 1_000) }
        assertTrue("region exit outside" in journal.lines, "${journal.lines}")
    }

    @Test
    fun withAJournalTheAndroidHidersTakeTheLayoutsInTurn() = runTest {
        val air = TestAir()
        val journal = Journal()
        val listener = JvmAirHost(AirPlatform.ANDROID, air, { true }, { testScheduler.currentTime }, journal, "l")
        val heard = CopyOnWriteArrayList<RadioSighting>()
        backgroundScope.launch { listener.run(MutableStateFlow("aaaa0001"), asSeeker = true).collect { heard += it } }
        for (number in 0 until 3) {
            val hider =
                JvmAirHost(AirPlatform.ANDROID, air, { false }, { testScheduler.currentTime }, journal, "h$number")
            backgroundScope.launch {
                hider.run(MutableStateFlow("cccc000$number"), false, RadioOptions(playerNumber = number)).collect { }
            }
        }
        runCurrent()
        tickAndWait(air)
        val tech = heard.associate { it.token to it.tech }
        assertEquals(
            mapOf(
                "cccc0000" to "ble.service_data.scan_response",
                "cccc0001" to "ble.service_data.bare",
                "cccc0002" to "ble.service_data.mfr",
            ),
            tech,
        )
        val layouts = journal.lines.filter {
            it.startsWith("adv start hider_service_data")
        }.map { it.substringAfterLast(' ') }
        assertEquals(setOf("scan_response", "bare", "mfr"), layouts.toSet())
    }

    @Test
    fun withoutAJournalEveryAndroidHiderUsesTheScanResponse() = runTest {
        val air = TestAir()
        val hider = JvmAirHost(AirPlatform.ANDROID, air, { true }, { testScheduler.currentTime }, peer = "h")
        backgroundScope.launch {
            hider.run(MutableStateFlow("cccc0001"), false, RadioOptions(playerNumber = 1)).collect { }
        }
        runCurrent()
        val broadcast = hider.broadcast()!!
        assertEquals(listOf(GameAir.SERVICE_UUID), broadcast.main.serviceUuids)
        assertEquals("cccc0001", broadcast.scanResponse.serviceData[GameAir.SERVICE_UUID])
    }

    private fun TestScope.tickAndWait(air: TestAir, millis: Long = 300) {
        air.tick()
        advanceTimeBy(millis)
        runCurrent()
    }

    /** Every running host hears every other at -60 dBm. */
    private class TestAir : JvmAir {
        val hosts = CopyOnWriteArrayList<JvmAirHost>()

        override fun register(host: JvmAirHost) {
            hosts += host
        }

        override fun unregister(host: JvmAirHost) {
            hosts -= host
        }

        fun tick() {
            val sent = hosts.mapNotNull { host -> host.broadcast()?.let { host to it } }
            for (listener in hosts) {
                for ((sender, broadcast) in sent) if (sender !== listener) listener.receive(broadcast, -60, sender.peer)
            }
        }
    }

    private class Journal : RadioTrace {
        val lines = CopyOnWriteArrayList<String>()
        override val isListening: Boolean = true

        override fun advertise(action: String, mode: String, token: String?, error: String?, report: AdvertReport?) {
            lines += "adv $action $mode ${report?.layout}"
        }

        override fun frame(frame: HeardFrame, tech: String) {
            lines += "frame $tech"
        }

        override fun shadow(tech: String, tokens: List<String>, frame: HeardFrame, via: SightingVia) {
            lines += "shadow $tech $tokens ${via.key}"
        }

        override fun region(event: String, state: String?, error: String?) {
            lines += "region $event $state"
        }
    }
}
