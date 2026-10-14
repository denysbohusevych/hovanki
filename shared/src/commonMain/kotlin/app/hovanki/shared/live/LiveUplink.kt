package app.hovanki.shared.live

import app.hovanki.shared.live.v2.Device
import app.hovanki.shared.live.v2.Fix
import app.hovanki.shared.live.v2.Sighting
import app.hovanki.shared.live.v2.Up
import app.hovanki.shared.protocol.DeviceReport
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.NearbySighting
import okio.ByteString

/**
 * The phone's Up (docs/live-v2/spec.md §7.1, §10): what a v1 `SyncRequest` carries, made small, and back on the server.
 *
 * Fixes: the first is absolute E7, every next one the difference to the one before it in the same Up (Int arithmetic
 * wraps the same way both ways, so even a jump across the antimeridian comes back right). Times are offsets from
 * `Up.time_ms`.
 */
object LiveUplink {
    fun up(
        seq: Int,
        ackState: Int,
        timeMillis: Long,
        samples: List<LocationSample> = emptyList(),
        nearby: List<NearbySighting> = emptyList(),
        device: DeviceReport? = null,
        chatAfter: Long? = null,
        pathResponse: ByteString = ByteString.EMPTY,
        full: Boolean = false,
    ): Up = Up(
        seq = seq,
        ack_state = ackState,
        time_ms = timeMillis,
        fixes = fixes(samples.take(LiveLimits.MAX_FIXES_PER_UP), timeMillis),
        sightings = sightings(nearby.take(LiveLimits.MAX_SIGHTINGS_PER_UP), timeMillis),
        device = device?.let(::device),
        chat_after = chatAfter,
        path_response = pathResponse,
        full = full,
    )

    fun fixes(samples: List<LocationSample>, timeMillis: Long): List<Fix> {
        var lat = 0
        var lon = 0
        return samples.map { sample ->
            val sampleLat = LiveValues.toE7(sample.point.lat)
            val sampleLon = LiveValues.toE7(sample.point.lon)
            Fix(
                lat_e7 = sampleLat - lat,
                lon_e7 = sampleLon - lon,
                accuracy_dm = LiveValues.toDecimetres(sample.accuracyMeters),
                t_ms = sample.timestampMillis - timeMillis,
                mock = sample.isMock,
            ).also {
                lat = sampleLat
                lon = sampleLon
            }
        }
    }

    /** A sighting whose token is not 8 lowercase hex characters is not sent: the server would drop it anyway. */
    fun sightings(nearby: List<NearbySighting>, timeMillis: Long): List<Sighting> = nearby.mapNotNull { sighting ->
        LiveValues.tokenToInt(sighting.token)?.let {
            Sighting(token = it, rssi = sighting.rssi, t_ms = sighting.atMillis - timeMillis)
        }
    }

    fun device(report: DeviceReport): Device = Device(
        platform = LiveEnums.wire(report.platform),
        bluetooth = LiveEnums.wire(report.bluetooth),
        uwb = report.uwb,
        off_screen = !report.onScreen,
        uwb_token = LiveValues.orEmpty(report.uwbToken),
        activity = LiveEnums.wire(report.activity),
        activity_sensor = report.activitySensor,
        carry = LiveEnums.wire(report.carry),
        model = LiveValues.orEmpty(report.model),
    )

    /** The server's side: the fixes of an Up as v1's samples. */
    fun samples(up: Up): List<LocationSample> {
        var lat = 0
        var lon = 0
        return up.fixes.map { fix ->
            lat += fix.lat_e7
            lon += fix.lon_e7
            LocationSample(
                point = GeoPoint(LiveValues.fromE7(lat), LiveValues.fromE7(lon)),
                accuracyMeters = LiveValues.fromDecimetres(fix.accuracy_dm),
                timestampMillis = up.time_ms + fix.t_ms,
                isMock = fix.mock,
            )
        }
    }

    fun nearby(up: Up): List<NearbySighting> = up.sightings.map {
        NearbySighting(token = LiveValues.tokenFromInt(it.token), rssi = it.rssi, atMillis = up.time_ms + it.t_ms)
    }

    fun deviceReport(device: Device): DeviceReport = DeviceReport(
        platform = LiveEnums.v1(device.platform),
        bluetooth = LiveEnums.v1(device.bluetooth),
        uwb = device.uwb,
        onScreen = !device.off_screen,
        uwbToken = LiveValues.nullIfEmpty(device.uwb_token),
        activity = LiveEnums.v1(device.activity),
        activitySensor = device.activity_sensor,
        carry = LiveEnums.v1(device.carry),
        model = LiveValues.nullIfEmpty(device.model),
    )
}
