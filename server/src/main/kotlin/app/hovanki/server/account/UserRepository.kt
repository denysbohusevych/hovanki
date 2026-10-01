package app.hovanki.server.account

import app.hovanki.server.db.getInstant
import app.hovanki.server.db.getInstantOrNull
import app.hovanki.server.db.toTimestamptz
import app.hovanki.server.game.GameException
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.UserRole
import app.hovanki.shared.protocol.UserSummary
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

/** A row of `users`. */
data class UserRecord(
    val id: UserId,
    /** NFKC-normalized ([AccountKeys.normalizeNickname]). */
    val nickname: String,
    /** Trimmed, as typed otherwise. */
    val email: String,
    val emailVerifiedAt: Instant?,
    val passwordHash: String,
    /** en, ru or uk: the language of the emails. */
    val language: String,
    val createdAt: Instant,
    /** When the user agreed to keep the routes of their games; null: they did not (docs/adr/0007). */
    val saveRoutesSince: Instant? = null,
    /** Staff roles work in the admin (docs/adr/0008-admin.md). */
    val role: UserRole = UserRole.PLAYER,
) {
    val emailVerified: Boolean get() = emailVerifiedAt != null

    /**
     * The account as its owner sees it. [labOn]: the server has the radio lab on; then staff get the lab's screen in the
     * field build (docs/adr/0018-field-test-build.md §4.D).
     */
    fun toProfile(labOn: Boolean = false) = UserProfile(
        id,
        nickname,
        email,
        emailVerified,
        createdAt.toEpochMilli(),
        saveRoutes = saveRoutesSince != null,
        labAccess = labOn && role.isStaff,
    )

    /** The account as everyone else sees it. */
    fun toSummary() = UserSummary(id, nickname)

    // Never the email or the password hash in logs.
    override fun toString(): String = "UserRecord(${id.value})"
}

/** `users`. Nicknames and emails are looked up by their keys ([AccountKeys]), so case never matters. */
@Repository
class UserRepository(private val jdbc: JdbcClient) {
    /** Throws [GameException] with [ErrorReason.NICKNAME_TAKEN] or [ErrorReason.EMAIL_TAKEN]. */
    fun insert(user: UserRecord) {
        withTakenKeys {
            jdbc.sql(
                """
                INSERT INTO users (id, nickname, nickname_key, email, email_key, email_verified_at, password_hash,
                                   language, created_at)
                VALUES (:id, :nickname, :nicknameKey, :email, :emailKey, :verifiedAt, :passwordHash, :language,
                        :createdAt)
                """.trimIndent(),
            )
                .param("id", user.id.value)
                .param("nickname", user.nickname)
                .param("nicknameKey", AccountKeys.nicknameKey(user.nickname))
                .param("email", user.email)
                .param("emailKey", AccountKeys.emailKey(user.email))
                .param("verifiedAt", user.emailVerifiedAt?.toTimestamptz())
                .param("passwordHash", user.passwordHash)
                .param("language", user.language)
                .param("createdAt", user.createdAt.toTimestamptz())
                .update()
        }
    }

    fun findById(id: UserId): UserRecord? = findBy("id", id.value)

    /** By any spelling of the nickname (case, fullwidth letters, spaces around it). */
    fun findByNickname(nickname: String): UserRecord? = findBy("nickname_key", AccountKeys.nicknameKey(nickname))

    fun findByEmail(email: String): UserRecord? = findBy("email_key", AccountKeys.emailKey(email))

    /** [login] is an email or a nickname ([AccountKeys.loginKey]). */
    fun findByLogin(login: String): UserRecord? = if ('@' in login) findByEmail(login) else findByNickname(login)

    fun findAll(ids: Collection<UserId>): List<UserRecord> {
        if (ids.isEmpty()) return emptyList()
        return jdbc.sql("SELECT * FROM users WHERE id IN (:ids)")
            .param("ids", ids.map { it.value })
            .query(mapper)
            .list()
            .filterNotNull()
    }

    /**
     * Inside a transaction: locks the rows of [ids] until it ends and returns the ids that exist. Friends, blocks and
     * groups lock the users a change reads the relations of, so two changes about one user run one after the other
     * (two users asking each other at once become friends). All in one call, in id order: two transactions never
     * wait for each other in a circle. `FOR NO KEY UPDATE` still lets others insert rows that point at the users.
     */
    fun lock(ids: Collection<UserId>): Set<UserId> {
        if (ids.isEmpty()) return emptySet()
        return jdbc.sql("SELECT id FROM users WHERE id IN (:ids) ORDER BY id FOR NO KEY UPDATE")
            .param("ids", ids.map { it.value }.distinct())
            .query(String::class.java)
            .list()
            .mapNotNullTo(mutableSetOf()) { it?.let(::UserId) }
    }

    /** False if already confirmed (or no such user). */
    fun markEmailVerified(id: UserId, at: Instant): Boolean =
        jdbc.sql("UPDATE users SET email_verified_at = :at WHERE id = :id AND email_verified_at IS NULL")
            .param("id", id.value)
            .param("at", at.toTimestamptz())
            .update() > 0

    /**
     * Replaces a not yet confirmed email; false if it is confirmed meanwhile. Throws [ErrorReason.EMAIL_TAKEN] when
     * another account has the address.
     */
    fun updateUnverifiedEmail(id: UserId, email: String): Boolean = withTakenKeys {
        jdbc.sql(
            "UPDATE users SET email = :email, email_key = :emailKey WHERE id = :id AND email_verified_at IS NULL",
        )
            .param("id", id.value)
            .param("email", email)
            .param("emailKey", AccountKeys.emailKey(email))
            .update() > 0
    }

    fun updatePassword(id: UserId, passwordHash: String): Boolean =
        jdbc.sql("UPDATE users SET password_hash = :hash WHERE id = :id")
            .param("id", id.value)
            .param("hash", passwordHash)
            .update() > 0

    /**
     * Turns keeping the routes of [id]'s games on (since [since], the moment of consent; kept when already on) or off
     * (null). False: no such user.
     */
    fun setSaveRoutes(id: UserId, since: Instant?): Boolean {
        val statement = if (since == null) {
            jdbc.sql("UPDATE users SET save_routes_since = NULL WHERE id = :id")
        } else {
            jdbc.sql("UPDATE users SET save_routes_since = coalesce(save_routes_since, :since) WHERE id = :id")
                .param("since", since.toTimestamptz())
        }
        return statement.param("id", id.value).update() > 0
    }

    /** Those of [ids] who agreed to keep the routes of their games. */
    fun savingRoutes(ids: Collection<UserId>): Set<UserId> {
        if (ids.isEmpty()) return emptySet()
        return jdbc.sql("SELECT id FROM users WHERE id IN (:ids) AND save_routes_since IS NOT NULL")
            .param("ids", ids.map { it.value }.distinct())
            .query(String::class.java)
            .list()
            .mapNotNullTo(mutableSetOf()) { it?.let(::UserId) }
    }

    /**
     * A new nickname (staff reset an offensive one). Throws [GameException] with [ErrorReason.NICKNAME_TAKEN] if
     * another account has it.
     */
    fun updateNickname(id: UserId, nickname: String): Boolean = withTakenKeys {
        jdbc.sql("UPDATE users SET nickname = :nickname, nickname_key = :key WHERE id = :id")
            .param("id", id.value)
            .param("nickname", nickname)
            .param("key", AccountKeys.nicknameKey(nickname))
            .update() > 0
    }

    fun setRole(id: UserId, role: UserRole): Boolean = jdbc.sql("UPDATE users SET role = :role WHERE id = :id")
        .param("id", id.value)
        .param("role", role.name)
        .update() > 0

    /** Moderators and admins, by nickname. */
    fun staff(): List<UserRecord> = jdbc.sql(
        "SELECT * FROM users WHERE role <> 'PLAYER' ORDER BY nickname_key",
    ).query(mapper).list().filterNotNull()

    /** Users whose nickname starts like [prefix] (case, spelling ignored), or whose id is [prefix]; at most [limit]. */
    fun search(prefix: String, limit: Int): List<UserRecord> {
        val key = AccountKeys.nicknameKey(prefix)
        return jdbc.sql(
            """
            SELECT * FROM users
            WHERE id = :id OR nickname_key LIKE :pattern ESCAPE '\'
            ORDER BY (id = :id) DESC, nickname_key
            LIMIT :limit
            """.trimIndent(),
        )
            .param("id", prefix.trim())
            .param("pattern", key.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
            .param("limit", limit)
            .query(mapper)
            .list()
            .filterNotNull()
    }

    /** Deletes the user; every row that points at them goes with them (ON DELETE CASCADE). */
    fun delete(id: UserId): Boolean = jdbc.sql("DELETE FROM users WHERE id = :id").param("id", id.value).update() > 0

    private fun findBy(column: String, value: String): UserRecord? = jdbc.sql(
        "SELECT * FROM users WHERE $column = :value",
    ).param("value", value).query(mapper).optional().orElse(null)

    private fun <T> withTakenKeys(block: () -> T): T = try {
        block()
    } catch (e: DuplicateKeyException) {
        val message = e.mostSpecificCause.message.orEmpty()
        when {
            "users_email_key_unique" in message ->
                throw GameException(ErrorCode.WRONG_STATE, "This email has an account", ErrorReason.EMAIL_TAKEN)

            "users_nickname_key_unique" in message ->
                throw GameException(ErrorCode.WRONG_STATE, "This nickname is taken", ErrorReason.NICKNAME_TAKEN)

            else -> throw e
        }
    }

    private companion object {
        val mapper = RowMapper { rs, _ ->
            UserRecord(
                id = UserId(rs.getString("id")),
                nickname = rs.getString("nickname"),
                email = rs.getString("email"),
                emailVerifiedAt = rs.getInstantOrNull("email_verified_at"),
                passwordHash = rs.getString("password_hash"),
                language = rs.getString("language"),
                createdAt = rs.getInstant("created_at"),
                saveRoutesSince = rs.getInstantOrNull("save_routes_since"),
                role = UserRole.valueOf(rs.getString("role")),
            )
        }
    }
}
