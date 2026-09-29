package app.hovanki.server.radio

import app.hovanki.server.db.toTimestamptz
import app.hovanki.server.game.CalibrationAnchor
import app.hovanki.server.game.CalibrationBucket
import app.hovanki.shared.protocol.Carry
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * `radio_calibration` (docs/adr/0012-nearby-radar.md, «Калибровка»): how loud each kind of phone hears each other
 * kind, counted across the finished games with the radar. Aggregates by model only: no game, no player, no position,
 * nothing to delete with an account.
 */
@Repository
class RadioCalibrationRepository(private val jdbc: JdbcClient) {
    /** Adds a game's counts to the table's. */
    fun add(buckets: List<CalibrationBucket>, at: Instant) {
        for (bucket in buckets) {
            jdbc.sql(
                """
                INSERT INTO radio_calibration (hearer_model, heard_model, hearer_carry, heard_carry, anchor, rssi_dbm,
                                               readings, updated_at)
                VALUES (:hearer, :heard, :hearerCarry, :heardCarry, :anchor, :rssi, :readings, :at)
                ON CONFLICT (hearer_model, heard_model, hearer_carry, heard_carry, anchor, rssi_dbm)
                DO UPDATE SET readings = radio_calibration.readings + EXCLUDED.readings,
                              updated_at = EXCLUDED.updated_at
                """.trimIndent(),
            )
                .param("hearer", bucket.hearerModel)
                .param("heard", bucket.heardModel)
                .param("hearerCarry", bucket.hearerCarry.name)
                .param("heardCarry", bucket.heardCarry.name)
                .param("anchor", bucket.anchor.name)
                .param("rssi", bucket.rssiDbm)
                .param("readings", bucket.readings.toLong())
                .param("at", at.toTimestamptz())
                .update()
        }
    }

    /** Everything counted so far, the loudest first. */
    fun all(): List<CalibrationBucket> = jdbc.sql(
        "SELECT * FROM radio_calibration ORDER BY hearer_model, heard_model, anchor, rssi_dbm DESC",
    )
        .query { rs, _ ->
            CalibrationBucket(
                hearerModel = rs.getString("hearer_model"),
                heardModel = rs.getString("heard_model"),
                hearerCarry = Carry.valueOf(rs.getString("hearer_carry")),
                heardCarry = Carry.valueOf(rs.getString("heard_carry")),
                anchor = CalibrationAnchor.valueOf(rs.getString("anchor")),
                rssiDbm = rs.getInt("rssi_dbm"),
                readings = rs.getLong("readings").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        }
        .list()
}
