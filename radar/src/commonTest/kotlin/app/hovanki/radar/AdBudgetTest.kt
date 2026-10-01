package app.hovanki.radar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bytes of the advertisements, as Android counts them before `startAdvertising` (ADR 0017 §2.2). */
class AdBudgetTest {
    private val token = "0a1b2c3d"
    private val service = GameAir.SERVICE_UUID

    @Test
    fun theFirstAppsHiderAdvertisementIs40BytesAndNeverWentOut() {
        val packet = AdBudget.android(AdData(serviceUuids = listOf(service), serviceData = mapOf(service to token)))
        assertEquals(40, packet.bytes)
        assertFalse(packet.fits)
        assertEquals("uuid128 18 + svc_data 22 = 40/31", packet.toString())
    }

    @Test
    fun theThreeLayoutsFit() {
        val scanResponse = advert(RadarCatalog.SCAN_RESPONSE)
        assertEquals(18, AdBudget.android(scanResponse.main).bytes)
        assertEquals(22, AdBudget.android(scanResponse.scanResponse).bytes)
        assertEquals(22, AdBudget.android(advert(RadarCatalog.BARE).main).bytes)
        assertEquals(26, AdBudget.android(advert(RadarCatalog.MFR).main).bytes)
        for (layout in RadarCatalog.hiderLayouts) {
            val advert = advert(layout)
            assertEquals(listOf(layout.id), advert.tech, layout.id)
            assertTrue(advert.dropped.isEmpty(), layout.id)
            assertTrue(AdBudget.android(advert.main).fits && AdBudget.android(advert.scanResponse).fits, layout.id)
        }
    }

    @Test
    fun theSeekersIBeaconIs27Bytes() {
        val advert = RadarCatalog.advert(token, AirRole.SEEKER, AirPlatform.ANDROID, RadioOptions(), shadow = false)
        assertEquals(listOf(RadarCatalog.IBEACON.id), advert.tech)
        assertEquals("mfr 27 = 27/31", AdBudget.android(advert.main).toString())
    }

    @Test
    fun fieldsAreCountedTheWayAndroidDoes() {
        val packet = AdBudget.android(
            AdData(
                serviceUuids = listOf("FEAA", "FEAB", "12345678", service),
                localName = "hovanki",
                includeTxPower = true,
            ),
            flags = true,
        )
        // flags 3, two 16-bit UUIDs in one field (2 + 4), one 32-bit (2 + 4), one 128-bit (2 + 16), tx 3, name 2 + 7.
        assertEquals(listOf(3, 6, 6, 18, 3, 9), packet.fields.map { it.bytes })
        assertEquals(45, packet.bytes)
        assertEquals("-", AdBudget.android(AdData()).toString())
    }

    @Test
    fun aPartThatDoesNotFitIsLeftOutAndNamed() {
        val tooBig = AdPart("old", main = AdData(serviceUuids = listOf(service), serviceData = mapOf(service to token)))
        val fits = AdPart("bare", main = AdData(serviceData = mapOf(service to token)))
        val advert = AdJoin.join(listOf(tooBig, fits), AirPlatform.ANDROID)
        assertEquals(listOf("bare"), advert.tech)
        assertEquals(listOf("old"), advert.dropped)
        // The same service data twice would overwrite the first: the second is left out.
        val twice = AdJoin.join(listOf(fits, fits.copy(tech = "again")), AirPlatform.ANDROID)
        assertEquals(listOf("again"), twice.dropped)
    }

    @Test
    fun anIPhoneHidersNameAndServiceFillTheForegroundRoom() {
        val advert = RadarCatalog.advert(token, AirRole.HIDER, AirPlatform.IOS, RadioOptions(), shadow = false)
        assertEquals(listOf(RadarCatalog.NAME.id), advert.tech)
        val report = advert.report()
        assertEquals("uuid128 18 + name 10 = 28/28", report.main.toString())
        assertNull(report.scanResponse)
        assertTrue(advert.backgroundUuids.isEmpty())
    }

    @Test
    fun iOSCanAdvertiseOnlyANameAndUuidsOrAnIBeacon() {
        val beacon = RadarCatalog.IBEACON.adPart(token, AirRole.SEEKER, AirPlatform.IOS)!!
        val name = RadarCatalog.NAME.adPart(token, AirRole.HIDER, AirPlatform.IOS)!!
        val serviceData = AdPart("data", main = AdData(serviceData = mapOf(service to token)))
        val advert = AdJoin.join(listOf(beacon, name, serviceData), AirPlatform.IOS)
        assertEquals(listOf(beacon.tech), advert.tech)
        assertEquals(listOf(name.tech, "data"), advert.dropped)
        assertTrue(advert.main.isEmpty)
    }

    private fun advert(layout: RadarChannel): Advert =
        AdJoin.join(listOfNotNull(layout.adPart(token, AirRole.HIDER, AirPlatform.ANDROID)), AirPlatform.ANDROID)
}
