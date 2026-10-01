package app.hovanki.radar.host

import app.hovanki.radar.AdPart
import app.hovanki.radar.AirFrame
import app.hovanki.radar.AirSecond
import app.hovanki.radar.Availability
import app.hovanki.radar.Decoded
import app.hovanki.radar.HostProximityRadio
import app.hovanki.radar.RadarCaps
import app.hovanki.radar.RadarCatalog
import app.hovanki.radar.RadarChannel
import app.hovanki.radar.RadarRole
import app.hovanki.radar.RadarService
import app.hovanki.radar.RadarTrace
import app.hovanki.radar.RadioApi
import app.hovanki.radar.RadioSighting
import app.hovanki.radar.ScanInterest
import app.hovanki.radar.SightingVia
import app.hovanki.radar.TechniqueStatus
import app.hovanki.radar.channel.ibeacon.IBeaconRegionChannel
import app.hovanki.radar.channel.overflow.OverflowChannel
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Carry
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.Platform
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The simulator's air with the OS's rules (docs/radar-run.md §3): who hears which channel. */
class SimulatedAirTest {
    private val air = SimulatedAir(noiseDb = 0.0, autoTick = false)
    private var now = 1_790_000_000_000L
    private val game = RadarCatalog.game

    @AfterTest
    fun close() = air.close()

    @Test
    fun anAndroidHiderOnTheFirstLayoutIsHeardByNobody() = air {
        val trace = Trace()
        val hider = host("hider", Platform.ANDROID)
        run(hider, listOf(FirstLayout), TOKEN, RadarRole.HIDER, trace)
        val android = run(host("android", Platform.ANDROID), game + FirstLayout)
        val iphone = run(host("iphone", Platform.IOS, meters = 3.0), game + FirstLayout)
        ticks(3)
        assertEquals(emptyList(), android + iphone)
        // Its host keeps what fits (the service UUID alone) and says what it left out.
        assertEquals(listOf(RadarService.UUID), hider.onAir()?.serviceUuids)
        assertTrue(trace.adverts.any { it.startsWith("dropped first over 31 bytes: adv 40") }, "${trace.adverts}")
    }

    @Test
    fun anAndroidHiderOnTheScanResponseIsHeardByEverybody() = air {
        run(host("hider", Platform.ANDROID), game, TOKEN, RadarRole.HIDER)
        val trace = Trace()
        val listeners = listOf(
            run(host("android", Platform.ANDROID), game, trace = trace),
            run(host("iphone", Platform.IOS, meters = 3.0), game),
            run(host("locked", Platform.IOS, meters = 4.0, carry = Carry.IN_POCKET), game),
            run(host("mac", Platform.OTHER, meters = 6.0), game),
        )
        ticks(2)
        for (heard in listeners) {
            assertEquals(2, heard.size, "$heard")
            assertTrue(heard.all { it.token == TOKEN && it.via == SightingVia.SERVICE_DATA }, "$heard")
            assertTrue(heard.all { it.tech == "ble.service_data.scan_response" }, "$heard")
        }
        assertEquals(listOf(RadioApi.ANDROID_LE), listeners[0].map { it.api }.distinct())
        assertEquals(listOf(RadioApi.COREBLUETOOTH), listeners[1].map { it.api }.distinct())
        assertEquals(2, trace.frames.size)
        assertEquals("ble.service_data.scan_response", trace.frames.first().second.single().first)
    }

    @Test
    fun anIphoneHiderOnTheNameIsHeardOnTheScreenOnly() = air {
        val carry = MutableStateFlow(Carry.IN_POCKET)
        run(host("hider", Platform.IOS, carry = carry), game, TOKEN, RadarRole.HIDER)
        val android = run(host("android", Platform.ANDROID), game)
        val iphone = run(host("iphone", Platform.IOS, meters = 3.0), game)
        ticks(2)
        assertEquals(emptyList(), android + iphone)

        carry.value = Carry.IN_HAND
        ticks(1)
        assertEquals(listOf(TOKEN to SightingVia.NAME), android.map { it.token to it.via })
        assertEquals(listOf(TOKEN to SightingVia.NAME), iphone.map { it.token to it.via })
    }

    @Test
    fun anIphoneHiderInTheBackgroundOnTheOverflowIsAMaskOrListedUuids() = air {
        run(host("hider", Platform.IOS, carry = Carry.IN_POCKET), listOf(OverflowChannel), TOKEN, RadarRole.HIDER)
        val android = run(host("android", Platform.ANDROID), listOf(OverflowChannel))
        val iphone = run(host("iphone", Platform.IOS, meters = 3.0), listOf(OverflowChannel))
        val locked = run(host("locked", Platform.IOS, meters = 4.0, carry = Carry.IN_POCKET), listOf(OverflowChannel))
        ticks(1)
        assertEquals(listOf(TOKEN to SightingVia.OVERFLOW_RAW), android.map { it.token to it.via })
        assertEquals(listOf(TOKEN to SightingVia.OVERFLOW_UUIDS), iphone.map { it.token to it.via })
        assertEquals(emptyList(), locked)
    }

    @Test
    fun aSeekersBeaconReachesALockedIphoneThroughRangingOnly() = air {
        run(host("seeker", Platform.ANDROID), game, TOKEN, RadarRole.SEEKER)
        val locked = run(host("locked", Platform.IOS, carry = Carry.IN_POCKET), game)
        val android = run(host("android", Platform.ANDROID, meters = 3.0), game)
        ticks(2)
        assertEquals(2, locked.size)
        assertTrue(locked.all { it.api == RadioApi.CORELOCATION_RANGING && it.via == SightingVia.IBEACON }, "$locked")
        assertTrue(locked.all { it.peer == null && it.tech == "ble.ibeacon" }, "$locked")
        assertEquals(listOf(RadioApi.ANDROID_LE), android.map { it.api }.distinct())
    }

    @Test
    fun anIphoneSeekerInAPocketSendsNoBeacon() = air {
        run(host("seeker", Platform.IOS, carry = Carry.IN_POCKET), game, TOKEN, RadarRole.SEEKER)
        val android = run(host("android", Platform.ANDROID), game)
        val iphone = run(host("iphone", Platform.IOS, meters = 3.0), game)
        ticks(2)
        assertEquals(emptyList(), android + iphone)
    }

    @Test
    fun bluetoothOffSendsAndHearsNothing() = air {
        val hider = host("hider", Platform.ANDROID, bluetooth = BluetoothState.OFF)
        run(hider, game, TOKEN, RadarRole.HIDER)
        val off = host("off", Platform.ANDROID, meters = 2.0, bluetooth = BluetoothState.OFF)
        val deaf = run(off, game)
        val android = run(host("android", Platform.ANDROID, meters = 3.0), game)
        ticks(2)
        assertEquals(emptyList(), android + deaf)
        assertNull(hider.onAir())

        hider.phone.bluetooth.value = BluetoothState.ON
        off.phone.bluetooth.value = BluetoothState.ON
        assertEquals(BluetoothState.ON, HostProximityRadio(off).state.value)
        ticks(1)
        assertEquals(1, android.size)
        assertEquals(1, deaf.size)
    }

    @Test
    fun theSignalFallsWithTheDistanceAndThePocket() = air {
        run(host("hider", Platform.ANDROID), game, TOKEN, RadarRole.HIDER)
        val near = run(host("near", Platform.ANDROID, meters = 5.0), game)
        val pocket = run(host("pocket", Platform.ANDROID, meters = 5.0, carry = Carry.IN_POCKET), game)
        val far = run(host("far", Platform.ANDROID, meters = 500.0), game)
        ticks(1)
        assertEquals(listOf(-73), near.map { it.rssi })
        assertEquals(listOf(-85), pocket.map { it.rssi })
        assertEquals(emptyList(), far)
    }

    @Test
    fun anIphoneKeepsItsAdvertisementInTheBackgroundUntilTheScreen() = air {
        val trace = Trace()
        val carry = MutableStateFlow(Carry.IN_HAND)
        val token = MutableStateFlow<String?>(TOKEN)
        val hider = host("hider", Platform.IOS, carry = carry)
        launch { HostProximityRadio(hider, trace = trace).run(token).collect {} }
        val android = run(host("android", Platform.ANDROID), game)
        ticks(1)
        carry.value = Carry.IN_POCKET
        token.value = OTHER
        ticks(1)
        assertEquals(TOKEN, hider.advertisedToken)
        assertTrue(trace.adverts.any { it.startsWith("skipped_background") }, "${trace.adverts}")

        carry.value = Carry.IN_HAND
        ticks(1)
        assertEquals(OTHER, hider.advertisedToken)
        assertEquals(listOf(TOKEN, OTHER), android.map { it.token })
    }

    @Test
    fun regionMonitoringEntersAndExits() = air {
        val seekerAt = MutableStateFlow(0.0)
        run(host("seeker", Platform.ANDROID, at = { seekerAt.value }), game, TOKEN, RadarRole.SEEKER)
        val trace = Trace()
        run(
            host("iphone", Platform.IOS, meters = 5.0, carry = Carry.IN_POCKET),
            listOf(IBeaconRegionChannel),
            trace = trace,
        )
        ticks(2)
        assertEquals(listOf("start corelocation_region", "region_enter corelocation_region"), trace.scans.drop(1))

        seekerAt.value = 2_000.0
        ticks(1)
        now += SimulatedAir.REGION_EXIT_MILLIS
        ticks(1)
        assertEquals("region_exit corelocation_region", trace.scans.last())
    }

    private fun air(test: suspend CoroutineScope.() -> Unit) = runBlocking {
        try {
            test()
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    private fun host(
        id: String,
        platform: Platform,
        meters: Double = 0.0,
        carry: Carry = Carry.IN_HAND,
        bluetooth: BluetoothState = BluetoothState.ON,
    ): JvmAirHost = host(id, platform, MutableStateFlow(carry), bluetooth) { meters }

    private fun host(
        id: String,
        platform: Platform,
        carry: MutableStateFlow<Carry>,
        bluetooth: BluetoothState = BluetoothState.ON,
        at: () -> Double = { 0.0 },
    ): JvmAirHost = JvmAirHost(
        air,
        SimPhone(id, platform, { east(at()) }, carry = { carry.value }, clock = { now }, bluetooth = bluetooth),
    )

    private fun host(id: String, platform: Platform, at: () -> Double): JvmAirHost =
        host(id, platform, MutableStateFlow(Carry.IN_HAND), at = at)

    /** Collects the radio of [host] running [channels]: what it hears. */
    private suspend fun CoroutineScope.run(
        host: JvmAirHost,
        channels: List<RadarChannel>,
        token: String? = null,
        role: RadarRole = RadarRole.HIDER,
        trace: RadarTrace = RadarTrace.None,
    ): MutableList<RadioSighting> {
        val heard = Collections.synchronizedList(mutableListOf<RadioSighting>())
        val radio = HostProximityRadio(host, { channels }, trace)
        launch { radio.run(MutableStateFlow(token), asSeeker = role == RadarRole.SEEKER).collect { heard += it } }
        settle()
        return heard
    }

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

    /** The Android hider's first layout: the service UUID and its data in one packet, 40 bytes. */
    private object FirstLayout : RadarChannel {
        override val id = "first"
        override val status = TechniqueStatus.LAB

        override fun available(caps: RadarCaps) = Availability.Available

        override fun advertise(token: String, role: RadarRole) = listOf(
            AdPart.ServiceUuid(RadarService.UUID),
            AdPart.ServiceData(RadarService.UUID, token.chunked(2).map { it.toInt(16).toByte() }.toByteArray()),
        )

        override fun interests() = listOf(ScanInterest.Service(RadarService.UUID))

        override fun decode(frame: AirFrame): List<Decoded> = frame.serviceData(RadarService.UUID)
            ?.let { data -> listOf(Decoded(data.joinToString("") { "%02x".format(it) }, SightingVia.SERVICE_DATA)) }
            .orEmpty()
    }

    private class Trace : RadarTrace {
        val adverts: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val scans: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val frames: MutableList<Pair<AirFrame, List<Pair<String, Decoded>>>> =
            Collections.synchronizedList(mutableListOf())
        val seconds: MutableList<AirSecond> = Collections.synchronizedList(mutableListOf())

        override fun advertise(action: String, tech: String, token: String?, layout: String?, error: String?) {
            adverts += "$action $tech $error"
        }

        override fun scan(action: String, api: RadioApi, filters: String?, error: String?) {
            scans += "$action ${api.key}"
        }

        override fun frame(frame: AirFrame, decoded: List<Pair<String, Decoded>>) {
            frames += frame to decoded
        }

        override fun air(second: AirSecond) {
            seconds += second
        }
    }

    private companion object {
        const val TOKEN = "0a1b2c3d"
        const val OTHER = "4e5f6071"
        const val LAT = 50.45
        const val LON = 30.52
        const val METERS_PER_DEGREE = 111_320.0
    }
}
