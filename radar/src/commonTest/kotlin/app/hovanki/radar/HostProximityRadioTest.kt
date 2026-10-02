package app.hovanki.radar

import app.hovanki.radar.channel.overflow.OverflowChannel
import app.hovanki.radar.channel.overflow.OverflowParts
import app.hovanki.radar.channel.servicedata.ServiceDataChannel
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Platform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HostProximityRadioTest {
    private class FakeHost(frames: List<AirFrame>) : AirHost {
        val mutableCaps = MutableStateFlow(RadarCaps(Platform.ANDROID, BluetoothState.OFF))
        override val caps: StateFlow<RadarCaps> = mutableCaps
        private val heard = frames
        var ran: Triple<List<RadarChannel>, RadarRole, RadarTrace>? = null

        override fun run(
            channels: List<RadarChannel>,
            token: StateFlow<String?>,
            role: RadarRole,
            trace: RadarTrace,
        ): Flow<AirFrame> {
            ran = Triple(channels, role, trace)
            return flowOf(*heard.toTypedArray())
        }
    }

    @Test
    fun framesTheChannelsDecodeAreSightingsWithTheirChannel() = runTest {
        val hider = frameOf(ServiceDataChannel.ScanResponse.advertise(TOKEN, RadarRole.HIDER), rssi = -71)
        val noise = frameOf(listOf(AdPart.ManufacturerData(0x0006, byteArrayOf(1, 2, 3))))
        val badName = frameOf(listOf(AdPart.ServiceUuid(RadarService.UUID), AdPart.LocalName("hvXYZ")))
        val host = FakeHost(listOf(hider, noise, badName))
        val trace = RecordingTrace()
        val radio = HostProximityRadio(host, trace = trace)

        val sightings = radio.run(MutableStateFlow(TOKEN), asSeeker = true).toList()

        assertEquals(
            listOf(
                RadioSighting(
                    TOKEN,
                    -71,
                    hider.atMillis,
                    RadioApi.ANDROID_LE,
                    SightingVia.SERVICE_DATA,
                    "peer",
                    "ble.service_data.scan_response",
                ),
            ),
            sightings,
        )
        assertEquals(Triple(RadarCatalog.game, RadarRole.SEEKER, trace as RadarTrace), host.ran)
    }

    @Test
    fun theChannelsFollowThePlatformAndAnOverflowGivesItsFirstCandidate() = runTest {
        val bits = app.hovanki.shared.rules.OverflowCode.encode(TOKEN)
        val mask = AirFrame(
            1L,
            -80,
            RadioApi.COREBLUETOOTH,
            "peer",
            overflowUuids = bits.map(app.hovanki.shared.rules.OverflowArea::uuid),
        )
        val host = FakeHost(listOf(mask))
        host.mutableCaps.value = RadarCaps(Platform.IOS, BluetoothState.ON)
        val radio = HostProximityRadio(host, channels = {
            if (it ==
                Platform.IOS
            ) {
                listOf(OverflowChannel)
            } else {
                emptyList()
            }
        })

        val sighting = radio.run(MutableStateFlow(null)).toList().single()

        assertEquals(TOKEN to SightingVia.OVERFLOW_UUIDS, sighting.token to sighting.via)
        assertEquals("ble.overflow", sighting.tech)
        assertEquals(RadarRole.HIDER, host.ran?.second)
    }

    @Test
    fun theStateIsTheHostsBluetooth() = runTest {
        val host = FakeHost(emptyList())
        val radio = HostProximityRadio(host)
        assertEquals(BluetoothState.OFF, radio.state.value)
        host.mutableCaps.value = RadarCaps(Platform.ANDROID, BluetoothState.ON)
        assertEquals(BluetoothState.ON, radio.state.value)
        assertEquals(BluetoothState.ON, radio.state.first())
    }

    @Test
    fun aMixGivesSightingsOfItsGameChannelsOnlyAndRunsTheShadowToo() = runTest {
        val bare = frameOf(ServiceDataChannel.Bare.advertise(TOKEN, RadarRole.HIDER), rssi = -70)
        val bits = app.hovanki.shared.rules.OverflowCode.encode("1a2b3c4d")
        val mask = frameOf(OverflowParts(bits))
        val host = FakeHost(listOf(bare, mask))
        val radio = HostProximityRadio(host)
        val mix = RadarCatalog.field(playerNumber = 0)

        val sightings = radio.run(MutableStateFlow(TOKEN), asSeeker = false, mix).toList()

        assertEquals(listOf("ble.service_data.bare"), sightings.map { it.tech }, "the shadow's mask is no sighting")
        assertEquals(mix.all, host.ran?.first)
    }
}
