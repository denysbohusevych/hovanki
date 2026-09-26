package app.hovanki.e2e.devices

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.net.URLDecoder
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The database of the server jar that the device runs start ([LocalServer]): a fresh, empty PostgreSQL database per
 * run, like the tests' `TestPostgres` (the server's test fixtures, which this command line can't use without pulling in
 * the whole server):
 * - with [DATABASE_URL_ENV] set, e.g. `jdbc:postgresql://localhost:5432/hovanki?user=hovanki&password=hovanki` (the
 *   user needs CREATEDB), a new database `hovanki_e2e_<random>` on that server, dropped on [close];
 * - otherwise an embedded PostgreSQL (zonky, binaries from Maven Central, no Docker) on a free port, stopped on [close].
 *
 * The server's Flyway creates the schema at its start.
 */
class LocalPostgres private constructor(
    /** Without credentials: they are [username] and [password]. */
    val jdbcUrl: String,
    val username: String,
    val password: String,
    /** For the log: where the database is, without credentials. */
    val description: String,
    private val onClose: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    /** `spring.datasource.*` for the server process, as environment variables: no password in the process list. */
    fun serverEnvironment(): Map<String, String> = mapOf(
        "SPRING_DATASOURCE_URL" to jdbcUrl,
        "SPRING_DATASOURCE_USERNAME" to username,
        "SPRING_DATASOURCE_PASSWORD" to password,
    )

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }

    companion object {
        const val DATABASE_URL_ENV = "HOVANKI_TEST_DATABASE_URL"
        private const val NAME_PREFIX = "hovanki_e2e_"

        /** A new database on the server of [serverUrl] if given, else an embedded server. */
        fun start(serverUrl: String? = System.getenv(DATABASE_URL_ENV)?.takeIf { it.isNotBlank() }): LocalPostgres =
            if (serverUrl != null) onServer(serverUrl) else embedded()

        private fun onServer(serverUrl: String): LocalPostgres {
            val server = ServerUrl.parse(serverUrl)
            val name = NAME_PREFIX + HexFormat.of().formatHex(ByteArray(6).also(SecureRandom()::nextBytes))
            server.execute("CREATE DATABASE $name")
            return LocalPostgres(server.withDatabase(name), server.user, server.password, "database $name") {
                // FORCE: the server may still hold pooled connections if it was killed.
                server.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)")
            }
        }

        private fun embedded(): LocalPostgres {
            val postgres = try {
                EmbeddedPostgres.builder().start()
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Could not start the embedded PostgreSQL. Point $DATABASE_URL_ENV at a PostgreSQL server " +
                        "instead, e.g. jdbc:postgresql://localhost:5432/postgres?user=postgres&password=postgres",
                    e,
                )
            }
            val url = "jdbc:postgresql://localhost:${postgres.port}/postgres"
            return LocalPostgres(url, "postgres", "", "embedded, port ${postgres.port}", postgres::close)
        }
    }

    /** `jdbc:postgresql://host:port/database?user=...&password=...&other=...`, split into its parts. */
    internal class ServerUrl(
        /** Up to the database name. */
        val base: String,
        /** The parameters other than the credentials: empty or `?name=value&...`. */
        val query: String,
        val user: String,
        val password: String,
    ) {
        /** The same server, another database. */
        fun withDatabase(name: String): String = base.substringBeforeLast('/') + "/" + name + query

        /** Runs [sql] in the URL's own database (the JDBC driver comes with embedded-postgres). */
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
