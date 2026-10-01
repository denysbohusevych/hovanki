package app.hovanki.server.db

import app.hovanki.server.account.AccountProperties
import app.hovanki.server.admin.AdminProperties
import app.hovanki.server.bigGames.BigGameProperties
import app.hovanki.server.history.HistoryProperties
import app.hovanki.server.lab.FieldProperties
import app.hovanki.server.lab.LabProperties
import app.hovanki.server.lab.LabRunRepository
import app.hovanki.server.moderation.ModerationProperties
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The schema itself (db/migration) on the test database (TestPostgres): keys, cascades, retention. */
@SpringBootTest
class DatabaseTest(@Autowired private val jdbc: JdbcClient) {
    private val now = Instant.parse("2026-06-01T12:00:00Z")

    @Test
    fun migrationsApply() {
        val applied = jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
            .query(String::class.java)
            .list()
        assertContains(applied, "1")
        assertContains(applied, "2")
        assertContains(applied, "3")
        val tables = jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
            .query(String::class.java)
            .set()
        for (table in EXPECTED_TABLES) assertContains(tables, table)
        // V2: unconfirmed accounts are no longer looked up by age.
        val indexes = jdbc.sql("SELECT indexname FROM pg_indexes WHERE tablename = 'users'")
            .query(String::class.java)
            .set()
        assertFalse("users_unverified_created_at" in indexes, "$indexes")
    }

    @Test
    fun nicknameAndEmailKeysAreUnique() {
        val key = unique("bob")
        insertUser(nicknameKey = key, emailKey = "$key@example.com")

        val sameNickname = assertFailsWith<DuplicateKeyException> {
            insertUser(nicknameKey = key, emailKey = "other-$key@example.com")
        }
        assertContains(sameNickname.mostSpecificCause.message.orEmpty(), "users_nickname_key_unique")

        val sameEmail = assertFailsWith<DuplicateKeyException> {
            insertUser(nicknameKey = "other-$key", emailKey = "$key@example.com")
        }
        assertContains(sameEmail.mostSpecificCause.message.orEmpty(), "users_email_key_unique")
    }

    @Test
    fun deletingAUserDeletesEverythingOfTheirsButReports() {
        val alice = insertUser()
        val bob = insertUser()
        insert("INSERT INTO account_sessions VALUES (:a, :a, :t, :t)", alice)
        insert("INSERT INTO email_codes VALUES (:a, 'VERIFY_EMAIL', 'hash', :t, 0, :t)", alice)
        insert("INSERT INTO friend_requests VALUES (:a, :b, :t)", alice, bob)
        insert("INSERT INTO friendships VALUES (:a, :b, :t), (:b, :a, :t)", alice, bob)
        insert("INSERT INTO blocks VALUES (:b, :a, :t)", alice, bob)
        insert("INSERT INTO user_groups VALUES (:a, 'Alice''s', :a, :t), (:b, 'Bob''s', :b, :t)", alice, bob)
        insert("INSERT INTO group_members VALUES (:a, :b, :t), (:b, :a, :t), (:b, :b, :t)", alice, bob)
        val game = insertPlayedGame()
        insertResult(alice, game)
        insertResult(bob, game)
        insertRoute(alice, game, savedAt = now)
        insertRecording(game, savedAt = now)
        insertTrack(game, "p-alice", alice)
        insertTrack(game, "p-bob", bob)
        insertTrack(game, "p-guest", userId = null)
        // A game's field log: alice's phone, bob's and a guest's.
        val field = insertLabRun(createdAt = now, finishedAt = null, kind = "GAME")
        val aliceDevice = insertLabDevice(field, now, userId = alice)
        insertLabDevice(field, now, userId = bob)
        insertLabDevice(field, now, userId = null)
        insert(
            """
            INSERT INTO reports (game_id, message_seq, reporter_player_id, reporter_user_id, reported_user_id,
                                 reported_name, text, created_at)
            VALUES ('game', 1, 'player', :b, :a, 'Alice', 'text', :t)
            """,
            alice,
            bob,
        )

        assertEquals(1, jdbc.sql("DELETE FROM users WHERE id = :id").param("id", alice).update())

        for ((table, column) in USER_COLUMNS) {
            val left = jdbc.sql("SELECT count(*) FROM $table WHERE $column = :id").param("id", alice)
                .query(Int::class.java)
                .single()
            assertEquals(0, left, "$table.$column")
        }
        assertEquals(listOf(bob), jdbc.sql("SELECT id FROM user_groups WHERE id IN (:a, :b)").ids(alice, bob))
        assertEquals(listOf(bob), jdbc.sql("SELECT user_id FROM group_members WHERE group_id = :b").ids(alice, bob))
        val reports = jdbc.sql("SELECT count(*) FROM reports WHERE reported_user_id = :id").param("id", alice)
            .query(Int::class.java)
            .single()
        assertEquals(1, reports)
        // The game itself stays: it has nothing about anybody. So do the other players' results.
        assertEquals(listOf(game), jdbc.sql("SELECT id FROM played_games WHERE id = :a").ids(game))
        assertEquals(listOf(bob), jdbc.sql("SELECT user_id FROM game_results WHERE game_id = :a").ids(game))
        // The recording stays for the others, without alice's way (docs/adr/0011-spectators-and-recordings.md).
        val tracks = jdbc.sql("SELECT player_id FROM game_recording_tracks WHERE game_id = :a").ids(game)
        assertEquals(setOf("p-bob", "p-guest"), tracks.toSet())
        // The field log loses alice's phone with its chunks; bob's and the guests' stay with the run.
        val aliceChunks = jdbc.sql("SELECT count(*) FROM lab_chunks WHERE device_id = :id").param("id", aliceDevice)
            .query(Int::class.java).single()
        assertEquals(0, aliceChunks)
        val devices = jdbc.sql("SELECT count(*) FROM lab_devices WHERE run_id = :id").param("id", field)
            .query(Int::class.java).single()
        assertEquals(3, devices)
        assertEquals(listOf(field), jdbc.sql("SELECT id FROM lab_runs WHERE id = :a").ids(field))
    }

    @Test
    fun fieldLogsGoWholeNinetyDaysAfterTheirGame() {
        val retention = DataRetention(
            jdbc,
            AccountProperties(),
            ModerationProperties(),
            HistoryProperties(),
            AdminProperties(),
            BigGameProperties(),
            LabProperties(),
            LabRunRepository(jdbc),
            FieldProperties(),
            Clock.fixed(now, ZoneOffset.UTC),
        )
        fun daysAgo(days: Double) = now.minusSeconds((days * 86_400).toLong())
        val player = insertUser()

        // Games that ended 91 and 89 days ago; one never closed (the server lost it), opened 92 and 90.5 days ago.
        val old = insertLabRun(createdAt = daysAgo(91.1), finishedAt = daysAgo(91.0), kind = "GAME")
        insertLabDevice(old, daysAgo(91.1), userId = player)
        val recent = insertLabRun(createdAt = daysAgo(89.1), finishedAt = daysAgo(89.0), kind = "GAME")
        val abandoned = insertLabRun(createdAt = daysAgo(92.0), finishedAt = null, kind = "GAME")
        val lately = insertLabRun(createdAt = daysAgo(90.5), finishedAt = null, kind = "GAME")
        // A lab run as old keeps its run and devices, only its chunks go.
        val lab = insertLabRun(createdAt = daysAgo(92.0), finishedAt = daysAgo(91.0))

        val deleted = retention.run()

        assertTrue(deleted.fieldRuns >= 2, "$deleted")
        val left = jdbc.sql("SELECT id FROM lab_runs WHERE id IN (:a, :b, :c, :d, :e)")
            .param("a", old).param("b", recent).param("c", abandoned).param("d", lately).param("e", lab)
            .query(String::class.java).set()
        assertEquals(setOf(recent, lately, lab), left)
        // Nothing of the old game's run is left: the player's phone neither.
        val devices = jdbc.sql("SELECT count(*) FROM lab_devices WHERE user_id = :id").param("id", player)
            .query(Int::class.java).single()
        assertEquals(0, devices)
    }

    @Test
    fun retentionDeletesOnlyWhatIsOld() {
        val accounts = AccountProperties()
        val retention = DataRetention(
            jdbc,
            accounts,
            ModerationProperties(),
            HistoryProperties(),
            AdminProperties(),
            BigGameProperties(),
            LabProperties(),
            LabRunRepository(jdbc),
            FieldProperties(),
            Clock.fixed(now, ZoneOffset.UTC),
        )
        val longAgo = now.minus(Duration.ofDays(400))
        val oldUnverified = insertUser(createdAt = longAgo, verifiedAt = null)
        val newUnverified = insertUser(createdAt = now.minus(Duration.ofDays(1)), verifiedAt = null)
        val oldVerified = insertUser(createdAt = longAgo, verifiedAt = longAgo)
        insertSession(oldVerified, lastUsedAt = longAgo)
        val activeSession = insertSession(oldVerified, lastUsedAt = now.minus(Duration.ofDays(179)))
        val code = "INSERT INTO email_codes VALUES (:a, :b, 'hash', :t, 0, :t)"
        insertAt(code, oldVerified, "RESET_PASSWORD", at = now.minusSeconds(1))
        insertAt(code, newUnverified, "VERIFY_EMAIL", at = now.plusSeconds(60))
        insertAt("INSERT INTO friend_requests VALUES (:a, :b, :t)", oldVerified, newUnverified, at = longAgo)
        val oldGame = insertPlayedGame()
        val recentGame = insertPlayedGame()
        insertResult(oldVerified, oldGame)
        insertResult(oldVerified, recentGame)
        insertRoute(oldVerified, oldGame, savedAt = now.minus(Duration.ofDays(91)))
        insertRoute(oldVerified, recentGame, savedAt = now.minus(Duration.ofDays(89)))
        insertRecording(oldGame, savedAt = now.minus(Duration.ofDays(91)))
        insertTrack(oldGame, "p1", oldVerified)
        insertRecording(recentGame, savedAt = now.minus(Duration.ofDays(89)))
        insertTrack(recentGame, "p1", oldVerified)
        // The admin (docs/adr/0008-admin.md): sessions end a week after the login or a day after their last request,
        // sanctions go a year after their end, the audit log after a year; a ban forever stays.
        fun adminSession(createdAgo: Duration, usedAgo: Duration): String {
            val hash = unique("admin")
            jdbc.sql(
                "INSERT INTO admin_sessions (token_hash, user_id, created_at, last_used_at, rotated_at) " +
                    "VALUES (:h, :u, :c, :l, :l)",
            )
                .param("h", hash)
                .param("u", oldVerified)
                .param("c", now.minus(createdAgo).toTimestamptz())
                .param("l", now.minus(usedAgo).toTimestamptz())
                .update()
            return hash
        }
        adminSession(createdAgo = Duration.ofDays(8), usedAgo = Duration.ofHours(1))
        adminSession(createdAgo = Duration.ofDays(2), usedAgo = Duration.ofHours(25))
        val adminSession = adminSession(createdAgo = Duration.ofDays(6), usedAgo = Duration.ofHours(23))
        val sanction = "INSERT INTO sanctions (user_id, kind, reason, created_by, created_at, until) " +
            "VALUES (:a, :b, 'spam', 'mod', :t, :t)"
        insertAt(sanction, oldVerified, "MUTE", at = now.minus(Duration.ofDays(366)))
        insertAt(sanction, oldVerified, "MUTE", at = now.minus(Duration.ofDays(364)))
        jdbc.sql(
            "INSERT INTO sanctions (user_id, kind, reason, created_by, created_at) VALUES (:a, 'BAN', 'x', 'mod', :t)",
        )
            .param("a", oldVerified)
            .param("t", longAgo.toTimestamptz())
            .update()
        for (daysAgo in listOf(366L, 1L)) {
            jdbc.sql(
                "INSERT INTO admin_audit (at, actor_id, actor_name, action, target_user_id) " +
                    "VALUES (:t, 'admin', 'admin', 'BAN', :a)",
            )
                .param("a", oldVerified)
                .param("t", now.minus(Duration.ofDays(daysAgo)).toTimestamptz())
                .update()
        }

        val deleted = retention.run()

        // Other tests share the database: at least ours went, and the fresh rows stay.
        assertTrue(
            deleted.sessions >= 1 && deleted.emailCodes >= 1 && deleted.friendRequests >= 1 && deleted.routes >= 1 &&
                deleted.recordings >= 1,
            "$deleted",
        )
        // Recordings go after 90 days, everybody's way in them with them.
        val recordings = jdbc.sql("SELECT game_id FROM game_recordings WHERE game_id IN (:a, :b)")
            .ids(oldGame, recentGame)
        assertEquals(listOf(recentGame), recordings)
        val recorded = jdbc.sql("SELECT game_id FROM game_recording_tracks WHERE user_id = :a").ids(oldVerified)
        assertEquals(listOf(recentGame), recorded)
        // Routes go after 90 days; the games stay in the history.
        val routes = jdbc.sql("SELECT game_id FROM game_routes WHERE user_id = :a").ids(oldVerified)
        assertEquals(listOf(recentGame), routes)
        val results = jdbc.sql("SELECT game_id FROM game_results WHERE user_id = :a").ids(oldVerified)
        assertEquals(setOf(oldGame, recentGame), results.toSet())
        // Accounts stay, however old, confirmed or not: confirming the email is optional.
        val users = jdbc.sql("SELECT id FROM users WHERE id IN (:a, :b)").ids(oldUnverified, newUnverified)
        assertEquals(setOf(oldUnverified, newUnverified), users.toSet())
        assertEquals(listOf(oldVerified), jdbc.sql("SELECT id FROM users WHERE id = :a").ids(oldVerified))
        val sessions = jdbc.sql("SELECT token_hash FROM account_sessions WHERE user_id = :a").ids(oldVerified)
        assertEquals(listOf(activeSession), sessions)
        val codes = jdbc.sql(
            "SELECT purpose FROM email_codes WHERE user_id IN (:a, :b)",
        ).ids(oldVerified, newUnverified)
        assertEquals(listOf("VERIFY_EMAIL"), codes)
        assertEquals(emptyList(), jdbc.sql("SELECT to_user FROM friend_requests WHERE from_user = :a").ids(oldVerified))
        assertEquals(
            listOf(adminSession),
            jdbc.sql("SELECT token_hash FROM admin_sessions WHERE user_id = :a").ids(oldVerified),
        )
        val sanctions = jdbc.sql("SELECT kind FROM sanctions WHERE user_id = :a ORDER BY kind").ids(oldVerified)
        assertEquals(listOf("BAN", "MUTE"), sanctions)
        val entries = jdbc.sql("SELECT count(*) FROM admin_audit WHERE target_user_id = :a")
            .param("a", oldVerified)
            .query(Int::class.java)
            .single()
        assertEquals(1, entries)
    }

    @Test
    fun labLogsGoNinetyDaysAfterTheirRun() {
        val retention = DataRetention(
            jdbc,
            AccountProperties(),
            ModerationProperties(),
            HistoryProperties(),
            AdminProperties(),
            BigGameProperties(),
            LabProperties(),
            LabRunRepository(jdbc),
            FieldProperties(),
            Clock.fixed(now, ZoneOffset.UTC),
        )
        fun daysAgo(days: Double) = now.minusSeconds((days * 86_400).toLong())

        // Finished 91 and 89 days ago; never finished, made 92 days ago (its join window ended 91 days ago) and 90.5
        // days ago (its window ended 89.5 days ago).
        val old = insertLabRun(createdAt = daysAgo(92.0), finishedAt = daysAgo(91.0))
        val recent = insertLabRun(createdAt = daysAgo(90.0), finishedAt = daysAgo(89.0))
        val abandoned = insertLabRun(createdAt = daysAgo(92.0), finishedAt = null)
        val lately = insertLabRun(createdAt = daysAgo(90.5), finishedAt = null)

        val deleted = retention.run()

        assertTrue(deleted.labChunks >= 2, "$deleted")
        val runs = listOf(old, recent, abandoned, lately)
        val chunks = runs.filter { run ->
            jdbc.sql(
                "SELECT count(*) FROM lab_chunks c JOIN lab_devices d ON d.id = c.device_id WHERE d.run_id = :a",
            ).param("a", run).query(Long::class.java).single() > 0
        }
        assertEquals(listOf(recent, lately), chunks)
        // The runs and their devices stay: labels, models and numbers.
        for (run in runs) {
            assertEquals(listOf(run), jdbc.sql("SELECT run_id FROM lab_devices WHERE run_id = :a").ids(run))
        }
    }

    @Test
    fun theAdminWhoMadeALabRunIsForgottenAfterAYear() {
        val retention = DataRetention(
            jdbc,
            AccountProperties(),
            ModerationProperties(),
            HistoryProperties(),
            AdminProperties(),
            BigGameProperties(),
            LabProperties(),
            LabRunRepository(jdbc),
            FieldProperties(),
            Clock.fixed(now, ZoneOffset.UTC),
        )
        fun daysAgo(days: Long) = now.minus(Duration.ofDays(days))
        val old = insertLabRun(createdAt = daysAgo(366), finishedAt = daysAgo(365))
        val recent = insertLabRun(createdAt = daysAgo(364), finishedAt = null)

        val deleted = retention.run()

        assertTrue(deleted.labRunNames >= 1, "$deleted")
        fun maker(run: String): String? = jdbc.sql("SELECT created_by_name FROM lab_runs WHERE id = :id")
            .param("id", run).query { rs, _ -> listOf(rs.getString(1)) }.single().single()
        assertNull(maker(old))
        assertEquals("admin", maker(recent))
        // The run itself stays, with its report.
        assertEquals(listOf(old), jdbc.sql("SELECT id FROM lab_runs WHERE id = :a").ids(old))
    }

    /** A run of the radio lab (or a game's field log: [kind] `GAME`) with one device and one chunk of its log. */
    private fun insertLabRun(createdAt: Instant, finishedAt: Instant?, kind: String = "LAB"): String {
        val run = unique("lab")
        jdbc.sql(
            """
            INSERT INTO lab_runs (id, code, title, scenario_id, scenario_version, status, created_by_name, created_at,
                                  finished_at, salt, kind, game_id)
            VALUES (:id, :id, 'test', 'e2e', 3, :status, 'admin', :createdAt, :finishedAt, '00', :kind, :gameId)
            """.trimIndent(),
        )
            .param("id", run)
            .param("status", if (finishedAt == null) "RUNNING" else "FINISHED")
            .param("createdAt", createdAt.toTimestamptz())
            .param("finishedAt", finishedAt?.toTimestamptz())
            .param("kind", kind)
            .param("gameId", if (kind == "GAME") unique("game") else null)
            .update()
        insertLabDevice(run, createdAt, userId = null)
        return run
    }

    /** A device of [run] with one chunk; [userId]: a field log's player with an account. */
    private fun insertLabDevice(run: String, joinedAt: Instant, userId: String?): String {
        val device = unique("device")
        jdbc.sql(
            """
            INSERT INTO lab_devices (id, run_id, label, capabilities, token_hash, radar_token, joined_at, user_id,
                                     consent_at)
            VALUES (:id, :run, 'A', '{}', :id, 'abcd0123', :at, :userId, :at)
            """.trimIndent(),
        ).param("id", device).param("run", run).param("at", joinedAt.toTimestamptz()).param("userId", userId).update()
        jdbc.sql(
            """
            INSERT INTO lab_chunks (device_id, seq_from, seq_to, events, received_at, body)
            VALUES (:id, 1, 1, 1, :at, :body)
            """.trimIndent(),
        ).param("id", device).param("at", joinedAt.toTimestamptz()).param("body", byteArrayOf(1, 2, 3)).update()
        return device
    }

    private fun insertUser(
        nicknameKey: String = unique("user"),
        emailKey: String = "$nicknameKey@example.com",
        createdAt: Instant = now,
        verifiedAt: Instant? = now,
    ): String {
        val id = unique("u")
        jdbc.sql(
            """
            INSERT INTO users (id, nickname, nickname_key, email, email_key, email_verified_at, password_hash, language,
                               created_at)
            VALUES (:id, :nickname, :nicknameKey, :email, :emailKey, :verifiedAt, 'hash', 'en', :createdAt)
            """,
        )
            .param("id", id)
            .param("nickname", nicknameKey)
            .param("nicknameKey", nicknameKey)
            .param("email", emailKey)
            .param("emailKey", emailKey)
            .param("verifiedAt", verifiedAt?.toTimestamptz())
            .param("createdAt", createdAt.toTimestamptz())
            .update()
        return id
    }

    private fun insertPlayedGame(): String {
        val id = unique("game")
        jdbc.sql(
            """
            INSERT INTO played_games (id, created_at, started_at, finished_at, players, guests, seekers, hiders_caught,
                                      hiders_eliminated, catch_claims, catches, disputes, chat_messages, buildings,
                                      zone_radius_meters, zone_stages, hiding_seconds, seeking_seconds)
            VALUES (:id, :t, :t, :t, 3, 1, 1, 1, 0, 1, 1, 0, 4, 'READY', 400, 3, 300, 1800)
            """,
        ).param("id", id).param("t", now.toTimestamptz()).update()
        return id
    }

    private fun insertResult(userId: String, gameId: String) = insert(
        """
        INSERT INTO game_results (user_id, game_id, started_at, finished_at, role, status, won, players, seekers,
                                  catch_claims, catches, zone_warnings, building_warnings, fixes, distance_meters,
                                  moving_seconds)
        VALUES (:a, :b, :t, :t, 'HIDER', 'ACTIVE', true, 3, 1, 0, 0, 0, 0, 100, 1234.5, 900)
        """,
        userId,
        gameId,
    )

    private fun insertRoute(userId: String, gameId: String, savedAt: Instant) = insertAt(
        """
        INSERT INTO game_routes (user_id, game_id, saved_at, role, zone, started_at, finished_at, points)
        VALUES (:a, :b, :t, 'HIDER', '{}', :t, :t, '[]')
        """,
        userId,
        gameId,
        savedAt,
    )

    private fun insertRecording(gameId: String, savedAt: Instant) = insertAt(
        """
        INSERT INTO game_recordings (game_id, saved_at, started_at, finished_at, zone)
        VALUES (:a, :t, :t, :t, '{}')
        """,
        gameId,
        gameId,
        savedAt,
    )

    /** One player's way in the recording of [gameId]; [userId] null: a guest. */
    private fun insertTrack(gameId: String, playerId: String, userId: String?) {
        jdbc.sql(
            """
            INSERT INTO game_recording_tracks (game_id, player_id, user_id, name, role, status, points)
            VALUES (:game, :player, :user, 'Name', 'HIDER', 'ACTIVE', '[]')
            """.trimIndent(),
        )
            .param("game", gameId)
            .param("player", playerId)
            .param("user", userId)
            .update()
    }

    private fun insertSession(userId: String, lastUsedAt: Instant): String {
        val token = unique("token")
        jdbc.sql("INSERT INTO account_sessions VALUES (:token, :user, :t, :t)")
            .param("token", token)
            .param("user", userId)
            .param("t", lastUsedAt.toTimestamptz())
            .update()
        return token
    }

    private fun insert(sql: String, a: String, b: String = a) = insertAt(sql, a, b, now)

    private fun insertAt(sql: String, a: String, b: String, at: Instant) {
        jdbc.sql(sql.trimIndent()).param("a", a).param("b", b).param("t", at.toTimestamptz()).update()
    }

    private fun JdbcClient.StatementSpec.ids(a: String, b: String = a): List<String> =
        param("a", a).param("b", b).query(String::class.java).list().filterNotNull()

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private companion object {
        val EXPECTED_TABLES = listOf(
            "users",
            "email_codes",
            "account_sessions",
            "friend_requests",
            "friendships",
            "blocks",
            "user_groups",
            "group_members",
            "reports",
            "played_games",
            "game_results",
            "game_routes",
            "radio_calibration",
            "game_recordings",
            "game_recording_tracks",
            "lab_runs",
            "lab_devices",
            "lab_chunks",
            "lab_reports",
        )

        /** Every column that points at a user, with ON DELETE CASCADE. */
        val USER_COLUMNS = listOf(
            "users" to "id",
            "email_codes" to "user_id",
            "account_sessions" to "user_id",
            "friend_requests" to "from_user",
            "friend_requests" to "to_user",
            "friendships" to "user_id",
            "friendships" to "friend_id",
            "blocks" to "blocker_id",
            "blocks" to "blocked_id",
            "user_groups" to "owner_id",
            "group_members" to "user_id",
            "game_results" to "user_id",
            // Through game_results.
            "game_routes" to "user_id",
            "game_recording_tracks" to "user_id",
            // A game's field log (docs/adr/0018-field-test-build.md §3.1): the player's phone and its chunks.
            "lab_devices" to "user_id",
        )
    }
}
