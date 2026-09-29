package app.hovanki.server.features

import app.hovanki.server.db.getInstant
import app.hovanki.server.db.toTimestamptz
import app.hovanki.shared.protocol.ServerFeature
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

/** A row of `feature_flags`. */
data class FeatureFlagRecord(
    val feature: ServerFeature,
    val enabled: Boolean,
    val updatedAt: Instant,
    val updatedBy: String,
)

/** `feature_flags` (docs/adr/0012-nearby-radar.md): what the operator turned on, by name. */
@Repository
class FeatureFlagRepository(private val jdbc: JdbcClient) {
    fun all(): List<FeatureFlagRecord> = jdbc.sql("SELECT * FROM feature_flags")
        .query { rs, _ ->
            // A name of a feature this server no longer knows is skipped.
            ServerFeature.entries.firstOrNull { it.name == rs.getString("feature") }?.let { feature ->
                FeatureFlagRecord(
                    feature,
                    rs.getBoolean("enabled"),
                    rs.getInstant("updated_at"),
                    rs.getString("updated_by"),
                )
            }
        }
        .list()
        .filterNotNull()

    fun set(feature: ServerFeature, enabled: Boolean, by: String, at: Instant) {
        jdbc.sql(
            """
            INSERT INTO feature_flags (feature, enabled, updated_at, updated_by) VALUES (:f, :e, :at, :by)
            ON CONFLICT (feature) DO UPDATE SET enabled = :e, updated_at = :at, updated_by = :by
            """.trimIndent(),
        )
            .param("f", feature.name)
            .param("e", enabled)
            .param("at", at.toTimestamptz())
            .param("by", by)
            .update()
    }
}
