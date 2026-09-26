package app.hovanki.server.testing

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.net.URLDecoder
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A fresh, empty PostgreSQL database for tests: the server's own tests and `:e2e`.
 *
 * - With [DATABASE_URL_ENV] set, e.g. `jdbc:postgresql://localhost:5432/hovanki?user=hovanki&password=hovanki`
 *   (credentials as query parameters; the user needs CREATEDB): creates the database `hovanki_test_<random>` on that
 *   server and drops it on [close].
 * - Otherwise: starts an embedded PostgreSQL 17 (zonky, binaries from Maven Central, no Docker) on a free port and
 *   stops it on [close]. Under root, zonky runs PostgreSQL as `nobody` (PostgreSQL itself refuses root).
 *
 * Flyway creates the schema when the server starts on it. Usage: [springProperties] as system properties (see
 * [TestPostgresSessionListener]) or `SpringApplicationBuilder.properties(...)`.
 */
class TestPostgres private constructor(
    /** Without credentials: they are [username] and [password]. */
    val jdbcUrl: String,
    val username: String,
    val password: String,
    private val onClose: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    /** `spring.datasource.*` pointing at this database. */
    fun springProperties(): Map<String, String> = mapOf(
        "spring.datasource.url" to jdbcUrl,
        "spring.datasource.username" to username,
        "spring.datasource.password" to password,
    )

    /** Drops the database or stops the embedded server; open connections are closed by the server. */
    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }

    companion object {
        const val DATABASE_URL_ENV = "HOVANKI_TEST_DATABASE_URL"
        private const val NAME_PREFIX = "hovanki_test_"

        /** A new database on the server of [serverUrl] if given, else an embedded server. */
        fun start(serverUrl: String? = System.getenv(DATABASE_URL_ENV)?.takeIf { it.isNotBlank() }): TestPostgres =
            if (serverUrl != null) onServer(serverUrl) else embedded()

        private fun onServer(serverUrl: String): TestPostgres {
            val server = ServerUrl.parse(serverUrl)
            val name = NAME_PREFIX + HexFormat.of().formatHex(ByteArray(6).also(SecureRandom()::nextBytes))
            server.execute("CREATE DATABASE $name")
            return TestPostgres(server.withDatabase(name), server.user, server.password) {
                // FORCE (PostgreSQL 13+): Spring contexts still hold pooled connections when the tests end.
                server.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)")
            }
        }

        private fun embedded(): TestPostgres {
            val postgres = try {
                EmbeddedPostgres.builder().start()
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Could not start the embedded PostgreSQL. Point $DATABASE_URL_ENV " +
                        "at a PostgreSQL server instead, e.g. " +
                        "jdbc:postgresql://localhost:5432/postgres?user=postgres&password=postgres",
                    e,
                )
            }
            return TestPostgres(
                "jdbc:postgresql://localhost:${postgres.port}/postgres",
                "postgres",
                "",
                postgres::close,
            )
        }
    }

    /** `jdbc:postgresql://host:port/database?user=...&password=...&other=...`, split into its parts. */
    private class ServerUrl(
        /** Up to the database name. */
        private val base: String,
        /** The parameters other than the credentials: empty or `?name=value&...`. */
        private val query: String,
        val user: String,
        val password: String,
    ) {
        /** The same server, another database. */
        fun withDatabase(name: String): String = base.substringBeforeLast('/') + "/" + name + query

        /** Runs [sql] in the URL's own database. */
        fun execute(sql: String) {
            DriverManager.getConnection(base + query, user, password).use { connection ->
                connection.createStatement().use { it.execute(sql) }
            }
        }

        companion object {
            private val credentials = setOf("user", "password")

            fun parse(url: String): ServerUrl {
                require(url.startsWith("jdbc:postgresql://")) { "$DATABASE_URL_ENV must be a jdbc:postgresql:// URL" }
                val parameters = url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
                fun value(key: String) = parameters.firstOrNull { it.substringBefore('=') == key }
                    ?.substringAfter('=', "")
                    ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
                val others = parameters.filter { it.substringBefore('=') !in credentials }
                return ServerUrl(
                    base = url.substringBefore('?'),
                    query = if (others.isEmpty()) "" else others.joinToString("&", prefix = "?"),
                    user = value("user") ?: System.getProperty("user.name"),
                    password = value("password").orEmpty(),
                )
            }
        }
    }
}
