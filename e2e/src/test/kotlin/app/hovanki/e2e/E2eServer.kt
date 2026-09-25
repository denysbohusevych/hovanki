package app.hovanki.e2e

import app.hovanki.e2e.scenario.Scenario
import app.hovanki.e2e.scenario.runScenario
import app.hovanki.server.HovankiServerApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The server the scenarios play against: `HOVANKI_E2E_SERVER_URL` if set (a server started with the `e2e` profile),
 * otherwise the real Spring Boot app started once in this JVM on a random port, with the `e2e` profile.
 */
object E2eServer {
    val url: String by lazy {
        System.getenv("HOVANKI_E2E_SERVER_URL")?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: startInProcess()
    }

    private fun startInProcess(): String {
        val context = SpringApplicationBuilder(HovankiServerApplication::class.java)
            .profiles("e2e")
            .properties("server.port=0", "spring.main.banner-mode=off")
            .run()
        Runtime.getRuntime().addShutdownHook(Thread { context.close() })
        val port = checkNotNull(context.environment.getProperty("local.server.port")) { "Server port unknown" }
        return "http://127.0.0.1:$port"
    }
}

/** A scenario against [E2eServer]. */
fun scenario(name: String, timeout: Duration = 3.minutes, block: suspend Scenario.() -> Unit) =
    runScenario(name, E2eServer.url, timeout, block)
