package app.hovanki.radar

import app.hovanki.shared.rules.OverflowArea
import app.hovanki.shared.rules.OverflowCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What each phone advertises and listens for (ADR 0017 §2.2, ADR 0018 §4 B): without a journal the game's channels
 * only, an Android hider with `.scan_response`; with one the three layouts go round the circle by the player's number,
 * and the shadow's channels join in.
 */
class RadarCatalogTest {
    private val token = "12ab34cd"

    @Test
    fun withAJournalTheLayoutsGoRoundTheCircle() {
        val layouts = (0 until 7).map { RadarCatalog.hiderLayout(it, experiment = true).id }
        assertEquals(
            listOf("scan_response", "bare", "mfr", "scan_response", "bare", "mfr", "scan_response")
                .map { "ble.service_data.$it" },
            layouts,
        )
        for (number in 0 until 5) {
            assertEquals(RadarCatalog.SCAN_RESPONSE, RadarCatalog.hiderLayout(number, experiment = false))
        }
    }

    @Test
    fun whatEachPhoneAdvertises() {
        fun tech(role: AirRole, platform: AirPlatform, shadow: Boolean, number: Int = 0) =
            RadarCatalog.advert(token, role, platform, RadioOptions(number), shadow).tech

        assertEquals(listOf("ble.service_data.scan_response"), tech(AirRole.HIDER, AirPlatform.ANDROID, false, 1))
        assertEquals(listOf("ble.service_data.bare"), tech(AirRole.HIDER, AirPlatform.ANDROID, true, 1))
        assertEquals(listOf("ble.ibeacon.ranging"), tech(AirRole.SEEKER, AirPlatform.ANDROID, true))
        assertEquals(listOf("ble.name"), tech(AirRole.HIDER, AirPlatform.IOS, false))
        assertEquals(listOf("ble.name", "ble.overflow"), tech(AirRole.HIDER, AirPlatform.IOS, true))
        assertEquals(listOf("ble.ibeacon.ranging"), tech(AirRole.SEEKER, AirPlatform.IOS, false))
        assertEquals(listOf("ble.ibeacon.ranging", "ble.overflow"), tech(AirRole.SEEKER, AirPlatform.IOS, true))
        // macOS has no iBeacon: the Mac advertises the iPhone hider's way in either role.
        assertEquals(listOf("ble.name"), tech(AirRole.SEEKER, AirPlatform.MAC, true))
    }

    @Test
    fun aLockedIPhoneSeekerKeepsItsMaskForTheBackground() {
        val advert = RadarCatalog.advert(token, AirRole.SEEKER, AirPlatform.IOS, RadioOptions(), shadow = true)
        assertTrue(advert.main.isEmpty)
        assertEquals(GameAir.SERVICE_UUID, advert.backgroundUuids.first())
        assertEquals(OverflowCode.encode(token), advert.backgroundUuids.drop(1).mapNotNull(OverflowArea::bitOf).toSet())
        assertEquals(37, advert.report().background)
    }

    @Test
    fun whatEachPhoneListensFor() {
        val service = ScanInterest.ServiceUuid(GameAir.SERVICE_UUID)
        val android = RadarCatalog.interests(AirPlatform.ANDROID, shadow = false)
        assertTrue(service in android)
        assertTrue(ScanInterest.ServiceData(GameAir.SERVICE_UUID) in android)
        assertTrue(android.any { it is ScanInterest.Manufacturer && it.companyId == GameAir.TEST_COMPANY_ID })
        assertTrue(android.any { it is ScanInterest.Manufacturer && it.prefixHex.startsWith("0215") })
        assertTrue(android.none { it is ScanInterest.Manufacturer && it.prefixHex == "01" }, "masks only in the shadow")
        assertEquals(1, android.count { it == service }, "no repeats")
        val androidShadow = RadarCatalog.interests(AirPlatform.ANDROID, shadow = true)
        assertTrue(ScanInterest.Manufacturer(GameAir.APPLE_COMPANY_ID, "01") in androidShadow)

        val ios = RadarCatalog.interests(AirPlatform.IOS, shadow = false)
        assertEquals(
            setOf(service, ScanInterest.IBeaconRanging(GameAir.SERVICE_UUID)),
            ios.toSet() - iosUnfiltered(ios),
        )
        val iosShadow = RadarCatalog.interests(AirPlatform.IOS, shadow = true)
        assertTrue(ScanInterest.IBeaconRegion(GameAir.SERVICE_UUID) in iosShadow)
        assertTrue(iosShadow.any { it is ScanInterest.OverflowUuids && it.uuids.size == OverflowArea.BITS })
    }

    /** What iOS can't filter by (service data, manufacturer data): the host leaves these to the service's scan. */
    private fun iosUnfiltered(interests: List<ScanInterest>): Set<ScanInterest> =
        interests.filter { it is ScanInterest.ServiceData || it is ScanInterest.Manufacturer }.toSet()
}
