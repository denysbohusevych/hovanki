package app.hovanki.radar

import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Platform

/**
 * A technique of the radar (docs/adr/0017-radar-techniques-and-big-run.md, section 2.1): one way to carry a token
 * between the phones or to measure how far they are. The game and the radio lab pick the techniques from
 * [RadarCatalog]; nobody else knows them by name. `:device` has techniques of its own (the phone itself).
 */
interface Technique {
    /** Stable, dotted: `ble.service_data.scan_response`, `ble.overflow`. The lab's log and the reports use it. */
    val id: String
    val kind: TechniqueKind
    val status: TechniqueStatus

    /** Whether this phone can use the technique at all (the chip, the OS, what the platform lets an app do). */
    fun available(caps: RadarCaps): Availability
}

/** The radar's kinds of technique (ADR 0017 §2.1); `:device` has its own. */
enum class TechniqueKind { CHANNEL, LINK, RANGING }

/**
 * [LAB]: only in the radio lab; [CANDIDATE]: always in the lab's runs, in games only where the server allows it;
 * [GAME]: in every game.
 */
enum class TechniqueStatus { LAB, CANDIDATE, GAME }

sealed interface Availability {
    data object Available : Availability

    data class Unavailable(val why: String) : Availability
}

/**
 * What the phone can do for the radar; filled by the platform's host ([AirHost.caps]) or by the simulator. Null:
 * unknown (the platform doesn't say).
 *
 * @property advertisingSets how many advertising sets the controller runs at once (Android 8+), null: unknown.
 * @property leCoded whether the controller advertises and scans on LE Coded PHY (long range).
 * @property canScanResponse whether an app can put its own data into the scan response (Android; not iOS).
 * @property canRangeBeacons iBeacon ranging and region monitoring (CoreLocation).
 * @property canReadOverflow whether this phone reads another iPhone's overflow area: raw on Android and a Mac, as
 * listed UUIDs on an iPhone on screen.
 * @property canAdvertiseOverflow whether this phone's service UUIDs go into the overflow area (an iPhone).
 */
data class RadarCaps(
    val platform: Platform,
    val bluetooth: BluetoothState,
    val advertisingSets: Int? = null,
    val leCoded: Boolean? = null,
    val canScanResponse: Boolean = true,
    val canRangeBeacons: Boolean = false,
    val canReadOverflow: Boolean = false,
    val canAdvertiseOverflow: Boolean = false,
) {
    /** Unavailable for every Bluetooth technique: no Bluetooth LE on this phone (or an app without the radar). */
    val noBluetoothLe: Availability.Unavailable?
        get() = Availability.Unavailable("no Bluetooth LE").takeIf { bluetooth == BluetoothState.UNSUPPORTED }
}
