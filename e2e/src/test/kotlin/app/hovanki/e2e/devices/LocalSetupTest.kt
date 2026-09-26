package app.hovanki.e2e.devices

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalSetupTest {
    @Test
    fun findsBootedEmulatorsOnly() {
        val output = listOf(
            "List of devices attached",
            "emulator-5554\tdevice",
            "emulator-5556\toffline",
            // A USB phone can't reach the server at 10.0.2.2 or take `emu geo fix`.
            "R58M12345AB\tdevice",
            "emulator-5558          device product:sdk_gphone64_arm64 model:Pixel_6 transport_id:3",
            "emulator-5560\tunauthorized",
            "",
        ).joinToString("\n")

        assertEquals(listOf("emulator-5554", "emulator-5558"), LocalSetup.parseAdbDevices(output))
    }

    @Test
    fun noDevices() {
        assertEquals(emptyList(), LocalSetup.parseAdbDevices("List of devices attached\n\n"))
    }
}
