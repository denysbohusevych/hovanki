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
    fun debugBuildsDefaultToTheDevelopmentMachine() {
        val debug = BuildInfo("0.1.0", "1", "1a2b3c4", isDebug = true)
        val preview = debug.copy(isDebug = false)

        assertEquals(developmentServerUrl(), defaultServerUrl(debug))
        assertEquals(BuildConstants.SERVER_URL, defaultServerUrl(preview))
    }
}
