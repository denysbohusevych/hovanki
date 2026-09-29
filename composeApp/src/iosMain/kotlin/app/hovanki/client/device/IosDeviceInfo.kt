@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.client.device

import app.hovanki.shared.protocol.Platform
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.uname
import platform.posix.utsname

class IosDeviceInfo : DeviceInfo {
    override val platform: Platform = Platform.IOS

    /**
     * Nearby Interaction (`NISession.isSupported`) is not wired yet: the precision radar is a no-op on every platform,
     * and saying «no UWB» keeps the server from pairing this phone (docs/adr/0012-nearby-radar.md, section 3).
     */
    override val hasUwb: Boolean = false

    /** Every iPhone has the accelerometer CoreMotion reads. */
    override val hasActivitySensor: Boolean = true

    /** «iPhone15,2»: the hardware model, for the radar's readings by model. */
    override val model: String? = memScoped {
        val info = alloc<utsname>()
        if (uname(info.ptr) == 0) info.machine.toKString().takeIf { it.isNotBlank() } else null
    }
}
