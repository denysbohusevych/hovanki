package app.hovanki.radar.lab

import app.hovanki.radar.RadioApi
import app.hovanki.radar.host.JvmAirHost
import app.hovanki.radar.host.SimPhone
import app.hovanki.radar.host.SimulatedAir
import app.hovanki.shared.protocol.Carry
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.rules.OverflowArea
import app.hovanki.shared.rules.OverflowCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.Collections
import kotlin.math.cos
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The lab's probe and «listen to everything» on the simulator's air, with the OS's rules (docs/radio-lab.md §5). */
class HostLabAirSimulatedTest {
    private val air = SimulatedAir(noiseDb = 0.0, autoTick = false)
    private var now = 1_790_000_000_000L

    @AfterTest
    fun close() = air.close()

    @Test
    fun aLockedIphonesProbeIsAMaskRawOnAndroidAndListedOnAnIphone() = runBlocking {
        try {
            val carry = MutableStateFlow(Carry.IN_HAND)
            val prober = HostLabAir(host("prober", Platform.IOS, carry, meters = 0.0))
            val bits = MutableStateFlow(OverflowCode.encode(TOKEN))
            val events = Collections.synchronizedList(mutableListOf<ProbeEvent>())
            launch { prober.probe(bits).collect { events += it } }
            val android = listen(HostLabAir(host("android", Platform.ANDROID, MutableStateFlow(Carry.IN_HAND), 2.0)))
            val iphone = listen(HostLabAir(host("iphone", Platform.IOS, MutableStateFlow(Carry.IN_HAND), 3.0)))
            ticks(1)
            assertEquals(listOf("start"), events.map { it.action })

            carry.value = Carry.IN_POCKET
            android.clear()
            iphone.clear()
            ticks(1)
            val raw = android.filterIsInstance<LabFrame.Mask>().single()
            assertTrue(raw.bits.containsAll(bits.value) && raw.hex != null, "$raw")
            assertEquals(RadioApi.ANDROID_LE, raw.api)
            val listed = iphone.filterIsInstance<LabFrame.Mask>().single()
            assertTrue(listed.bits.containsAll(bits.value) && listed.hex == null, "$listed")
            assertEquals(listOf(TOKEN), OverflowCode.decode(raw.bits))

            // Locked, a new set of bits waits for the screen: iOS can't start an advertisement in the background.
            bits.value = setOf(3)
            ticks(1)
            assertEquals(listOf("start", "skipped_background"), events.map { it.action })
            carry.value = Carry.IN_HAND
            ticks(1)
            assertEquals(listOf("start", "skipped_background", "stop", "start"), events.map { it.action })
            assertTrue(events.last().layout.orEmpty().startsWith("adv"), "${events.last()}")
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @Test
    fun androidCantProbe() {
        val android = HostLabAir(host("android", Platform.ANDROID, MutableStateFlow(Carry.IN_HAND), 0.0))
        assertTrue(android.canListen)
        assertTrue(!android.canProbe)
        assertTrue(HostLabAir(host("iphone", Platform.IOS, MutableStateFlow(Carry.IN_HAND), 0.0)).canProbe)
    }

    private suspend fun CoroutineScope.listen(lab: HostLabAir): MutableList<LabFrame> {
        val heard = Collections.synchronizedList(mutableListOf<LabFrame>())
        launch { lab.listen().collect { heard += it } }
        settle()
        return heard
    }

    private fun host(id: String, platform: Platform, carry: MutableStateFlow<Carry>, meters: Double) = JvmAirHost(
        air,
        SimPhone(id, platform, { east(meters) }, carry = { carry.value }, clock = { now }),
    )

    private suspend fun ticks(count: Int) {
        settle()
        repeat(count) {
            air.tick()
            now += SimulatedAir.TICK_MILLIS
            settle()
        }
    }

    private suspend fun settle() = repeat(10) { yield() }

    private fun east(meters: Double) = GeoPoint(LAT, LON + meters / (METERS_PER_DEGREE * cos(Math.toRadians(LAT))))

    private companion object {
        const val TOKEN = "0a1b2c3d"
        const val LAT = 50.45
        const val LON = 30.52
        const val METERS_PER_DEGREE = 111_320.0

        init {
            // The probe's bits are the table's: none of them the Apple Watch's.
            check(OverflowArea.APPLE_WATCH_BIT !in OverflowCode.encode(TOKEN))
        }
    }
}
