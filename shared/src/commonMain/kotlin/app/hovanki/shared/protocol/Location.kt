package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class GeoPoint(val lat: Double, val lon: Double)

/** One GPS fix as reported by the device. */
@Serializable
data class LocationSample(
    val point: GeoPoint,
    /** Horizontal accuracy radius in meters, as reported by the OS. */
    val accuracyMeters: Double,
    /** Time of the fix in server time (epoch millis), see ServerClock on the client. */
    val timestampMillis: Long,
    /** Android `Location.isMock`, iOS `CLLocationSourceInformation.isSimulatedBySoftware`. */
    val isMock: Boolean = false,
    /**
     * Speed over ground in m/s and course in degrees from north, where the OS says (null: it doesn't). The phone's
     * own: [Transient], never on the wire; only the field build's log (docs/adr/0018-field-test-build.md) reads them.
     */
    @Transient val speedMetersPerSecond: Double? = null,
    @Transient val bearingDegrees: Double? = null,
)
