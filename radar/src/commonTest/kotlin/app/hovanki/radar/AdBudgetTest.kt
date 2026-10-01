package app.hovanki.radar

import app.hovanki.radar.channel.ibeacon.IBeaconChannel
import app.hovanki.radar.channel.name.NameChannel
import app.hovanki.radar.channel.servicedata.ServiceDataChannel
import app.hovanki.shared.protocol.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdBudgetTest {
    private val token = byteArrayOf(0x0a, 0x1b, 0x2c, 0x3d)

    @Test
    fun theFirstHiderLayoutIs40BytesAndNeverFit() {
        val old = listOf(AdPart.ServiceUuid(RadarService.UUID), AdPart.ServiceData(RadarService.UUID, token))
        assertEquals(40, AdBudget.bytes(old, scanResponse = false))
        assertFalse(AdBudget.fits(old))
        assertEquals("adv 40 (uuid128 18, svcdata 22)", AdBudget.layout(old))
    }

    @Test
    fun theThreeLayoutsFit() {
        val response = ServiceDataChannel.ScanResponse.advertise(TOKEN, RadarRole.HIDER)
        assertEquals(18, AdBudget.bytes(response, scanResponse = false))
        assertEquals(22, AdBudget.bytes(response, scanResponse = true))
        assertTrue(AdBudget.fits(response))
        assertEquals("adv 18 (uuid128 18) + rsp 22 (svcdata 22)", AdBudget.layout(response))

        val bare = ServiceDataChannel.Bare.advertise(TOKEN, RadarRole.HIDER)
        assertEquals(22, AdBudget.bytes(bare, scanResponse = false))
        assertEquals(0, AdBudget.bytes(bare, scanResponse = true))

        val mfr = ServiceDataChannel.Mfr.advertise(TOKEN, RadarRole.HIDER)
        assertEquals(2 + 2 + 16 + 4, AdBudget.bytes(mfr, scanResponse = false))
        assertTrue(AdBudget.fits(mfr))
    }

    @Test
    fun theFlagsCountOnlyInAConnectableAdvertisement() {
        val bare = ServiceDataChannel.Bare.advertise(TOKEN, RadarRole.HIDER)
        assertEquals(25, AdBudget.bytes(bare, scanResponse = false, connectable = true))
        assertEquals(0, AdBudget.bytes(bare, scanResponse = true, connectable = true))
        assertEquals("adv 25 (flags 3, svcdata 22)", AdBudget.layout(bare, connectable = true))
    }

    @Test
    fun uuidsOfOneSizeShareAField() {
        val parts = listOf(
            AdPart.ServiceUuid("FEAA"),
            AdPart.ServiceUuid("0000FEAB-0000-1000-8000-00805F9B34FB"),
            AdPart.ServiceUuid(RadarService.UUID),
        )
        assertEquals(2 + 2 * 2 + 2 + 16, AdBudget.bytes(parts, scanResponse = false))
        val beacon = IBeaconChannel.advertise(TOKEN, RadarRole.SEEKER)
        assertEquals(27, AdBudget.bytes(beacon, scanResponse = false))
    }

    @Test
    fun androidDropsTheNameAndWhatDoesNotFitFromTheEnd() {
        val plan = AdPlan.of(RadarCatalog.game, TOKEN, RadarRole.HIDER, Platform.ANDROID)
        assertEquals(
            listOf(
                TechPart(ServiceDataChannel.ScanResponse.id, AdPart.ServiceUuid(RadarService.UUID)),
                TechPart(
                    ServiceDataChannel.ScanResponse.id,
                    AdPart.ServiceData(RadarService.UUID, token, inScanResponse = true),
                ),
            ),
            plan.parts,
        )
        assertEquals(listOf(Dropped(NameChannel.id, AdPart.LocalName(TOKEN), "no local name on android")), plan.dropped)

        val old = listOf(
            TechPart("old", AdPart.ServiceUuid(RadarService.UUID)),
            TechPart("old", AdPart.ServiceData(RadarService.UUID, token)),
        )
        val fitted = AdPlan.forPlatform(old, Platform.ANDROID)
        assertEquals(old.take(1), fitted.parts)
        assertEquals("over 31 bytes: adv 40 (uuid128 18, svcdata 22)", fitted.dropped.single().why)
    }

    @Test
    fun anIphoneSendsNoDataAndAnIBeaconAlone() {
        val hider = AdPlan.of(RadarCatalog.game, TOKEN, RadarRole.HIDER, Platform.IOS)
        assertEquals(listOf(AdPart.ServiceUuid(RadarService.UUID), AdPart.LocalName(TOKEN)), hider.adParts)
        assertEquals(listOf("no service data on ios"), hider.dropped.map { it.why })
        assertEquals(listOf(ServiceDataChannel.ScanResponse.id, NameChannel.id), hider.techs)

        val both = AdPlan.forPlatform(
            listOf(TechPart("a", AdPart.ServiceUuid(RadarService.UUID))) +
                IBeaconChannel.advertise(TOKEN, RadarRole.SEEKER).map { TechPart("b", it) },
            Platform.IOS,
        )
        assertEquals(listOf("b"), both.techs)
        assertEquals(listOf("an ibeacon advertises alone on ios"), both.dropped.map { it.why })
    }

    @Test
    fun thePlanTellsTheTraceOncePerChannelAndWhatItDropped() {
        val trace = RecordingTrace()
        val plan = AdPlan.of(RadarCatalog.game, TOKEN, RadarRole.HIDER, Platform.ANDROID)
        plan.trace(trace, "start", TOKEN)
        assertEquals(
            listOf(
                "start ${ServiceDataChannel.ScanResponse.id} adv 18 (uuid128 18) + rsp 22 (svcdata 22) null",
                "dropped ${NameChannel.id} adv 18 (uuid128 18) + rsp 22 (svcdata 22) no local name on android",
            ),
            trace.adverts,
        )
    }
}

class RecordingTrace : RadarTrace {
    val adverts = mutableListOf<String>()
    val scans = mutableListOf<String>()
    val frames = mutableListOf<Pair<AirFrame, List<Pair<String, Decoded>>>>()
    val seconds = mutableListOf<AirSecond>()

    override fun advertise(action: String, tech: String, token: String?, layout: String?, error: String?) {
        adverts += "$action $tech $layout $error"
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
