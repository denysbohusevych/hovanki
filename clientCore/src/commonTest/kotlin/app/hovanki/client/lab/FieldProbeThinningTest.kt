package app.hovanki.client.lab

import app.hovanki.device.lab.LabBattery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldProbeThinningTest {
    @Test
    fun theProximitySensorIsWrittenWhenItChanges() {
        val thinning = FieldProbeThinning()
        assertFalse(thinning.allowProximity(null))
        assertTrue(thinning.allowProximity(false))
        assertFalse(thinning.allowProximity(false))
        assertTrue(thinning.allowProximity(true))
        assertTrue(thinning.allowProximity(false))
    }

    @Test
    fun theLightIsWrittenOnBigChangesAndOnceAMinuteAtLeast() {
        val thinning = FieldProbeThinning()
        assertTrue(thinning.allowLight(300.0, 0))
        // The same light a second later, and a flicker: nothing.
        assertFalse(thinning.allowLight(300.0, 1_000))
        assertFalse(thinning.allowLight(500.0, 10_000))
        // A twice as bright light after the gap.
        assertTrue(thinning.allowLight(700.0, 20_000))
        // But never twice in the gap, however big the change.
        assertFalse(thinning.allowLight(5.0, 22_000))
        // The dark: a pocket. Into it, and out of it.
        assertTrue(thinning.allowLight(0.0, 30_000))
        assertFalse(thinning.allowLight(0.0, 40_000))
        assertTrue(thinning.allowLight(50.0, 50_000))
        // A steady light is still written once a minute.
        assertFalse(thinning.allowLight(50.0, 100_000))
        assertTrue(thinning.allowLight(50.0, 110_000))
    }

    @Test
    fun theBatteryIsWrittenOnAChangeOfStateAndOnceAMinute() {
        val thinning = FieldProbeThinning()
        assertTrue(thinning.allowBattery(LabBattery(0.8, "discharging", false), 0))
        assertFalse(thinning.allowBattery(LabBattery(0.79, "discharging", false), 10_000))
        assertTrue(thinning.allowBattery(LabBattery(0.79, "charging", false), 20_000))
        assertTrue(thinning.allowBattery(LabBattery(0.79, "charging", true), 30_000))
        assertFalse(thinning.allowBattery(LabBattery(0.78, "charging", true), 59_000))
        assertTrue(thinning.allowBattery(LabBattery(0.78, "charging", true), 90_000))
    }

    @Test
    fun thermalCarryAndActivityAreWrittenOnChanges() {
        val thinning = FieldProbeThinning()
        assertEquals(
            listOf(true, false, true, true),
            listOf("nominal", "nominal", "fair", "nominal").map(thinning::allowThermal),
        )
        assertEquals(
            listOf(true, false, true),
            listOf("in_hand", "in_hand", "in_pocket").map(thinning::allowCarry),
        )
        assertEquals(
            listOf(true, true, false),
            listOf("still", "walking", "walking").map(thinning::allowActivity),
        )
    }
}
