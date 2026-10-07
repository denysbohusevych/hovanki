package app.hovanki.server.account

import app.hovanki.server.db.getInstant
import app.hovanki.server.db.getInstantOrNull
import app.hovanki.server.db.toTimestamptz
import app.hovanki.shared.protocol.AdminEntitlement
import app.hovanki.shared.protocol.Entitlement
import app.hovanki.shared.protocol.EntitlementSource
import app.hovanki.shared.protocol.UserId
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

/** A row of `entitlements`: a paid extra of an account (docs/adr/0023-entitlements.md). [until] null: forever. */
data class EntitlementRecord(
    val userId: UserId,
    val entitlement: Entitlement,
    val source: EntitlementSource,
    val grantedAt: Instant,
    val until: Instant?,
    val grantedBy: String?,
) {
    fun isActiveAt(now: Instant): Boolean = until == null || until > now

    fun toAdmin() = AdminEntitlement(
        entitlement = entitlement,
        source = source,
        grantedAtMillis = grantedAt.toEpochMilli(),
        untilMillis = until?.toEpochMilli(),
        byName = grantedBy,
    )
}

/**
 * `entitlements`: what paid extras an account has. The server is the only one that decides; the profile carries the
 * active ones to the app ([active]), and code that locks something behind one asks [has].
 */
@Repository
class EntitlementRepository(private val jdbc: JdbcClient) {
    /** Grants [entitlement], or replaces the one already there (its source, start and end). */
    fun grant(
        userId: UserId,
        entitlement: Entitlement,
        source: EntitlementSource,
        by: String?,
        now: Instant,
        until: Instant?,
    ) {
        jdbc.sql(
            """
            INSERT INTO entitlements (user_id, entitlement, source, granted_at, until, granted_by)
            VALUES (:u, :e, :source, :now, :until, :by)
            ON CONFLICT (user_id, entitlement)
            DO UPDATE SET source = :source, granted_at = :now, until = :until, granted_by = :by
            """.trimIndent(),
        )
            .param("u", userId.value)
            .param("e", entitlement.id)
            .param("source", source.name)
            .param("now", now.toTimestamptz())
            .param("until", until?.toTimestamptz())
            .param("by", by)
            .update()
    }

    /** False if the account didn't have it. */
    fun revoke(userId: UserId, entitlement: Entitlement): Boolean =
        jdbc.sql("DELETE FROM entitlements WHERE user_id = :u AND entitlement = :e")
            .param("u", userId.value)
            .param("e", entitlement.id)
            .update() > 0

    /** The extras of [userId] active at [now], in the catalog's order. */
    fun active(userId: UserId, now: Instant): List<EntitlementRecord> =
        jdbc.sql("SELECT * FROM entitlements WHERE user_id = :u AND (until IS NULL OR until > :now)")
            .param("u", userId.value)
            .param("now", now.toTimestamptz())
            .query(mapper)
            .list()
            .filterNotNull()
            .sortedBy { it.entitlement.ordinal }

    fun has(userId: UserId, entitlement: Entitlement, now: Instant): Boolean = jdbc.sql(
        """
        SELECT count(*) FROM entitlements
        WHERE user_id = :u AND entitlement = :e AND (until IS NULL OR until > :now)
        """.trimIndent(),
    )
        .param("u", userId.value)
        .param("e", entitlement.id)
        .param("now", now.toTimestamptz())
        .query(Int::class.java)
        .single() > 0

    private companion object {
        // A row of an extra this version doesn't know (written by a newer server) is skipped.
        val mapper = RowMapper { rs, _ ->
            Entitlement.fromId(rs.getString("entitlement"))?.let {
                EntitlementRecord(
                    userId = UserId(rs.getString("user_id")),
                    entitlement = it,
                    source = EntitlementSource.valueOf(rs.getString("source")),
                    grantedAt = rs.getInstant("granted_at"),
                    until = rs.getInstantOrNull("until"),
                    grantedBy = rs.getString("granted_by"),
                )
            }
        }
    }
}
