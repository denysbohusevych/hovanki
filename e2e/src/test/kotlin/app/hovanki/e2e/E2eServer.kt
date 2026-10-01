package app.hovanki.e2e

import app.hovanki.e2e.scenario.Scenario
import app.hovanki.e2e.scenario.runScenario
import app.hovanki.server.HovankiServerApplication
import app.hovanki.server.testing.TestPostgres
import org.junit.jupiter.api.Assumptions
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Set: the scenarios play against this server (started with the `e2e` profile) instead of one in this JVM. */
private val externalServerUrl: String? =
    System.getenv("HOVANKI_E2E_SERVER_URL")?.takeIf { it.isNotBlank() }?.trimEnd('/')

/**
 * The server the scenarios play against: `HOVANKI_E2E_SERVER_URL` if set (a server started with the `e2e` profile),
 * otherwise the real Spring Boot app started once in this JVM on a random port, with the `e2e` profile, on a fresh
 * PostgreSQL database ([TestPostgres]: `HOVANKI_TEST_DATABASE_URL` or an embedded server) dropped when the JVM exits.
 */
object E2eServer {
    val url: String by lazy { externalServerUrl ?: startInProcess() }

    private fun startInProcess(): String {
        val database = TestPostgres.start()
        val context = try {
            startServer(database, port = 0)
        } catch (e: Throwable) {
            database.close()
            throw e
        }
        Runtime.getRuntime().addShutdownHook(
            Thread {
                context.close()
                database.close()
            },
        )
        return "http://127.0.0.1:${context.port()}"
    }
}

/**
 * A server of one scenario's own, for what would disturb the others on [E2eServer]: a restart, games deleted within
 * seconds, rate limits on. The same app with the `e2e` profile and [properties] on top, on a fresh [TestPostgres]
 * database, in this JVM. [restart] keeps the port and the database, like a real server restart: accounts stay, the
 * games (in memory) are gone.
 */
class DedicatedServer(private val properties: Map<String, String> = emptyMap()) : AutoCloseable {
    private val database = TestPostgres.start()
    private var context: ConfigurableApplicationContext? = try {
        startServer(database, port = 0, properties)
    } catch (e: Throwable) {
        database.close()
        throw e
    }
    private val port = checkNotNull(context).port()

    val url = "http://127.0.0.1:$port"

    /** A bean of the running server (its meters, for a scenario that measures it). */
    fun <T : Any> bean(type: Class<T>): T = checkNotNull(context) { "The server is stopped" }.getBean(type)

    /** Stops the server; the port stays free for [start]. */
    fun stop() {
        context?.close()
        context = null
    }

    fun start() {
        check(context == null) { "The server is running" }
        context = startServer(database, port, properties)
    }

    fun restart() {
        stop()
        start()
    }

    override fun close() {
        stop()
        database.close()
    }
}

/** The `@ResourceLock` of the tests with a [DedicatedServer]. */
const val OWN_SERVER = "own-server"

/** A scenario against [E2eServer]. */
fun scenario(name: String, timeout: Duration = 3.minutes, block: suspend Scenario.() -> Unit) =
    runScenario(name, E2eServer.url, timeout, block)

/**
 * A scenario on a [DedicatedServer] with [properties] (Spring properties on top of the `e2e` profile), which the
 * scenario may restart. Skipped against an external server (`HOVANKI_E2E_SERVER_URL`): it can't be reconfigured.
 * Tests using it take the [OWN_SERVER] lock: one extra server at a time next to the shared one, for the memory.
 */
fun scenarioOnOwnServer(
    name: String,
    properties: Map<String, String>,
    timeout: Duration = 3.minutes,
    block: suspend Scenario.(DedicatedServer) -> Unit,
) {
    Assumptions.assumeTrue(externalServerUrl == null, "needs a server of its own, not HOVANKI_E2E_SERVER_URL")
    DedicatedServer(properties).use { server -> runScenario(name, server.url, timeout) { block(server) } }
}

/**
 * Spring contexts start one at a time: two starting at once in one JVM (the shared server's lazy start next to a
 * [DedicatedServer]) race in Logback's property map (a ConcurrentModificationException in LoggingApplicationListener).
 */
private val serverStart = Any()

/** The app with the `e2e` profile on [database] and [port] (0: a free one), [properties] on top. */
private fun startServer(
    database: TestPostgres,
    port: Int,
    properties: Map<String, String> = emptyMap(),
): ConfigurableApplicationContext = synchronized(serverStart) {
    // As command line arguments: they win over application.yaml, unlike the builder's default properties.
    val settings = database.springProperties() + properties +
        mapOf("server.port" to port.toString(), "spring.main.banner-mode" to "off")
    SpringApplicationBuilder(HovankiServerApplication::class.java)
        .profiles("e2e")
        .run(*settings.map { (key, value) -> "--$key=$value" }.toTypedArray())
}

private fun ConfigurableApplicationContext.port(): Int =
    checkNotNull(environment.getProperty("local.server.port")) { "Server port unknown" }.toInt()
