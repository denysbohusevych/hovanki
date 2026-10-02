package app.hovanki.device

import app.hovanki.shared.protocol.Carry
import kotlin.test.Test
import kotlin.test.assertEquals

class IosCarryRulesTest {
    private fun state(
        active: Boolean = true,
        proximityOn: Boolean = false,
        near: Boolean = false,
        protectedDataAvailable: Boolean = true,
        carried: Boolean = false,
    ) = IosCarryRules.state(active, proximityOn, near, protectedDataAvailable, carried)

    @Test
    fun aLitActiveScreenIsTheHand() {
        assertEquals(Carry.IN_HAND, state())
        assertEquals(Carry.IN_HAND, state(carried = true))
        // The proximity screen on, the sensor uncovered: the screen is lit.
        assertEquals(Carry.IN_HAND, state(proximityOn = true, carried = true))
        // Covered, but the field build did not turn the screen off by the sensor: lit.
        assertEquals(Carry.IN_HAND, state(near = true, carried = true))
    }

    @Test
    fun theProximityScreenCoveredIsThePocketWhenCarried() {
        assertEquals(Carry.IN_POCKET, state(proximityOn = true, near = true, carried = true))
        assertEquals(Carry.UNKNOWN, state(proximityOn = true, near = true, carried = false))
    }

    @Test
    fun aLockedCarriedPhoneIsThePocket() {
        assertEquals(Carry.IN_POCKET, state(active = false, protectedDataAvailable = false, carried = true))
        assertEquals(Carry.UNKNOWN, state(active = false, protectedDataAvailable = false))
        assertEquals(Carry.UNKNOWN, state(active = false, carried = true))
    }
}
