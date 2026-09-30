package app.hovanki.radar

import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UwbTechniqueTest {
    @Test
    fun uwbNiIsTheLabsRangingOnIPhones() {
        assertEquals("uwb.ni", UwbTechnique.id)
        assertEquals(TechniqueKind.RANGING, UwbTechnique.kind)
        assertEquals(TechniqueStatus.LAB, UwbTechnique.status)
        // Whatever the Bluetooth: Nearby Interaction has its own radio; the chip itself is the radio's isSupported.
        assertEquals(Availability.Available, UwbTechnique.available(RadarCaps(Platform.IOS, BluetoothState.OFF)))
        assertIs<Availability.Unavailable>(UwbTechnique.available(RadarCaps(Platform.ANDROID, BluetoothState.ON)))
        assertIs<Availability.Unavailable>(UwbTechnique.available(RadarCaps(Platform.OTHER, BluetoothState.ON)))
        // Not a channel: the catalog of channels doesn't know it.
        assertNull(RadarCatalog.byId(UwbTechnique.id))
    }

    @Test
    fun theNoopRadioHasNoTokenAndNoReadings() = runTest {
        val radio: PrecisionRadio = NoopPrecisionRadio()
        radio.prepare()
        assertFalse(radio.isSupported)
        assertNull(radio.token.value)
        assertTrue(radio.range(MutableStateFlow(emptyList())).toList().isEmpty())
        // The trace is silent unless the lab listens.
        RangeTrace.None.range("session_start")
    }
}
