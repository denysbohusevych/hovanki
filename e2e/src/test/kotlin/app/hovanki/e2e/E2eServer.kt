package app.hovanki.e2e

import app.hovanki.e2e.scenario.Scenario
import app.hovanki.e2e.scenario.runScenario
import app.hovanki.server.HovankiServerApplication
import app.hovanki.server.testing.TestPostgres
import org.springframework.boot.builder.SpringApplicationBuilder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The server the scenarios play against: `HOVANKI_E2E_SERVER_URL` if set (a server started with the `e2e` profile),
 * otherwise the real Spring Boot app started once in this JVM on a random port, with the `e2e` profile, on a fresh
 * PostgreSQL database ([TestPostgres]: `HOVANKI_TEST_DATABASE_URL` or an embedded server) dropped when the JVM exits.
 */
object E2eServer {
    val url: String by lazy {
        System.getenv("HOVANKI_E2E_SERVER_URL")?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: startInProcess()
    }

    private fun startInProcess(): String {
        val database = TestPostgres.start()
        // As command line arguments: they win over application.yaml, unlike the builder's default properties.
        val settings = database.springProperties() + mapOf("server.port" to "0", "spring.main.banner-mode" to "off")
        val context = try {
            SpringApplicationBuilder(HovankiServerApplication::class.java)
                .profiles("e2e")
                .run(*settings.map { (key, value) -> "--$key=$value" }.toTypedArray())
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
        val port = checkNotNull(context.environment.getProperty("local.server.port")) { "Server port unknown" }
        return "http://127.0.0.1:$port"
    }
}

/** A scenario against [E2eServer]. */
fun scenario(name: String, timeout: Duration = 3.minutes, block: suspend Scenario.() -> Unit) =
    runScenario(name, E2eServer.url, timeout, block)
