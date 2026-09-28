package app.hovanki.server.bigGames

import app.hovanki.server.db.getInstant
import app.hovanki.server.db.getInstantOrNull
import app.hovanki.server.db.toTimestamptz
import app.hovanki.shared.protocol.AreaNorms
import app.hovanki.shared.protocol.BigGameId
import app.hovanki.shared.protocol.BigGameSetup
import app.hovanki.shared.protocol.BigGameStatus
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.TerrainAreas
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserSummary
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.protocol.protocolJson
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

/** A row of `big_games` (docs/adr/0010-big-games.md). */
data class BigGameRecord(
    val id: BigGameId,
    val title: String,
    val status: BigGameStatus,
    val startsAt: Instant,
    val timeZone: String,
    val zone: ZonePolygon,
    val setup: BigGameSetup,
    val norms: AreaNorms,
    /** The estimate at the last change; null: no map data then. */
    val capacity: Int?,
    val areas: TerrainAreas?,
    val playerLimit: Int,
    /** The round in memory once the lobby opened. */
    val gameId: GameId?,
    /** The admin running it: their account can't play in it. */
    val hostUserId: UserId?,
    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val endedAt: Instant? = null,
) {
    // Never the zone in logs: a list of coordinates says nothing useful there.
    override fun toString(): String = "BigGame(${id.value}, $status)"
}

/** `big_games` and `big_game_signups`. Times are passed in (the injected Clock). */
@Repository
class BigGameRepository(private val jdbc: JdbcClient) {
    fun insert(game: BigGameRecord) {
        jdbc.sql(
            """
            INSERT INTO big_games (id, title, status, starts_at, time_zone, zone, setup, norms, capacity, areas,
                                   player_limit, game_id, host_user_id, created_by, created_at, updated_at, ended_at)
            VALUES (:id, :title, :status, :startsAt, :timeZone, CAST(:zone AS jsonb), CAST(:setup AS jsonb),
                    CAST(:norms AS jsonb), :capacity, CAST(:areas AS jsonb), :playerLimit, :gameId, :hostUserId,
                    :createdBy, :createdAt, :updatedAt, :endedAt)
            """.trimIndent(),
        ).bind(game).update()
    }

    /** Everything an admin can change, and the state; the id, the creator and the creation time stay. */
    fun update(game: BigGameRecord) {
        jdbc.sql(
            """
            UPDATE big_games SET title = :title, status = :status, starts_at = :startsAt, time_zone = :timeZone,
                zone = CAST(:zone AS jsonb), setup = CAST(:setup AS jsonb), norms = CAST(:norms AS jsonb),
                capacity = :capacity, areas = CAST(:areas AS jsonb), player_limit = :playerLimit, game_id = :gameId,
                host_user_id = :hostUserId, updated_at = :updatedAt, ended_at = :endedAt
            WHERE id = :id
            """.trimIndent(),
        ).bind(game).update()
    }

    fun find(id: BigGameId): BigGameRecord? =
        jdbc.sql("SELECT * FROM big_games WHERE id = :id").param("id", id.value).query(rows).optional().orElse(null)

    /** The row, locked until the transaction ends: sign-ups against the limit, changes against the scheduler. */
    fun findForUpdate(id: BigGameId): BigGameRecord? = jdbc.sql("SELECT * FROM big_games WHERE id = :id FOR UPDATE")
        .param("id", id.value).query(rows).optional().orElse(null)

    /** Ahead or going on, soonest first. */
    fun open(): List<BigGameRecord> = jdbc.sql(
        "SELECT * FROM big_games WHERE status IN ('SCHEDULED', 'LOBBY', 'RUNNING') ORDER BY starts_at, id",
    ).query(rows).list()

    /** Finished at [since] or later: their results may still be in memory. */
    fun finishedSince(since: Instant): List<BigGameRecord> =
        jdbc.sql("SELECT * FROM big_games WHERE status = 'FINISHED' AND ended_at >= :since")
            .param("since", since.toTimestamptz()).query(rows).list()

    /** For admins: the newest [limit], by start. */
    fun latest(limit: Int): List<BigGameRecord> =
        jdbc.sql("SELECT * FROM big_games ORDER BY starts_at DESC, id LIMIT :limit")
            .param("limit", limit).query(rows).list()

    /** True when [userId] was not signed up yet. */
    fun signUp(id: BigGameId, userId: UserId, now: Instant): Boolean = jdbc.sql(
        """
        INSERT INTO big_game_signups (big_game_id, user_id, signed_up_at) VALUES (:id, :userId, :now)
        ON CONFLICT DO NOTHING
        """.trimIndent(),
    ).param("id", id.value).param("userId", userId.value).param("now", now.toTimestamptz()).update() > 0

    fun cancelSignup(id: BigGameId, userId: UserId): Boolean =
        jdbc.sql("DELETE FROM big_game_signups WHERE big_game_id = :id AND user_id = :userId")
            .param("id", id.value).param("userId", userId.value).update() > 0

    fun isSignedUp(id: BigGameId, userId: UserId): Boolean = jdbc.sql(
        "SELECT EXISTS (SELECT 1 FROM big_game_signups WHERE big_game_id = :id AND user_id = :userId)",
    ).param("id", id.value).param("userId", userId.value).query(Boolean::class.java).single()

    fun signedUp(id: BigGameId): Int = counts(listOf(id))[id] ?: 0

    /** How many signed up for each of [ids]. */
    fun counts(ids: Collection<BigGameId>): Map<BigGameId, Int> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.sql(
            """
            SELECT big_game_id, count(*) AS signed_up FROM big_game_signups
            WHERE big_game_id IN (:ids) GROUP BY big_game_id
            """.trimIndent(),
        ).param("ids", ids.map { it.value })
            .query { rs, _ -> BigGameId(rs.getString("big_game_id")) to rs.getInt("signed_up") }
            .list().toMap()
    }

    /** Which of [ids] [userId] signed up for. */
    fun signedUpAmong(userId: UserId, ids: Collection<BigGameId>): Set<BigGameId> {
        if (ids.isEmpty()) return emptySet()
        return jdbc.sql("SELECT big_game_id FROM big_game_signups WHERE user_id = :userId AND big_game_id IN (:ids)")
            .param("userId", userId.value).param("ids", ids.map { it.value })
            .query { rs, _ -> BigGameId(rs.getString("big_game_id")) }.list().toSet()
    }

    /** [userId]'s friends who signed up for each of [ids], by nickname. */
    fun friendsSignedUp(userId: UserId, ids: Collection<BigGameId>): Map<BigGameId, List<UserSummary>> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.sql(
            """
            SELECT s.big_game_id, u.id, u.nickname FROM big_game_signups s
            JOIN friendships f ON f.user_id = :userId AND f.friend_id = s.user_id
            JOIN users u ON u.id = s.user_id
            WHERE s.big_game_id IN (:ids)
            ORDER BY u.nickname_key
            """.trimIndent(),
        ).param("userId", userId.value).param("ids", ids.map { it.value })
            .query { rs, _ ->
                BigGameId(rs.getString("big_game_id")) to
                    UserSummary(UserId(rs.getString("id")), rs.getString("nickname"))
            }
            .list().groupBy({ it.first }, { it.second })
    }

    private fun JdbcClient.StatementSpec.bind(game: BigGameRecord): JdbcClient.StatementSpec = this
        .param("id", game.id.value)
        .param("title", game.title)
        .param("status", game.status.name)
        .param("startsAt", game.startsAt.toTimestamptz())
        .param("timeZone", game.timeZone)
        .param("zone", protocolJson.encodeToString(ZonePolygon.serializer(), game.zone))
        .param("setup", protocolJson.encodeToString(BigGameSetup.serializer(), game.setup))
        .param("norms", protocolJson.encodeToString(AreaNorms.serializer(), game.norms))
        .param("capacity", game.capacity)
        .param("areas", game.areas?.let { protocolJson.encodeToString(TerrainAreas.serializer(), it) })
        .param("playerLimit", game.playerLimit)
        .param("gameId", game.gameId?.value)
        .param("hostUserId", game.hostUserId?.value)
        .param("createdBy", game.createdBy)
        .param("createdAt", game.createdAt.toTimestamptz())
        .param("updatedAt", game.updatedAt.toTimestamptz())
        .param("endedAt", game.endedAt?.toTimestamptz())

    private val rows = RowMapper { rs, _ ->
        BigGameRecord(
            id = BigGameId(rs.getString("id")),
            title = rs.getString("title"),
            status = BigGameStatus.valueOf(rs.getString("status")),
            startsAt = rs.getInstant("starts_at"),
            timeZone = rs.getString("time_zone"),
            zone = protocolJson.decodeFromString(ZonePolygon.serializer(), rs.getString("zone")),
            setup = protocolJson.decodeFromString(BigGameSetup.serializer(), rs.getString("setup")),
            norms = protocolJson.decodeFromString(AreaNorms.serializer(), rs.getString("norms")),
            capacity = rs.getObject("capacity") as Int?,
            areas = rs.getString("areas")?.let { protocolJson.decodeFromString(TerrainAreas.serializer(), it) },
            playerLimit = rs.getInt("player_limit"),
            gameId = rs.getString("game_id")?.let(::GameId),
            hostUserId = rs.getString("host_user_id")?.let(::UserId),
            createdBy = rs.getString("created_by"),
            createdAt = rs.getInstant("created_at"),
            updatedAt = rs.getInstant("updated_at"),
            endedAt = rs.getInstantOrNull("ended_at"),
        )
    }
}
