package app.hovanki.radar

import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RadarCatalogTest {
    @Test
    fun idsAreUniqueAndFindTheirChannel() {
        val ids = RadarCatalog.channels.map { it.id }
        assertEquals(ids.distinct(), ids)
        for (channel in RadarCatalog.channels) {
            assertSame(channel, RadarCatalog.byId(channel.id))
            assertEquals(TechniqueKind.CHANNEL, channel.kind)
        }
        assertNull(RadarCatalog.byId("ble.nothing"))
    }

    @Test
    fun theGameRunsItsThreeChannels() {
        assertEquals(
            listOf("ble.service_data.scan_response", "ble.name", "ble.ibeacon", "ble.ibeacon.region"),
            RadarCatalog.game.map { it.id },
        )
        assertEquals(
            setOf(
                "ble.service_data.scan_response",
                "ble.service_data.bare",
                "ble.service_data.mfr",
                "ble.name",
                "ble.ibeacon",
                "ble.ibeacon.region",
                "ble.overflow",
            ),
            RadarCatalog.channels.map { it.id }.toSet(),
        )
    }

    @Test
    fun whatAPhoneCanRun() {
        val android = RadarCaps(Platform.ANDROID, BluetoothState.ON, canReadOverflow = true)
        val iphone = RadarCaps(
            Platform.IOS,
            BluetoothState.OFF,
            canScanResponse = false,
            canRangeBeacons = true,
            canReadOverflow = true,
            canAdvertiseOverflow = true,
        )
        fun available(caps: RadarCaps) =
            RadarCatalog.channels.filter { it.available(caps) == Availability.Available }.map { it.id }.toSet()
        assertEquals(RadarCatalog.channels.map { it.id }.toSet() - "ble.ibeacon.region", available(android))
        assertEquals(RadarCatalog.channels.map { it.id }.toSet(), available(iphone))
        assertTrue(available(android.copy(bluetooth = BluetoothState.UNSUPPORTED)).isEmpty())
        assertTrue("ble.service_data.scan_response" !in available(android.copy(canScanResponse = false)))
    }
}
