package app.hovanki.client.device

import app.hovanki.shared.protocol.Platform

class IosDeviceInfo : DeviceInfo {
    override val platform: Platform = Platform.IOS

    /**
     * Nearby Interaction (`NISession.isSupported`) is not wired yet: the precision radar is a no-op on every platform,
     * and saying «no UWB» keeps the server from pairing this phone (docs/adr/0010-nearby-radar.md, section 3).
     */
    override val hasUwb: Boolean = false

    /** Every iPhone has the accelerometer CoreMotion reads. */
    override val hasActivitySensor: Boolean = true
}
