package app.hovanki.shared.lab

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabJoinCodeTest {
    @Test
    fun typedAndScannedCodesAreRead() {
        assertEquals("ABC234", LabJoinCode.normalize("ABC234"))
        assertEquals("ABC234", LabJoinCode.normalize(" abc-234 "))
        assertEquals("ABC234", LabJoinCode.normalize("abc 234"))
        assertEquals("ABC234", LabJoinCode.normalize(LabJoinCode.qrPayload("ABC234")))
        assertEquals("ABC234", LabJoinCode.normalize("HOVANKI-LAB:abc234"))
    }

    @Test
    fun anythingElseIsNoCode() {
        assertNull(LabJoinCode.normalize(""))
        assertNull(LabJoinCode.normalize("ABC23"), "too short")
        assertNull(LabJoinCode.normalize("ABC2345"), "too long")
        assertNull(LabJoinCode.normalize("ABC0O1"), "0, O and 1 are not in the alphabet")
        assertNull(LabJoinCode.normalize("hovanki:ABC234"))
    }

    @Test
    fun randomCodesAreWellFormed() {
        val random = Random(42)
        val codes = List(200) { LabJoinCode.random(random) }
        assertTrue(codes.all { LabJoinCode.normalize(it) == it }, "$codes")
        assertTrue(codes.distinct().size > 190)
        assertEquals("hovanki-lab:ABC234", LabJoinCode.qrPayload("ABC234"))
    }
}
