package app.hovanki.client

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildInfoTest {
    @Test
    fun labelNamesVersionBuildAndCommit() {
        assertEquals("0.1.0 (42) · 1a2b3c4", BuildInfo("0.1.0", "42", "1a2b3c4", isDebug = false).label)
        assertEquals(
            "0.1.0 (1) · 1a2b3c4-dirty · debug",
            BuildInfo("0.1.0", "1", "1a2b3c4-dirty", isDebug = true).label,
        )
    }

    @Test
    fun debugBuildsOnPhonesPlayOnTheServerOnEmulatorsOnTheDevelopmentMachine() {
        val phone = BuildInfo("0.1.0", "1", "1a2b3c4", isDebug = true)
        val emulator = phone.copy(isEmulator = true)
        val preview = phone.copy(isDebug = false)

        assertEquals(BuildConstants.SERVER_URL, defaultServerUrl(phone))
        assertEquals(developmentServerUrl(), defaultServerUrl(emulator))
        assertEquals(BuildConstants.SERVER_URL, defaultServerUrl(preview))
        // A preview build on an emulator is still a preview build: its one server.
        assertEquals(BuildConstants.SERVER_URL, defaultServerUrl(preview.copy(isEmulator = true)))
    }
}
