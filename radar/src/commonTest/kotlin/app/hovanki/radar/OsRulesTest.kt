package app.hovanki.radar

import app.hovanki.radar.channel.ibeacon.IBeaconChannel
import app.hovanki.radar.channel.name.NameChannel
import app.hovanki.radar.channel.overflow.OverflowChannel
import app.hovanki.radar.channel.servicedata.ServiceDataChannel
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.rules.OverflowArea
import app.hovanki.shared.rules.OverflowCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OsRulesTest {
    private val gameInterests = RadarCatalog.game.flatMap { it.interests() }.distinct()

    @Test
    fun androidRefusesWhatIsOverTheBudgetAndSendsNoName() {
        val old = listOf(AdPart.ServiceUuid(RadarService.UUID), AdPart.ServiceData(RadarService.UUID, ByteArray(4)))
        assertEquals(Sent(null, "code 1"), OsRules.send(old, Platform.ANDROID, AppState.ON_SCREEN))

        val named = OsRules.send(NameChannel.advertise(TOKEN, RadarRole.HIDER), Platform.ANDROID, AppState.ON_SCREEN)
        val broadcast = assertNotNull(named.broadcast)
        assertNull(broadcast.name)
        assertEquals(listOf(RadarService.UUID), broadcast.serviceUuids)
    }

    @Test
    fun androidSendsAnIBeaconAsApplesData() {
        val sent = OsRules.send(
            IBeaconChannel.advertise(TOKEN, RadarRole.SEEKER),
            Platform.ANDROID,
            AppState.BACKGROUND,
        )
        val beacon = assertNotNull(assertNotNull(sent.broadcast).iBeacon)
        assertEquals(0x0a1b to 0x2c3d, beacon.major to beacon.minor)
    }

    @Test
    fun anIphoneInTheBackgroundSendsOnlyTheMaskOfItsUuids() {
        val name = OsRules.send(NameChannel.advertise(TOKEN, RadarRole.HIDER), Platform.IOS, AppState.BACKGROUND)
        val broadcast = assertNotNull(name.broadcast)
        assertNull(broadcast.name)
        assertEquals(emptyList(), broadcast.serviceUuids)
        assertEquals(setOf(OverflowArea.OUR_SERVICE_BIT), broadcast.maskBits)

        val overflow = OsRules.send(
            OverflowChannel.advertise(TOKEN, RadarRole.HIDER),
            Platform.IOS,
            AppState.BACKGROUND,
        )
        assertEquals(
            OverflowCode.encode(TOKEN) + OverflowArea.OUR_SERVICE_BIT,
            assertNotNull(overflow.broadcast).maskBits,
        )

        val beacon = OsRules.send(IBeaconChannel.advertise(TOKEN, RadarRole.SEEKER), Platform.IOS, AppState.BACKGROUND)
        assertEquals(Sent(null), beacon)
    }

    @Test
    fun anIphoneOnScreenCutsTheNameAndOverflowsAllButOneLongUuid() {
        val name = OsRules.send(
            listOf(AdPart.ServiceUuid(RadarService.UUID), AdPart.LocalName("${TOKEN}xyz")),
            Platform.IOS,
            AppState.ON_SCREEN,
        )
        assertEquals(TOKEN, assertNotNull(name.broadcast).name)

        val overflow = OsRules.send(OverflowChannel.advertise(TOKEN, RadarRole.HIDER), Platform.IOS, AppState.ON_SCREEN)
        val broadcast = assertNotNull(overflow.broadcast)
        assertEquals(listOf(RadarService.UUID), broadcast.serviceUuids)
        assertEquals(OverflowCode.encode(TOKEN), broadcast.maskBits)

        val data = OsRules.send(
            ServiceDataChannel.Bare.advertise(TOKEN, RadarRole.HIDER),
            Platform.IOS,
            AppState.ON_SCREEN,
        )
        assertEquals(Sent(null), data)
    }

    @Test
    fun androidHearsRawWhatAFilterMatches() {
        val mask = assertNotNull(
            OsRules.send(NameChannel.advertise(TOKEN, RadarRole.HIDER), Platform.IOS, AppState.BACKGROUND).broadcast,
        )
        assertEquals(emptyList(), hear(mask, Platform.ANDROID, gameInterests))
        val raw = hear(mask, Platform.ANDROID, OverflowChannel.interests()).single()
        assertEquals(
            setOf(OverflowArea.OUR_SERVICE_BIT),
            OverflowArea.bitsOf(raw.manufacturerData.getValue(0x004C).copyOfRange(1, 17)),
        )

        val bare = assertNotNull(
            OsRules.send(
                ServiceDataChannel.Bare.advertise(TOKEN, RadarRole.HIDER),
                Platform.ANDROID,
                AppState.ON_SCREEN,
            )
                .broadcast,
        )
        val frame = hear(bare, Platform.ANDROID, ServiceDataChannel.Bare.interests()).single()
        assertEquals(RadioApi.ANDROID_LE, frame.api)
        // The record as Android gives it: service data of a 128-bit UUID (type 0x21), the UUID little-endian.
        assertEquals("1521107d1a8c5e2f3d9b6a4e1f4c2e8d0b7a0a1b2c3d", frame.hex())
    }

    @Test
    fun anIphoneHearsTheListedServiceAndOnScreenTheOverflow() {
        val response = assertNotNull(
            OsRules.send(
                ServiceDataChannel.ScanResponse.advertise(TOKEN, RadarRole.HIDER),
                Platform.ANDROID,
                AppState.ON_SCREEN,
            ).broadcast,
        )
        for (app in AppState.entries) {
            val frame = hear(response, Platform.IOS, gameInterests, app).single()
            assertEquals(RadioApi.COREBLUETOOTH, frame.api)
            assertEquals(TOKEN, ServiceDataChannel.ScanResponse.decode(frame).single().token)
        }
        val bare = assertNotNull(
            OsRules.send(
                ServiceDataChannel.Bare.advertise(TOKEN, RadarRole.HIDER),
                Platform.ANDROID,
                AppState.ON_SCREEN,
            )
                .broadcast,
        )
        assertEquals(emptyList(), hear(bare, Platform.IOS, ServiceDataChannel.Bare.interests()))

        val mask = assertNotNull(
            OsRules.send(
                OverflowChannel.advertise(TOKEN, RadarRole.HIDER),
                Platform.IOS,
                AppState.BACKGROUND,
            ).broadcast,
        )
        val onScreen = hear(mask, Platform.IOS, OverflowChannel.interests()).single()
        assertTrue(onScreen.manufacturerData.isEmpty())
        // The table's UUID of bit 117 hashes where the game's service does: CoreBluetooth lists it too.
        val listed = onScreen.overflowUuids.mapNotNull(OverflowArea::bitOf).toSet()
        assertEquals(OverflowCode.encode(TOKEN) + OverflowArea.OUR_SERVICE_BIT, listed)
        assertEquals(TOKEN, OverflowChannel.decode(onScreen).single().token)
        assertEquals(emptyList(), hear(mask, Platform.IOS, OverflowChannel.interests(), AppState.BACKGROUND))
    }

    @Test
    fun anIphoneHearsABeaconOnlyByRanging() {
        val beacon = assertNotNull(
            OsRules.send(
                IBeaconChannel.advertise(TOKEN, RadarRole.SEEKER),
                Platform.ANDROID,
                AppState.ON_SCREEN,
            ).broadcast,
        )
        val frame = hear(beacon, Platform.IOS, gameInterests, AppState.BACKGROUND).single()
        assertEquals(RadioApi.CORELOCATION_RANGING, frame.api)
        assertNull(frame.peer)
        assertEquals(TOKEN, IBeaconChannel.decode(frame).single().token)
        assertEquals(
            setOf(RadarService.UUID),
            OsRules.regions(beacon, Platform.IOS, listOf(ScanInterest.BeaconRegion(RadarService.UUID))),
        )
        assertEquals(
            emptySet(),
            OsRules.regions(beacon, Platform.ANDROID, listOf(ScanInterest.BeaconRegion(RadarService.UUID))),
        )
    }

    private fun hear(
        broadcast: Broadcast,
        listener: Platform,
        interests: List<ScanInterest>,
        app: AppState = AppState.ON_SCREEN,
    ): List<AirFrame> = OsRules.hear(broadcast, listener, app, interests, -60, 1L, "peer")
}
