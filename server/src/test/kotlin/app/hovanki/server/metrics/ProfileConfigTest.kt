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
 * the internet may reach on a production server and on staging (docs/adr/0018-field-test-build.md, section 2), and
 * what a field test's server changes (the profile `field`: the main server on the test's days, and staging always).
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
        assertEquals("ecs", staging.text("logging.structured.format.console"))
    }

    @Test
    fun stagingIsAFieldTestsServerAlways() {
        // The group lives in application.yaml: a profile's own file may not define groups.
        val production = load("application.yaml")
        val group = production.propertyNames.filter { it.startsWith("spring.profiles.group.") }
            .associateWith { production.text(it) }
        assertEquals(mapOf("spring.profiles.group.staging" to "field"), group)
    }

    @Test
    fun theFieldProfileKeepsProductionsPortsAndLogs() {
        // The main server runs `field` on the test's days: its health stays on the game's port, where Caddy,
        // deploy/hovanki-update.sh and the docs expect it, and nothing new reaches the internet.
        val field = load("application-field.yaml")
        assertTrue(
            field.propertyNames.none { it.startsWith("management.") || it.startsWith("logging.") },
            "the field profile changes ports or logs: ${field.propertyNames.toList()}",
        )
        assertEquals("60", field.text("hovanki.game.max-players"))
        // The field build's lab screen is for staff: only they join a lab run there (without the profile: anybody).
        assertEquals("true", field.text("hovanki.lab.join-staff-only"))
        assertEquals("false", load("application.yaml").text("hovanki.lab.join-staff-only"))
    }

    @Test
    fun onlyTheFieldProfileAndE2eHaveTheFieldLog() {
        // Without the profile `field` no server keeps a field log, whatever the admin's switch says
        // (docs/adr/0018-field-test-build.md §9); staging gets it through its group.
        assertEquals("false", load("application.yaml").text("hovanki.field.allowed"))
        assertEquals("true", load("application-field.yaml").text("hovanki.field.allowed"))
        assertNull(load("application-staging.yaml").text("hovanki.field.allowed"), "staging's comes from `field`")
        assertEquals("true", load("application-e2e.yaml").text("hovanki.field.allowed"))
    }

    @Test
    fun onlyTheFieldProfileKeepsTheProximityCatchAndThePocketStealthInTheShadow() {
        assertNull(
            load("application.yaml").text("hovanki.features.shadow-only"),
            "production: empty, a game's own rules",
        )
        assertNull(load("application-e2e.yaml").text("hovanki.features.shadow-only"))
        assertNull(load("application-staging.yaml").text("hovanki.features.shadow-only[0]"))
        val field = load("application-field.yaml")
        val shadow = field.propertyNames.filter { it.startsWith("hovanki.features.shadow-only") }
            .mapNotNull { field.text(it) }
        assertEquals(setOf("PROXIMITY_CATCH", "POCKET_STEALTH"), shadow.toSet())
    }

    @Test
    fun theFieldProfileLetsAGameFromBehindOneAddressAskTheClock() {
        // 60 field build phones on one Wi-Fi: 5 requests each at the join and every 5 minutes.
        val field = load("application-field.yaml")
        val production = load("application.yaml")
        assertTrue(assertNotNull(field.text("hovanki.rate-limits.time-per-ip.count")).toInt() >= 60 * 5 * 2)
        for (limit in listOf("time-per-ip", "login-per-ip", "register-per-ip")) {
            val key = "hovanki.rate-limits.$limit.count"
            val more = assertNotNull(field.text(key)).toInt()
            assertTrue(more > assertNotNull(production.text(key)).toInt(), "$limit with the field profile: $more")
        }
        assertNull(field.text("hovanki.rate-limits.enabled"), "the field profile keeps the rate limits on")
    }

    @Test
    fun stagingAndTheFieldProfileChangeNothingOfTheE2eProfile() {
        // The observer endpoints, recorded emails, no rate limits, the test admin key and the fake map are the e2e
        // profile's alone: a field test's server has real players and real mail. The test is against the e2e file
        // itself, so a setting that file gets later is covered too, and a later step may add its own settings.
        val e2e = load("application-e2e.yaml")
        for (file in listOf("application-staging.yaml", "application-field.yaml")) {
            val profile = load(file)
            val overlap = profile.propertyNames.filter { name ->
                name !in BOTH_TEST_SERVERS &&
                    e2e.propertyNames.any { other ->
                        name == other || name.startsWith("$other.") ||
                            other.startsWith("$name.")
                    }
            }
            assertTrue(overlap.isEmpty(), "$file sets what the e2e profile sets: $overlap")
            assertTrue(
                profile.propertyNames.none { it.startsWith("spring.profiles") },
                "$file turns no other profile on (the e2e one in particular)",
            )
        }
    }

    private companion object {
        /** What both a test server and an e2e server have, and production never: the field log. */
        val BOTH_TEST_SERVERS = setOf("hovanki.field.allowed")
    }
}
