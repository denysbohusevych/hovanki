package app.hovanki.radar.channel.servicedata

import app.hovanki.radar.AdBudget
import app.hovanki.radar.AdPart
import app.hovanki.radar.Decoded
import app.hovanki.radar.RadarRole
import app.hovanki.radar.RadarService
import app.hovanki.radar.ScanInterest
import app.hovanki.radar.SightingVia
import app.hovanki.radar.TOKEN
import app.hovanki.radar.frameOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceDataChannelTest {
    private val heard = listOf(Decoded(TOKEN, SightingVia.SERVICE_DATA))

    @Test
    fun scanResponseCarriesTheUuidInTheAdvertisementAndTheTokenInTheResponse() {
        val parts = ServiceDataChannel.ScanResponse.advertise(TOKEN, RadarRole.HIDER)
        assertEquals(AdPart.ServiceUuid(RadarService.UUID), parts[0])
        val data = parts[1] as AdPart.ServiceData
        assertTrue(data.inScanResponse)
        assertEquals(TOKEN, data.data.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
        assertEquals(heard, ServiceDataChannel.ScanResponse.decode(frameOf(parts)))
        assertEquals(
            listOf<ScanInterest>(ScanInterest.Service(RadarService.UUID)),
            ServiceDataChannel.ScanResponse.interests(),
        )
    }

    @Test
    fun bareAndScanResponseTellTheirFramesApart() {
        val bare = frameOf(ServiceDataChannel.Bare.advertise(TOKEN, RadarRole.HIDER))
        val response = frameOf(ServiceDataChannel.ScanResponse.advertise(TOKEN, RadarRole.HIDER))
        assertEquals(heard, ServiceDataChannel.Bare.decode(bare))
        assertEquals(emptyList(), ServiceDataChannel.ScanResponse.decode(bare))
        assertEquals(emptyList(), ServiceDataChannel.Bare.decode(response))
    }

    @Test
    fun mfrCarriesTheServiceAndTheTokenUnderTheTestCompanyId() {
        val parts = ServiceDataChannel.Mfr.advertise(TOKEN, RadarRole.HIDER)
        val data = parts.single() as AdPart.ManufacturerData
        assertEquals(ServiceDataChannel.HOVANKI_COMPANY_ID, data.companyId)
        assertEquals(20, data.data.size)
        assertEquals(heard, ServiceDataChannel.Mfr.decode(frameOf(parts)))
        assertEquals(24, AdBudget.bytes(parts, scanResponse = false))
    }

    @Test
    fun theKeyOfTheServiceDataMayComeInAnyCase() {
        val frame = frameOf(ServiceDataChannel.Bare.advertise(TOKEN, RadarRole.HIDER))
        val lower = frame.copy(serviceData = frame.serviceData.mapKeys { it.key.lowercase() })
        assertEquals(heard, ServiceDataChannel.Bare.decode(lower))
    }

    @Test
    fun aSeekerSendsNothingAndOtherFramesReadAsNothing() {
        for (channel in listOf(ServiceDataChannel.ScanResponse, ServiceDataChannel.Bare, ServiceDataChannel.Mfr)) {
            assertEquals(emptyList(), channel.advertise(TOKEN, RadarRole.SEEKER))
            assertEquals(emptyList(), channel.advertise("not-a-token", RadarRole.HIDER))
        }
        // Another service's data, a token of the wrong length, another company's prefix.
        val other = frameOf(listOf(AdPart.ServiceData("FEAA", byteArrayOf(1, 2, 3, 4))))
        assertEquals(emptyList(), ServiceDataChannel.Bare.decode(other))
        val short = frameOf(listOf(AdPart.ServiceData(RadarService.UUID, byteArrayOf(1, 2, 3))))
        assertEquals(emptyList(), ServiceDataChannel.Bare.decode(short))
        val foreign = frameOf(listOf(AdPart.ManufacturerData(ServiceDataChannel.HOVANKI_COMPANY_ID, ByteArray(20))))
        assertEquals(emptyList(), ServiceDataChannel.Mfr.decode(foreign))
    }
}
