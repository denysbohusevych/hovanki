package app.hovanki.server.db

import app.hovanki.server.account.AccountProperties
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
import kotlin.test.assertTrue

/** The schema itself (V1__accounts_social.sql) on the test database (TestPostgres): keys, cascades, retention. */
@SpringBootTest
class DatabaseTest(@Autowired private val jdbc: JdbcClient) {
    private val now = Instant.parse("2026-06-01T12:00:00Z")

    @Test
    fun migrationsApply() {
        val applied = jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
            .query(String::class.java)
            .list()
        assertContains(applied, "1")
        val tables = jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
            .query(String::class.java)
            .set()
        for (table in EXPECTED_TABLES) assertContains(tables, table)
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
    }

    @Test
    fun retentionDeletesOnlyWhatIsOld() {
        val accounts = AccountProperties()
        val retention = DataRetention(jdbc, accounts, ModerationProperties(), Clock.fixed(now, ZoneOffset.UTC))
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

        val deleted = retention.run()

        // Other tests share the database: at least ours went, and the fresh rows stay.
        assertTrue(deleted.unverifiedAccounts >= 1 && deleted.sessions >= 1 && deleted.emailCodes >= 1, "$deleted")
        assertTrue(deleted.friendRequests >= 1, "$deleted")
        val users = jdbc.sql("SELECT id FROM users WHERE id IN (:a, :b)").ids(oldUnverified, newUnverified)
        assertEquals(listOf(newUnverified), users)
        assertEquals(listOf(oldVerified), jdbc.sql("SELECT id FROM users WHERE id = :a").ids(oldVerified))
        val sessions = jdbc.sql("SELECT token_hash FROM account_sessions WHERE user_id = :a").ids(oldVerified)
        assertEquals(listOf(activeSession), sessions)
        val codes = jdbc.sql(
            "SELECT purpose FROM email_codes WHERE user_id IN (:a, :b)",
        ).ids(oldVerified, newUnverified)
        assertEquals(listOf("VERIFY_EMAIL"), codes)
        assertEquals(emptyList(), jdbc.sql("SELECT to_user FROM friend_requests WHERE from_user = :a").ids(oldVerified))
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
        )
    }
}
