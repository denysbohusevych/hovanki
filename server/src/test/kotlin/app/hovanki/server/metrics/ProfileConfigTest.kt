package app.hovanki.server.metrics

import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.ClassPathResource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The settings files themselves, read as they are (no Spring context, so the production port 8081 is not taken): what
 * the internet may reach on a production server and on staging (docs/adr/0018-field-test-build.md, section 2).
 */
class ProfileConfigTest {
    private fun load(file: String): EnumerablePropertySource<*> =
        YamlPropertySourceLoader().load(file, ClassPathResource(file)).single() as EnumerablePropertySource<*>

    private fun EnumerablePropertySource<*>.text(key: String): String? = getProperty(key)?.toString()

    @Test
    fun productionExposesNoMetricsAndKeepsTheManagementOnTheGamesPort() {
        val production = load("application.yaml")

        assertNull(production.text("management.server.port"), "the health stays where Caddy and the docs expect it")
        val exposed = production.text("management.endpoints.web.exposure.include").orEmpty().split(",")
        assertFalse("prometheus" in exposed, "metrics are not exposed on production: $exposed")
        assertContains(exposed, "health")
        assertContains(exposed, "restarthold")
        assertNull(production.text("hovanki.game.max-players"), "the default is Game.MAX_PLAYERS")
        assertNull(production.text("logging.structured.format.console"), "production keeps its plain log lines")
    }

    @Test
    fun stagingServesMetricsOnAPortOfTheirOwn() {
        val staging = load("application-staging.yaml")

        val port = assertNotNull(staging.text("management.server.port")).toInt()
        assertNotEquals(8080, port)
        val exposed = assertNotNull(staging.text("management.endpoints.web.exposure.include")).split(",")
        assertContains(exposed, "prometheus")
        assertContains(exposed, "restarthold")
        assertEquals("60", staging.text("hovanki.game.max-players"))
        assertEquals("ecs", staging.text("logging.structured.format.console"))
        // The field build's lab screen is for staff: only they join a lab run on staging (production: anybody).
        assertEquals("true", staging.text("hovanki.lab.join-staff-only"))
        assertEquals("false", load("application.yaml").text("hovanki.lab.join-staff-only"))
    }

    @Test
    fun stagingChangesNothingOfTheE2eProfile() {
        // The observer endpoints, recorded emails, no rate limits, the test admin key and the fake map are the e2e
        // profile's alone: staging has real players and real mail. The test is against the e2e file itself, so a setting
        // that file gets later is covered too, and a later step may add its own staging settings.
        val staging = load("application-staging.yaml")
        val e2e = load("application-e2e.yaml")
        val overlap = staging.propertyNames.filter { name ->
            e2e.propertyNames.any { other -> name == other || name.startsWith("$other.") || other.startsWith("$name.") }
        }
        assertTrue(overlap.isEmpty(), "staging sets what the e2e profile sets: $overlap")
        assertTrue(
            staging.propertyNames.none { it.startsWith("spring.profiles") },
            "staging turns no other profile on (the e2e one in particular)",
        )
    }
}
