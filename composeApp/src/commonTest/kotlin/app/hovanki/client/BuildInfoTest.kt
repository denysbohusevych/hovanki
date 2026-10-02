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

    @Test
    fun channelIsDebugPreviewOrRelease() {
        val release = BuildInfo("0.1.0", "42", "1a2b3c4", isDebug = false)
        val preview = release.copy(isPreview = true)
        val debug = release.copy(isDebug = true, isPreview = true)

        assertEquals("release", release.channel)
        assertEquals("preview", preview.channel)
        // A debug build is a debug build, whatever else it was built as.
        assertEquals("debug", debug.channel)
        assertEquals(false, release.isFieldBuild)
        assertEquals(true, preview.isFieldBuild)
        assertEquals(false, debug.isFieldBuild)
        assertEquals("0.1.0 (42) · 1a2b3c4 · β staging", preview.label)
    }
}
