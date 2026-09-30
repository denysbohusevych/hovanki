package app.hovanki.radar.link

import app.hovanki.radar.Availability
import app.hovanki.radar.BleUuid
import app.hovanki.radar.RadarCaps
import app.hovanki.radar.RadarCatalog
import app.hovanki.radar.RadarService
import app.hovanki.radar.TechniqueKind
import app.hovanki.radar.TechniqueStatus
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GattLinkTest {
    @Test
    fun aTokenIsItsFourBytesAndBack() {
        val bytes = GattLinkRules.tokenBytes("0a1bff00")
        assertContentEquals(byteArrayOf(0x0a, 0x1b, 0xff.toByte(), 0x00), bytes)
        assertEquals("0a1bff00", GattLinkRules.tokenOf(bytes))
        for (token in listOf("00000000", "ffffffff", "12345678")) {
            assertEquals(token, GattLinkRules.tokenOf(GattLinkRules.tokenBytes(token)))
        }
    }

    @Test
    fun anythingButFourBytesIsNoToken() {
        assertNull(GattLinkRules.tokenOf(null))
        assertNull(GattLinkRules.tokenOf(ByteArray(0)))
        assertNull(GattLinkRules.tokenOf(ByteArray(3)))
        assertNull(GattLinkRules.tokenOf(ByteArray(5)))
        assertFailsWith<IllegalArgumentException> { GattLinkRules.tokenBytes("0A1BFF00") }
        assertFailsWith<IllegalArgumentException> { GattLinkRules.tokenBytes("0a1b") }
    }

    @Test
    fun theRulesNumbers() {
        assertEquals(5, GattLinkRules.MAX_LINKS)
        assertEquals(5_000L, GattLinkRules.WRITE_MILLIS)
        assertEquals(3_000L, GattLinkRules.RSSI_MILLIS)
        assertEquals(2_000L, GattLinkRules.RECONNECT_MILLIS)
        assertTrue(GattLinkRules.FORGET_MILLIS > GattLinkRules.RECONNECT_MILLIS)
        assertTrue(GattLinkRules.OPERATION_TIMEOUT_MILLIS > GattLinkRules.RSSI_MILLIS)
    }

    @Test
    fun theLinkHasItsOwnUuidsNextToTheGames() {
        val uuids = listOf(RadarService.UUID, RadarService.LINK_UUID, RadarService.LINK_TOKEN_UUID)
        assertEquals(uuids.distinct(), uuids)
        for (uuid in uuids) assertEquals(16, BleUuid.size(uuid))
        assertNotEquals(BleUuid.normalize(RadarService.UUID), BleUuid.normalize(RadarService.LINK_UUID))
    }

    @Test
    fun theTechniqueIsTheLabsAndNeedsBluetoothLe() {
        assertEquals("gatt.link", LinkTechnique.id)
        assertEquals(TechniqueKind.LINK, LinkTechnique.kind)
        assertEquals(TechniqueStatus.LAB, LinkTechnique.status)
        // Not a channel: the catalog of channels doesn't know it.
        assertNull(RadarCatalog.byId(LinkTechnique.id))
        for (platform in listOf(Platform.ANDROID, Platform.IOS)) {
            assertEquals(Availability.Available, LinkTechnique.available(RadarCaps(platform, BluetoothState.ON)))
            assertEquals(Availability.Available, LinkTechnique.available(RadarCaps(platform, BluetoothState.OFF)))
            assertIs<Availability.Unavailable>(
                LinkTechnique.available(RadarCaps(platform, BluetoothState.UNSUPPORTED)),
            )
        }
    }

    @Test
    fun theNoopLinkIsSilent() = runTest {
        val link = NoopGattLink()
        assertFalse(link.isSupported)
        assertEquals(emptyList(), link.run(MutableStateFlow("0a1bff00")).toList())
    }
}
