package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

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
)
