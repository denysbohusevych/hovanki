package app.hovanki.radar.channel.ibeacon

import app.hovanki.radar.AdPart
import app.hovanki.radar.AirFrame
import app.hovanki.radar.BleUuid
import app.hovanki.radar.Decoded
import app.hovanki.radar.RadarRole
import app.hovanki.radar.RadarService
import app.hovanki.radar.RadioApi
import app.hovanki.radar.RegionEvent
import app.hovanki.radar.ScanInterest
import app.hovanki.radar.SightingVia
import app.hovanki.radar.TOKEN
import app.hovanki.radar.frameOf
import app.hovanki.shared.rules.IBeaconFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IBeaconChannelTest {
    private val heard = listOf(Decoded(TOKEN, SightingVia.IBEACON))

    @Test
    fun theSeekerIsABeaconOfTheGameWithTheTokenAsMajorAndMinor() {
        val beacon = IBeaconChannel.advertise(TOKEN, RadarRole.SEEKER).single() as AdPart.IBeacon
        assertEquals(AdPart.IBeacon(RadarService.UUID, 0x0a1b, 0x2c3d, -59), beacon)
        assertEquals(emptyList(), IBeaconChannel.advertise(TOKEN, RadarRole.HIDER))
    }

    @Test
    fun readRawFromApplesDataAndRangedByCoreLocation() {
        val raw = frameOf(IBeaconChannel.advertise(TOKEN, RadarRole.SEEKER))
        assertEquals(heard, IBeaconChannel.decode(raw))
        val ranged = AirFrame(
            1L,
            -70,
            RadioApi.CORELOCATION_RANGING,
            null,
            iBeacon = IBeaconFrame(BleUuid.hex(RadarService.UUID), 0x0a1b, 0x2c3d),
        )
        assertEquals(heard, IBeaconChannel.decode(ranged))
        assertTrue(ScanInterest.BeaconRanging(RadarService.UUID) in IBeaconChannel.interests())
    }

    @Test
    fun anotherBeaconReadsAsNothing() {
        val other = frameOf(listOf(AdPart.IBeacon("E2C56DB5-DFFB-48D2-B060-D0F5A71096E0", 1, 2, -59)))
        assertEquals(emptyList(), IBeaconChannel.decode(other))
        val ranged =
            AirFrame(1L, -70, RadioApi.CORELOCATION_RANGING, null, iBeacon = IBeaconFrame("00".repeat(16), 1, 2))
        assertEquals(emptyList(), IBeaconChannel.decode(ranged))
    }

    @Test
    fun theRegionSendsNothingAndReadsNothing() {
        assertEquals(emptyList(), IBeaconRegionChannel.advertise(TOKEN, RadarRole.SEEKER))
        assertEquals(
            listOf<ScanInterest>(ScanInterest.BeaconRegion(RadarService.UUID)),
            IBeaconRegionChannel.interests(),
        )
        val enter = AirFrame(1L, 0, RadioApi.CORELOCATION_REGION, null, regionEvent = RegionEvent.ENTER)
        assertEquals(emptyList(), IBeaconRegionChannel.decode(enter))
        assertEquals(
            emptyList(),
            IBeaconRegionChannel.decode(frameOf(IBeaconChannel.advertise(TOKEN, RadarRole.SEEKER))),
        )
    }
}
