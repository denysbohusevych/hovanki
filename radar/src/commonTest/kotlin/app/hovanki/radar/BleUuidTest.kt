package app.hovanki.radar

import kotlin.test.Test
import kotlin.test.assertEquals

class BleUuidTest {
    @Test
    fun normalizedUpperCaseAndShortOnTheBase() {
        assertEquals(RadarService.UUID, BleUuid.normalize(RadarService.UUID.lowercase()))
        assertEquals("FEAA", BleUuid.normalize("0000feaa-0000-1000-8000-00805f9b34fb"))
        assertEquals("1234FEAA", BleUuid.normalize("1234feaa-0000-1000-8000-00805f9b34fb"))
        assertEquals(listOf(2, 4, 16), listOf("FEAA", "1234FEAA", RadarService.UUID).map(BleUuid::size))
    }

    @Test
    fun bytesAndBack() {
        assertEquals("7a0b8d2e4c1f4e6a9b3d2f5e8c1a7d10", BleUuid.hex(RadarService.UUID))
        assertEquals(RadarService.UUID, BleUuid.of(BleUuid.bytes(RadarService.UUID)))
        assertEquals("FEAA", BleUuid.of(BleUuid.bytes("feaa")))
    }
}
