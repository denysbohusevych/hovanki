package app.hovanki.server.lab

import app.hovanki.shared.protocol.FieldUpload
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/**
 * `hovanki.field.*`: the field log of real games (docs/adr/0018-field-test-build.md §3), while the server feature
 * FIELD_LOG is on. A game's run takes more phones and more logs than a lab run ([LabProperties]): the players and their
 * rejoins, hours of play.
 */
@ConfigurationProperties("hovanki.field")
data class FieldProperties(
    /** Devices per game's run, rejoins of the same phone included. */
    val maxDevices: Int = 100,
    /** What a game's chunks may take on the server, gzipped as they are stored. */
    val maxRunBytes: DataSize = DataSize.ofGigabytes(2),
    /** A game's run takes joins and uploads until this long after it was opened: no game lasts a day. */
    val joinWindow: Duration = Duration.ofHours(24),
    /** A finished run (its game gone) still takes the phones' last uploads this long. */
    val uploadGrace: Duration = Duration.ofMinutes(30),
    /** The whole run (devices, chunks, report) is deleted this long after its game ended (DataRetention). */
    val retention: Duration = Duration.ofDays(90),
    /** The phone's pace, handed over at the join (ADR 0018 §3.2). */
    val uploadInterval: Duration = Duration.ofMillis(FieldUpload.INTERVAL_MILLIS),
    val rxEvery: Duration = Duration.ofMillis(FieldUpload.RX_EVERY_MILLIS),
    val frameEvery: Duration = Duration.ofMillis(FieldUpload.FRAME_EVERY_MILLIS),
    val gpsEvery: Duration = Duration.ofMillis(FieldUpload.GPS_EVERY_MILLIS),
)
