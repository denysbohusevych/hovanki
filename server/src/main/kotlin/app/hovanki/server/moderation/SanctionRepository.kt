package app.hovanki.server.moderation

import app.hovanki.server.db.getInstant
import app.hovanki.server.db.getInstantOrNull
import app.hovanki.server.db.toTimestamptz
import app.hovanki.shared.protocol.AdminSanction
import app.hovanki.shared.protocol.SanctionKind
import app.hovanki.shared.protocol.UserId
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

/** A row of `sanctions`: a ban or a chat ban (docs/adr/0008-admin.md). [until] null: forever. */
data class SanctionRecord(
    val id: Long,
    val userId: UserId,
    val kind: SanctionKind,
    val reason: String,
    val createdBy: String,
    val createdAt: Instant,
    val until: Instant?,
    val liftedAt: Instant?,
    val liftedBy: String?,
) {
    fun isActiveAt(now: Instant): Boolean = liftedAt == null && (until == null || until > now)

    fun toAdmin() = AdminSanction(
        id = id,
        kind = kind,
        reason = reason,
        byName = createdBy,
        createdAtMillis = createdAt.toEpochMilli(),
        untilMillis = until?.toEpochMilli(),
        liftedAtMillis = liftedAt?.toEpochMilli(),
        liftedByName = liftedBy,
    )

    // The reason is staff's free text about a person: not for logs.
    override fun toString(): String = "SanctionRecord($id, $kind)"
}

/** `sanctions`. Staff names are stored as they were ([createdBy], [liftedBy]): the log reads the same later. */
@Repository
class SanctionRepository(private val jdbc: JdbcClient) {
    fun insert(userId: UserId, kind: SanctionKind, reason: String, by: String, now: Instant, until: Instant?): Long =
        jdbc.sql(
            """
            INSERT INTO sanctions (user_id, kind, reason, created_by, created_at, until)
            VALUES (:u, :kind, :reason, :by, :now, :until)
            RETURNING id
            """.trimIndent(),
        )
            .param("u", userId.value)
            .param("kind", kind.name)
            .param("reason", reason)
            .param("by", by)
            .param("now", now.toTimestamptz())
            .param("until", until?.toTimestamptz())
            .query(Long::class.java)
            .single()

    /** The active sanction of [kind] ending last (a forever one first), or null. */
    fun active(userId: UserId, kind: SanctionKind, now: Instant): SanctionRecord? = jdbc.sql(
        """
        SELECT * FROM sanctions
        WHERE user_id = :u AND kind = :kind AND lifted_at IS NULL AND (until IS NULL OR until > :now)
        ORDER BY until DESC NULLS FIRST
        LIMIT 1
        """.trimIndent(),
    )
        .param("u", userId.value)
        .param("kind", kind.name)
        .param("now", now.toTimestamptz())
        .query(mapper)
        .optional()
        .orElse(null)

    /** Lifts every active sanction of [kind]; how many there were. */
    fun lift(userId: UserId, kind: SanctionKind, by: String, now: Instant): Int = jdbc.sql(
        """
        UPDATE sanctions SET lifted_at = :now, lifted_by = :by
        WHERE user_id = :u AND kind = :kind AND lifted_at IS NULL AND (until IS NULL OR until > :now)
        """.trimIndent(),
    )
        .param("u", userId.value)
        .param("kind", kind.name)
        .param("by", by)
        .param("now", now.toTimestamptz())
        .update()

    /** Every sanction of [userId], newest first. */
    fun of(userId: UserId): List<SanctionRecord> =
        jdbc.sql("SELECT * FROM sanctions WHERE user_id = :u ORDER BY id DESC")
            .param("u", userId.value)
            .query(mapper)
            .list()
            .filterNotNull()

    /** Of [ids], who is under an active sanction of [kind]. */
    fun activeAmong(ids: Collection<UserId>, kind: SanctionKind, now: Instant): Set<UserId> {
        if (ids.isEmpty()) return emptySet()
        return jdbc.sql(
            """
            SELECT DISTINCT user_id FROM sanctions
            WHERE user_id IN (:ids) AND kind = :kind AND lifted_at IS NULL AND (until IS NULL OR until > :now)
            """.trimIndent(),
        )
            .param("ids", ids.map { it.value })
            .param("kind", kind.name)
            .param("now", now.toTimestamptz())
            .query(String::class.java)
            .list()
            .mapNotNullTo(mutableSetOf()) { it?.let(::UserId) }
    }

    fun countActive(kind: SanctionKind, now: Instant): Int = jdbc.sql(
        """
        SELECT count(DISTINCT user_id) FROM sanctions
        WHERE kind = :kind AND lifted_at IS NULL AND (until IS NULL OR until > :now)
        """.trimIndent(),
    )
        .param("kind", kind.name)
        .param("now", now.toTimestamptz())
        .query(Int::class.java)
        .single()

    private companion object {
        val mapper = RowMapper { rs, _ ->
            SanctionRecord(
                id = rs.getLong("id"),
                userId = UserId(rs.getString("user_id")),
                kind = SanctionKind.valueOf(rs.getString("kind")),
                reason = rs.getString("reason"),
                createdBy = rs.getString("created_by"),
                createdAt = rs.getInstant("created_at"),
                until = rs.getInstantOrNull("until"),
                liftedAt = rs.getInstantOrNull("lifted_at"),
                liftedBy = rs.getString("lifted_by"),
            )
        }
    }
}
