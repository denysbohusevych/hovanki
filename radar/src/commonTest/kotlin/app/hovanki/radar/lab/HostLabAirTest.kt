package app.hovanki.radar.lab

import app.hovanki.radar.AdPart
import app.hovanki.radar.AdPlan
import app.hovanki.radar.AirFrame
import app.hovanki.radar.AirHost
import app.hovanki.radar.HostProximityRadio
import app.hovanki.radar.RadarCaps
import app.hovanki.radar.RadarCatalog
import app.hovanki.radar.RadarChannel
import app.hovanki.radar.RadarRole
import app.hovanki.radar.RadarService
import app.hovanki.radar.RadarTrace
import app.hovanki.radar.RadioApi
import app.hovanki.radar.RecordingTrace
import app.hovanki.radar.SightingVia
import app.hovanki.radar.TOKEN
import app.hovanki.radar.channel.ibeacon.IBeaconChannel
import app.hovanki.radar.channel.name.NameChannel
import app.hovanki.radar.channel.overflow.OverflowChannel
import app.hovanki.radar.channel.overflow.OverflowParts
import app.hovanki.radar.channel.servicedata.ServiceDataChannel
import app.hovanki.radar.frameOf
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.rules.OverflowArea
import app.hovanki.shared.rules.OverflowCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The radio lab's air on a host (docs/radio-lab.md §5): what «listen to everything» hears, what the probe sends. */
class HostLabAirTest {
    /** A host that hears [frames] and advertises every token its caller wants, as a platform's host would plan it. */
    private class FakeHost(
        private val frames: List<AirFrame> = emptyList(),
        caps: RadarCaps = ANDROID,
        private val advertises: Boolean = false,
    ) : AirHost {
        override val caps = MutableStateFlow(caps)
        val runs = mutableListOf<Run>()
        val advertised = mutableListOf<List<AdPart>>()

        class Run(val channels: List<RadarChannel>, val token: StateFlow<String?>, val role: RadarRole)

        override fun run(
            channels: List<RadarChannel>,
            token: StateFlow<String?>,
            role: RadarRole,
            trace: RadarTrace,
        ): Flow<AirFrame> = flow {
            runs += Run(channels, token, role)
            frames.forEach { emit(it) }
            if (!advertises) return@flow
            token.collect { value ->
                if (value == null) return@collect
                val plan = AdPlan.of(channels, value, role, caps.value.platform)
                advertised += plan.adParts
                plan.trace(trace, "start", value)
            }
        }
    }

    @Test
    fun listeningHearsTheMasksAndTheTokensOfEveryChannel() = runTest {
        val bits = OverflowCode.encode(TOKEN)
        val raw = AirFrame(1L, -70, RadioApi.ANDROID_LE, "a", manufacturerData = mapOf(APPLE to mask(bits)))
        val listed = AirFrame(2L, -72, RadioApi.COREBLUETOOTH, "b", overflowUuids = bits.map(OverflowArea::uuid))
        val hider = frameOf(ServiceDataChannel.ScanResponse.advertise(TOKEN, RadarRole.HIDER), rssi = -60)
        val seeker = frameOf(IBeaconChannel.advertise(OTHER, RadarRole.SEEKER), rssi = -65)
        val chatter = AirFrame(3L, -80, RadioApi.ANDROID_LE, "c", manufacturerData = mapOf(APPLE to NEARBY))
        val host = FakeHost(listOf(raw, listed, hider, seeker, chatter))

        val heard = HostLabAir(host).listen().toList()

        val hex = OverflowArea.maskOf(bits).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertEquals(
            listOf(
                LabFrame.Mask(bits, hex, -70, "a", 1L, RadioApi.ANDROID_LE),
                LabFrame.Mask(bits, null, -72, "b", 2L, RadioApi.COREBLUETOOTH),
                LabFrame.Token(
                    TOKEN,
                    SightingVia.SERVICE_DATA,
                    -60,
                    "peer",
                    hider.atMillis,
                    RadioApi.ANDROID_LE,
                    "ble.service_data.scan_response",
                ),
                LabFrame.Token(OTHER, SightingVia.IBEACON, -65, "peer", seeker.atMillis, ANDROID_LE, "ble.ibeacon"),
            ),
            heard,
            "one token a frame (the bare channel reads the hider's too); the chatter is the tally's",
        )
        val run = host.runs.single()
        assertEquals(RadarCatalog.channels + RawAppleListener, run.channels, "every channel, and every Apple frame")
        assertNull(run.token.value, "listening sends nothing")
        assertTrue(host.advertised.isEmpty())
    }

    @Test
    fun theProbeAdvertisesTheOverflowPartsOfItsBitsAndSaysWhatItDid() = runTest {
        val host = FakeHost(caps = IPHONE, advertises = true)
        val trace = RecordingTrace()
        val air = HostLabAir(host, trace)
        val bits = MutableStateFlow(setOf(5, 3))
        val events = mutableListOf<ProbeEvent>()
        val job = launch { air.probe(bits).collect { events += it } }
        runCurrent()
        bits.value = emptySet()
        runCurrent()
        job.cancel()

        assertEquals(listOf(OverflowParts(setOf(3, 5)), OverflowParts(emptySet())), host.advertised)
        assertEquals(listOf(AdPart.ServiceUuid(RadarService.UUID)), host.advertised.last(), "no bits: the service")
        assertEquals(listOf("start", "start"), events.map { it.action })
        assertEquals("adv 50 (uuid128 50)", events.first().layout)
        assertEquals(RadarRole.HIDER, host.runs.single().role)
        assertEquals(listOf("ble.overflow"), host.runs.single().channels.map { it.id })
        assertTrue(host.runs.single().channels.single().interests().isEmpty(), "the probe hears nothing")
        assertTrue(trace.adverts.isEmpty(), "the lab writes the probe's advertisement itself, with its bits")
    }

    @Test
    fun whatTheLabCanDoIsTheHosts() {
        val android = HostLabAir(FakeHost())
        assertTrue(android.canListen)
        assertFalse(android.canProbe, "Android can't advertise an overflow area")
        val iphone = FakeHost(caps = IPHONE)
        assertTrue(HostLabAir(iphone).canProbe)
        iphone.caps.value = RadarCaps(Platform.IOS, BluetoothState.UNSUPPORTED)
        assertFalse(HostLabAir(iphone).canListen)
    }

    @Test
    fun aStepsTechniquesPickTheCatalogsChannels() = runTest {
        val host = FakeHost()
        val radio = HostProximityRadio(host)

        radio.run(MutableStateFlow(null), asSeeker = false, setOf("ble.name", "ble.overflow", "no.such")).toList()
        radio.run(MutableStateFlow(null), asSeeker = true, setOf("no.such")).toList()
        radio.run(MutableStateFlow(null), asSeeker = false, emptySet()).toList()

        assertEquals(
            listOf(listOf(NameChannel, OverflowChannel), RadarCatalog.game, RadarCatalog.game),
            host.runs.map { it.channels },
        )
        assertEquals(listOf(RadarRole.HIDER, RadarRole.SEEKER, RadarRole.HIDER), host.runs.map { it.role })
    }

    private fun mask(bits: Set<Int>): ByteArray = byteArrayOf(0x01) + OverflowArea.maskOf(bits)

    private companion object {
        const val APPLE = RadarService.APPLE_COMPANY_ID
        const val OTHER = "4e5f6071"
        val ANDROID_LE = RadioApi.ANDROID_LE

        /** Apple's «Nearby» chatter: no mask, no beacon. */
        val NEARBY = byteArrayOf(0x10, 0x05, 0x01, 0x18, 0x00, 0x00, 0x00)
        val ANDROID = RadarCaps(Platform.ANDROID, BluetoothState.ON, canReadOverflow = true)
        val IPHONE = RadarCaps(Platform.IOS, BluetoothState.ON, canAdvertiseOverflow = true, canReadOverflow = true)
    }
}
