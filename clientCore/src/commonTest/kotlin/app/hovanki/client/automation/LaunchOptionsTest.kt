package app.hovanki.client.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LaunchOptionsTest {
    @Test
    fun readsTheKnownKeys() {
        val values = mapOf(
            LaunchOptions.SERVER to "http://10.0.2.2:8080",
            LaunchOptions.NAME to "Sam",
            LaunchOptions.JOIN_CODE to "ABC234",
            LaunchOptions.HIDING_SECONDS to " 15 ",
            LaunchOptions.ALLOW_SIMULATED_LOCATION to "true",
            LaunchOptions.FORGET_SAVED_GAME to "1",
            LaunchOptions.PASSWORD to " secret pass ",
            LaunchOptions.LOG_OUT to "yes",
        )

        assertEquals(
            LaunchOptions(
                "http://10.0.2.2:8080",
                "Sam",
                "ABC234",
                hidingSeconds = 15,
                allowSimulatedLocation = true,
                forgetSavedGame = true,
                password = " secret pass ",
                logOut = true,
            ),
            LaunchOptions.read(values::get),
        )
    }

    @Test
    fun blankOrInvalidValuesAreIgnored() {
        val values = mapOf(
            LaunchOptions.NAME to " ",
            LaunchOptions.HIDING_SECONDS to "soon",
            LaunchOptions.JOIN_CODE to "X",
            LaunchOptions.PASSWORD to "",
            LaunchOptions.LOG_OUT to "no",
        )

        assertEquals(LaunchOptions(joinCode = "X"), LaunchOptions.read(values::get))
        assertNull(LaunchOptions.read { null }, "no options at all")
    }

    @Test
    fun logOutAloneIsAnOption() {
        assertEquals(LaunchOptions(logOut = true), LaunchOptions.read(mapOf(LaunchOptions.LOG_OUT to "true")::get))
    }

    @Test
    fun thePasswordIsNeverPrinted() {
        val text = LaunchOptions(playerName = "anna", password = "hunter22").toString()

        assertFalse("hunter22" in text)
        assertTrue("anna" in text)
    }
}
