package app.hovanki.client.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LaunchOptionsTest {
    @Test
    fun readsTheKnownKeys() {
        val values = mapOf(
            LaunchOptions.SERVER to "http://10.0.2.2:8080",
            LaunchOptions.NAME to "Sam",
            LaunchOptions.JOIN_CODE to "ABC234",
            LaunchOptions.HIDING_SECONDS to " 15 ",
            LaunchOptions.ALLOW_SIMULATED_LOCATION to "true",
        )

        assertEquals(
            LaunchOptions("http://10.0.2.2:8080", "Sam", "ABC234", hidingSeconds = 15, allowSimulatedLocation = true),
            LaunchOptions.read(values::get),
        )
    }

    @Test
    fun blankOrInvalidValuesAreIgnored() {
        val values = mapOf(
            LaunchOptions.NAME to " ",
            LaunchOptions.HIDING_SECONDS to "soon",
            LaunchOptions.JOIN_CODE to "X",
        )

        assertEquals(LaunchOptions(joinCode = "X"), LaunchOptions.read(values::get))
        assertNull(LaunchOptions.read { null }, "no options at all")
    }
}
