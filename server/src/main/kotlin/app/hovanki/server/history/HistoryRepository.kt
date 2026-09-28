package app.hovanki.server.history

import app.hovanki.server.db.getInstant
import app.hovanki.server.db.getInstantOrNull
import app.hovanki.server.db.toTimestamptz
import app.hovanki.server.game.GameRecord
import app.hovanki.server.game.PlayerResult
import app.hovanki.shared.protocol.GameHistoryEntry
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameRoute
import app.hovanki.shared.protocol.PlayerStats
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.RoutePoint
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.ZoneSchedule
import app.hovanki.shared.protocol.protocolJson
import kotlinx.serialization.builtins.ListSerializer
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant

/**
 * `played_games`, `game_results` and `game_routes` (docs/adr/0007-game-history-and-routes.md). Writes are idempotent
 * (a game saved twice changes nothing); reads are always about one user, their own rows.
 */
@Repository
class HistoryRepository(private val jdbc: JdbcClient) {
    fun insertGame(record: GameRecord) {
        val settings = record.settings
        jdbc.sql(
            """
            INSERT INTO played_games (id, created_at, started_at, zone_started_at, finished_at, players, guests,
                                      seekers, hiders_caught, hiders_eliminated, catch_claims, catches, disputes,
                                      chat_messages, buildings, zone_radius_meters, zone_stages, hiding_seconds,
                                      seeking_seconds)
            VALUES (:id, :createdAt, :startedAt, :zoneStartedAt, :finishedAt, :players, :guests, :seekers,
                    :hidersCaught, :hidersEliminated, :catchClaims, :catches, :disputes, :chatMessages, :buildings,
                    :zoneRadius, :zoneStages, :hidingSeconds, :seekingSeconds)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
            .param("id", record.gameId.value)
            .param("createdAt", millis(record.createdAtMillis))
            .param("startedAt", millis(record.startedAtMillis))
            .param("zoneStartedAt", record.zoneStartedAtMillis?.let(::millis))
            .param("finishedAt", millis(record.finishedAtMillis))
            .param("players", record.players)
            .param("guests", record.guests)
            .param("seekers", record.seekers)
            .param("hidersCaught", record.hidersCaught)
            .param("hidersEliminated", record.hidersEliminated)
            .param("catchClaims", record.catchClaims)
            .param("catches", record.catches)
            .param("disputes", record.disputes)
            .param("chatMessages", record.chatMessages)
            .param("buildings", record.buildings.name)
            .param("zoneRadius", settings.zone.initial.radiusMeters)
            .param("zoneStages", settings.zone.stages.size)
            .param("hidingSeconds", settings.hidingSeconds)
            .param("seekingSeconds", settings.seekingSeconds)
            .update()
    }

    fun insertResult(record: GameRecord, result: PlayerResult) {
        jdbc.sql(
            """
            INSERT INTO game_results (user_id, game_id, started_at, finished_at, role, status, won, players, seekers,
                                      catch_claims, catches, survived_seconds, zone_warnings, building_warnings, fixes,
                                      distance_meters, moving_seconds, max_speed_mps, sparks, quests_done)
            VALUES (:userId, :gameId, :startedAt, :finishedAt, :role, :status, :won, :players, :seekers, :catchClaims,
                    :catches, :survivedSeconds, :zoneWarnings, :buildingWarnings, :fixes, :distance, :movingSeconds,
                    :maxSpeed, :sparks, :questsDone)
            ON CONFLICT (user_id, game_id) DO NOTHING
            """.trimIndent(),
        )
            .param("userId", result.userId.value)
            .param("gameId", record.gameId.value)
            .param("startedAt", millis(record.startedAtMillis))
            .param("finishedAt", millis(record.finishedAtMillis))
            .param("role", result.role.name)
            .param("status", result.status.name)
            .param("won", result.won)
            .param("players", record.players)
            .param("seekers", record.seekers)
            .param("catchClaims", result.catchClaims)
            .param("catches", result.catches)
            .param("survivedSeconds", result.survivedSeconds)
            .param("zoneWarnings", result.zoneWarnings)
            .param("buildingWarnings", result.buildingWarnings)
            .param("fixes", result.fixes)
            .param("distance", result.distanceMeters)
            .param("movingSeconds", result.movingSeconds)
            .param("maxSpeed", result.maxSpeedMetersPerSecond)
            .param("sparks", result.sparks)
            .param("questsDone", result.questsDone)
            .update()
    }

    /** Saves the route of [result]; only next to its row in `game_results` (else nothing happens). */
    fun insertRoute(record: GameRecord, result: PlayerResult, savedAt: Instant) {
        jdbc.sql(
            """
            INSERT INTO game_routes (user_id, game_id, saved_at, role, zone, started_at, zone_started_at, finished_at,
                                     points)
            SELECT :userId, :gameId, :savedAt, :role, CAST(:zone AS jsonb), :startedAt, :zoneStartedAt, :finishedAt,
                   CAST(:points AS jsonb)
            WHERE EXISTS (SELECT 1 FROM game_results WHERE user_id = :userId AND game_id = :gameId)
            ON CONFLICT (user_id, game_id) DO NOTHING
            """.trimIndent(),
        )
            .param("userId", result.userId.value)
            .param("gameId", record.gameId.value)
            .param("savedAt", savedAt.toTimestamptz())
            .param("role", result.role.name)
            .param("zone", protocolJson.encodeToString(ZoneSchedule.serializer(), record.settings.zone))
            .param("startedAt", millis(record.startedAtMillis))
            .param("zoneStartedAt", record.zoneStartedAtMillis?.let(::millis))
            .param("finishedAt", millis(record.finishedAtMillis))
            .param("points", protocolJson.encodeToString(pointsSerializer, result.route))
            .update()
    }

    /** [userId]'s games that ended before [before] (null: all), newest first, at most [limit]. */
    fun games(userId: UserId, before: Instant?, limit: Int): List<GameHistoryEntry> {
        val page = if (before == null) "" else "AND r.finished_at < :before"
        return jdbc.sql(
            """
            SELECT r.*, (g.user_id IS NOT NULL) AS has_route
            FROM game_results r
            LEFT JOIN game_routes g ON g.user_id = r.user_id AND g.game_id = r.game_id
            WHERE r.user_id = :userId $page
            ORDER BY r.finished_at DESC, r.game_id
            LIMIT :limit
            """.trimIndent(),
        )
            .param("userId", userId.value)
            .apply { if (before != null) param("before", before.toTimestamptz()) }
            .param("limit", limit)
            .query(entryMapper)
            .list()
            .filterNotNull()
    }

    fun stats(userId: UserId): PlayerStats = jdbc.sql(
        """
        SELECT count(*)                                                AS games,
               count(*) FILTER (WHERE role = 'HIDER')                  AS games_as_hider,
               count(*) FILTER (WHERE role = 'SEEKER')                 AS games_as_seeker,
               count(*) FILTER (WHERE won)                             AS wins,
               count(*) FILTER (WHERE won AND role = 'HIDER')          AS wins_as_hider,
               count(*) FILTER (WHERE won AND role = 'SEEKER')         AS wins_as_seeker,
               coalesce(sum(catches), 0)                               AS catches,
               count(*) FILTER (WHERE status = 'CAUGHT')               AS times_caught,
               count(*) FILTER (WHERE status = 'ELIMINATED')           AS times_eliminated,
               max(survived_seconds) FILTER (WHERE role = 'HIDER')     AS longest_hide,
               coalesce(sum(extract(EPOCH FROM finished_at - started_at)), 0) AS played_seconds,
               coalesce(sum(distance_meters), 0)                       AS distance,
               coalesce(sum(moving_seconds), 0)                        AS moving_seconds,
               max(max_speed_mps)                                      AS max_speed,
               max(distance_meters)                                    AS longest_game,
               min(started_at)                                         AS first_game,
               max(finished_at)                                        AS last_game,
               coalesce(sum(sparks), 0)                                AS sparks,
               coalesce(sum(quests_done), 0)                           AS quests_done
        FROM game_results
        WHERE user_id = :userId
        """.trimIndent(),
    )
        .param("userId", userId.value)
        .query { rs, _ ->
            val distance = rs.getDouble("distance")
            val moving = rs.getLong("moving_seconds")
            PlayerStats(
                games = rs.getInt("games"),
                gamesAsHider = rs.getInt("games_as_hider"),
                gamesAsSeeker = rs.getInt("games_as_seeker"),
                wins = rs.getInt("wins"),
                winsAsHider = rs.getInt("wins_as_hider"),
                winsAsSeeker = rs.getInt("wins_as_seeker"),
                catches = rs.getInt("catches"),
                timesCaught = rs.getInt("times_caught"),
                timesEliminated = rs.getInt("times_eliminated"),
                longestHideSeconds = rs.getIntOrNull("longest_hide"),
                playedSeconds = rs.getDouble("played_seconds").toLong(),
                distanceMeters = distance,
                movingSeconds = moving,
                averageSpeedMetersPerSecond = if (moving > 0) distance / moving else null,
                maxSpeedMetersPerSecond = rs.getDoubleOrNull("max_speed"),
                longestGameMeters = rs.getDoubleOrNull("longest_game"),
                firstGameAtMillis = rs.getInstantOrNull("first_game")?.toEpochMilli(),
                lastGameAtMillis = rs.getInstantOrNull("last_game")?.toEpochMilli(),
                sparks = rs.getInt("sparks"),
                questsDone = rs.getInt("quests_done"),
            )
        }
        .single()

    /** [userId]'s saved route of [gameId]; null when there is none. [retention] tells when it goes. */
    fun route(userId: UserId, gameId: GameId, retention: Duration): GameRoute? = jdbc.sql(
        "SELECT * FROM game_routes WHERE user_id = :userId AND game_id = :gameId",
    )
        .param("userId", userId.value)
        .param("gameId", gameId.value)
        .query { rs, _ ->
            GameRoute(
                gameId = GameId(rs.getString("game_id")),
                role = Role.valueOf(rs.getString("role")),
                zone = protocolJson.decodeFromString(ZoneSchedule.serializer(), rs.getString("zone")),
                startedAtMillis = rs.getInstant("started_at").toEpochMilli(),
                zoneStartedAtMillis = rs.getInstantOrNull("zone_started_at")?.toEpochMilli(),
                finishedAtMillis = rs.getInstant("finished_at").toEpochMilli(),
                points = protocolJson.decodeFromString(pointsSerializer, rs.getString("points")),
                expiresAtMillis = rs.getInstant("saved_at").plus(retention).toEpochMilli(),
            )
        }
        .optional()
        .orElse(null)

    fun deleteRoute(userId: UserId, gameId: GameId): Boolean =
        jdbc.sql("DELETE FROM game_routes WHERE user_id = :userId AND game_id = :gameId")
            .param("userId", userId.value)
            .param("gameId", gameId.value)
            .update() > 0

    fun deleteRoutes(userId: UserId): Int =
        jdbc.sql("DELETE FROM game_routes WHERE user_id = :userId").param("userId", userId.value).update()

    private companion object {
        val pointsSerializer = ListSerializer(RoutePoint.serializer())

        fun millis(epochMillis: Long) = Instant.ofEpochMilli(epochMillis).toTimestamptz()

        fun ResultSet.getIntOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

        fun ResultSet.getDoubleOrNull(column: String): Double? = getDouble(column).takeUnless { wasNull() }

        val entryMapper = RowMapper { rs, _ ->
            GameHistoryEntry(
                gameId = GameId(rs.getString("game_id")),
                startedAtMillis = rs.getInstant("started_at").toEpochMilli(),
                finishedAtMillis = rs.getInstant("finished_at").toEpochMilli(),
                role = Role.valueOf(rs.getString("role")),
                status = PlayerStatus.valueOf(rs.getString("status")),
                won = rs.getBoolean("won"),
                players = rs.getInt("players"),
                seekers = rs.getInt("seekers"),
                catches = rs.getInt("catches"),
                survivedSeconds = rs.getIntOrNull("survived_seconds"),
                distanceMeters = rs.getDouble("distance_meters"),
                movingSeconds = rs.getInt("moving_seconds"),
                maxSpeedMetersPerSecond = rs.getDoubleOrNull("max_speed_mps"),
                hasRoute = rs.getBoolean("has_route"),
                sparks = rs.getInt("sparks"),
                questsDone = rs.getInt("quests_done"),
            )
        }
    }
}
