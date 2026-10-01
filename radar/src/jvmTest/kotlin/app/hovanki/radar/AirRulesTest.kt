package app.hovanki.radar

import app.hovanki.shared.rules.OverflowArea
import app.hovanki.shared.rules.OverflowCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The OS's rules of the simulated air (ADR 0017 §2.2): what goes out of a phone and what its APIs let in. */
class AirRulesTest {
    private val token = "5eed1e55"
    private val service = GameAir.SERVICE_UUID

    @Test
    fun androidSendsNothingOver31Bytes() {
        val old = AdData(serviceUuids = listOf(service), serviceData = mapOf(service to token))
        val advert = Advert(listOf("old"), old, AdData(), null, emptyList(), emptyList(), null, AirPlatform.ANDROID)
        assertNull(AirRules.broadcast(advert, AirPlatform.ANDROID, onScreen = true))
        for (layout in RadarCatalog.hiderLayouts) {
            val broadcast = AirRules.broadcast(androidHider(layout), AirPlatform.ANDROID, onScreen = false)
            assertNotNull(broadcast, "${layout.id} goes out with the screen off too")
        }
    }

    @Test
    fun aLockedIPhoneSendsNoNameAndNoIBeaconOnlyItsMask() {
        val hider = RadarCatalog.advert(token, AirRole.HIDER, AirPlatform.IOS, RadioOptions(), shadow = false)
        val onScreen = AirRules.broadcast(hider, AirPlatform.IOS, onScreen = true)!!
        assertEquals(token, onScreen.main.localName)
        val locked = AirRules.broadcast(hider, AirPlatform.IOS, onScreen = false)!!
        assertNull(locked.main.localName)
        assertTrue(locked.main.serviceUuids.isEmpty())
        assertEquals(setOf(OverflowArea.OUR_SERVICE_BIT), maskBits(locked), "the service alone: bit 117")

        val withJournal = RadarCatalog.advert(token, AirRole.HIDER, AirPlatform.IOS, RadioOptions(), shadow = true)
        val lockedWithMask = AirRules.broadcast(withJournal, AirPlatform.IOS, onScreen = false)!!
        assertEquals(OverflowCode.encode(token) + OverflowArea.OUR_SERVICE_BIT, maskBits(lockedWithMask))

        val seeker = RadarCatalog.advert(token, AirRole.SEEKER, AirPlatform.IOS, RadioOptions(), shadow = false)
        assertNotNull(AirRules.broadcast(seeker, AirPlatform.IOS, onScreen = true)?.main?.manufacturerData?.get(0x004C))
        assertNull(AirRules.broadcast(seeker, AirPlatform.IOS, onScreen = false), "no iBeacon from the background")
    }

    @Test
    fun anIPhoneHearsOnlyWhatListsTheGamesService() {
        val interests = RadarCatalog.interests(AirPlatform.IOS, shadow = false)
        fun heard(layout: RadarChannel) = AirRules.broadcast(androidHider(layout), AirPlatform.ANDROID, true)!!
            .let { AirRules.hear(it, AirPlatform.IOS, onScreen = true, interests, -60, 0, "droid") }
        val scanResponse = heard(RadarCatalog.SCAN_RESPONSE).single()
        assertEquals(RadioApi.COREBLUETOOTH, scanResponse.api)
        assertEquals(listOf(token), RadarCatalog.SCAN_RESPONSE.decode(scanResponse)?.tokens)
        assertTrue(heard(RadarCatalog.BARE).isEmpty(), "iOS can't scan for service data")
        assertTrue(heard(RadarCatalog.MFR).isEmpty(), "nor for manufacturer data")
    }

    @Test
    fun anIPhoneRangesTheIBeaconCoreBluetoothHides() {
        val seeker = RadarCatalog.advert(token, AirRole.SEEKER, AirPlatform.ANDROID, RadioOptions(), shadow = false)
        val broadcast = AirRules.broadcast(seeker, AirPlatform.ANDROID, onScreen = false)!!
        for (onScreen in listOf(true, false)) {
            val frames = AirRules.hear(
                broadcast,
                AirPlatform.IOS,
                onScreen,
                RadarCatalog.interests(AirPlatform.IOS, false),
                -70,
                0,
                "droid",
            )
            val ranged = frames.single()
            assertEquals(RadioApi.CORELOCATION_RANGING, ranged.api)
            assertNull(ranged.peer, "CoreLocation names no sender")
            assertEquals(listOf(token), RadarCatalog.IBEACON.decode(ranged)?.tokens)
        }
    }

    @Test
    fun aMaskIsReadOnTheScreenAndByAndroidInTheShadowOnly() {
        val locked = AirRules.broadcast(
            RadarCatalog.advert(token, AirRole.HIDER, AirPlatform.IOS, RadioOptions(), shadow = true),
            AirPlatform.IOS,
            onScreen = false,
        )!!
        // An iPhone on the screen with the shadow's scan lists the table's UUIDs whose bits stand.
        val shadowIos = RadarCatalog.interests(AirPlatform.IOS, shadow = true)
        val listed = AirRules.hear(locked, AirPlatform.IOS, onScreen = true, shadowIos, -75, 0, "a").single()
        assertEquals(listOf(token), RadarCatalog.OVERFLOW.decode(listed)?.tokens)
        assertTrue(GameAir.SERVICE_UUID in listed.overflowUuids, "its scan for the service finds the bit 117 too")
        // In the pocket it reads nothing of another locked iPhone.
        assertTrue(AirRules.hear(locked, AirPlatform.IOS, onScreen = false, shadowIos, -75, 0, "a").isEmpty())
        // Android: its scan lets the mask through only with the shadow's filter.
        val game = RadarCatalog.interests(AirPlatform.ANDROID, shadow = false)
        assertTrue(AirRules.hear(locked, AirPlatform.ANDROID, true, game, -75, 0, "a").isEmpty())
        val shadow = RadarCatalog.interests(AirPlatform.ANDROID, shadow = true)
        val raw = AirRules.hear(locked, AirPlatform.ANDROID, false, shadow, -75, 0, "a").single()
        assertEquals(listOf(token), RadarCatalog.OVERFLOW.decode(raw)?.tokens)
        assertEquals(SightingVia.OVERFLOW_RAW, RadarCatalog.OVERFLOW.decode(raw)?.via)
    }

    @Test
    fun androidHearsEveryLayoutAndEveryPhoneOnTheScreen() {
        val interests = RadarCatalog.interests(AirPlatform.ANDROID, shadow = false)
        for (layout in RadarCatalog.hiderLayouts) {
            val broadcast = AirRules.broadcast(androidHider(layout), AirPlatform.ANDROID, onScreen = false)!!
            val frame = AirRules.hear(broadcast, AirPlatform.ANDROID, onScreen = false, interests, -60, 0, "b").single()
            assertEquals(listOf(layout.id), RadarCatalog.channels.mapNotNull { it.decode(frame)?.tech })
        }
        val iphone = RadarCatalog.advert(token, AirRole.HIDER, AirPlatform.IOS, RadioOptions(), shadow = false)
        val name = AirRules.hear(
            AirRules.broadcast(iphone, AirPlatform.IOS, true)!!,
            AirPlatform.ANDROID,
            false,
            interests,
            -60,
            0,
            "c",
        )
        assertEquals(listOf(token), RadarCatalog.NAME.decode(name.single())?.tokens)
    }

    private fun androidHider(layout: RadarChannel): Advert {
        val options = RadioOptions(RadarCatalog.hiderLayouts.indexOf(layout))
        return RadarCatalog.advert(token, AirRole.HIDER, AirPlatform.ANDROID, options, shadow = true)
    }

    private fun maskBits(broadcast: Broadcast): Set<Int> {
        val apple = broadcast.main.manufacturerData.getValue(0x004C)
        check(apple.startsWith("01"))
        return OverflowArea.bitsOf(AirHex.bytes(apple.drop(2)))
    }
}
