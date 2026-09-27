package app.hovanki.server.social

import app.hovanki.server.account.AccountKeys
import app.hovanki.server.account.AccountSessionRepository
import app.hovanki.server.account.PasswordHasher
import app.hovanki.server.account.UserRecord
import app.hovanki.server.account.UserRepository
import app.hovanki.server.account.uniqueName
import app.hovanki.server.api.AuthenticatedUser
import app.hovanki.server.db.toTimestamptz
import app.hovanki.server.game.IdGenerator
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserSummary
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/** An account of the social tests: confirmed (unless asked otherwise) and logged in once. */
data class TestUser(val id: UserId, val nickname: String, val token: String) {
    val summary get() = UserSummary(id, nickname)

    /** What the routes pass to the services. */
    val auth get() = AuthenticatedUser(id, AccountKeys.tokenHash(token))
}

/**
 * Accounts straight in the database, without emails and codes: the social tests need many. Also bulk rows for the
 * limits (hundreds of friends).
 */
class TestUsers(
    private val users: UserRepository,
    private val sessions: AccountSessionRepository,
    private val hasher: PasswordHasher,
    private val ids: IdGenerator,
    private val jdbc: JdbcClient,
    private val clock: Clock,
) {
    private val passwordHash by lazy { hasher.hash(PASSWORD) }

    fun create(prefix: String = "user", verified: Boolean = true): TestUser {
        val now = clock.instant()
        val user = UserRecord(
            id = ids.userId(),
            nickname = uniqueName(prefix),
            email = "${uniqueName()}@example.com",
            emailVerifiedAt = now.takeIf { verified },
            passwordHash = passwordHash,
            language = "en",
            createdAt = now,
        )
        users.insert(user)
        val token = ids.token()
        sessions.create(AccountKeys.tokenHash(token), user.id, now)
        return TestUser(user.id, user.nickname, token)
    }

    /** [count] more friends of [user], each a new confirmed account. */
    fun addFriends(user: TestUser, count: Int) {
        val prefix = insertUsers(count)
        for (sql in listOf(
            "INSERT INTO friendships SELECT :u, id, :t FROM users WHERE id LIKE :p",
            "INSERT INTO friendships SELECT id, :u, :t FROM users WHERE id LIKE :p",
        )) {
            jdbc.sql(sql).param("u", user.id.value).param("t", clock.instant().toTimestamptz()).param("p", "$prefix%")
                .update()
        }
    }

    /** [count] more friend requests from [user], each to a new confirmed account. */
    fun addOutgoingRequests(user: TestUser, count: Int) {
        val prefix = insertUsers(count)
        jdbc.sql("INSERT INTO friend_requests SELECT :u, id, :t FROM users WHERE id LIKE :p")
            .param("u", user.id.value)
            .param("t", clock.instant().toTimestamptz())
            .param("p", "$prefix%")
            .update()
    }

    /** [count] confirmed accounts whose ids, nicknames and emails start with the returned prefix. */
    private fun insertUsers(count: Int): String {
        val prefix = uniqueName("bulk")
        jdbc.sql(
            """
            INSERT INTO users (id, nickname, nickname_key, email, email_key, email_verified_at, password_hash, language,
                               created_at)
            SELECT p || i, p || i, p || i, p || i || '@example.com', p || i || '@example.com', :t, 'x', 'en', :t
            FROM generate_series(1, :n) AS i, (SELECT CAST(:p AS text) AS p) AS prefix
            """.trimIndent(),
        )
            .param("p", prefix)
            .param("n", count)
            .param("t", clock.instant().toTimestamptz())
            .update()
        return prefix
    }

    companion object {
        const val PASSWORD = "correct horse battery"
    }
}
