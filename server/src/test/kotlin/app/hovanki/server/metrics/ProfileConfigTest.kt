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
    }

    @Test
    fun stagingChangesNothingOfTheE2eProfile() {
        // The observer endpoints, recorded emails and no rate limits are the e2e profile's alone: staging has real
        // players and real mail, and only these three groups of settings differ from production.
        val staging = load("application-staging.yaml")
        val allowed = listOf("hovanki.game.", "management.", "logging.")
        val others = staging.propertyNames.filterNot { name -> allowed.any(name::startsWith) }
        assertTrue(others.isEmpty(), "staging sets more than its limits, management and logs: $others")
    }
}
