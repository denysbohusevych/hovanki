package app.hovanki.client.device

import app.hovanki.shared.protocol.Platform

/** What kind of phone this is and what it has, for `DeviceReport` (docs/adr/0012-nearby-radar.md). */
interface DeviceInfo {
    val platform: Platform

    /** A UWB chip the app may use. */
    val hasUwb: Boolean

    /** Motion sensors to tell running from walking (docs/adr/0013-quests-sparks-and-sensors.md, section 4). */
    val hasActivitySensor: Boolean

    /** The phone's model («Pixel 8», «iPhone15,2») for the radar's readings by model; null: unknown. */
    val model: String? get() = null

    /** A headless client (the e2e bots) or a platform that never said. */
    object Unknown : DeviceInfo {
        override val platform: Platform = Platform.OTHER
        override val hasUwb: Boolean = false
        override val hasActivitySensor: Boolean = false
    }
}
