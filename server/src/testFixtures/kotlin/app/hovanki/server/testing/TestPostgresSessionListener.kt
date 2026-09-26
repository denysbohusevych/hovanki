package app.hovanki.server.testing

import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener

/**
 * One [TestPostgres] per test JVM: started before the first test, its `spring.datasource.*` set as system properties
 * (so every `@SpringBootTest` uses it), dropped after the last one. Registered for the server's tests in
 * `src/test/resources/META-INF/services/org.junit.platform.launcher.LauncherSessionListener`; another module registers
 * it the same way. A `spring.datasource.url` given as a system property wins: then nothing is started.
 */
class TestPostgresSessionListener : LauncherSessionListener {
    private var postgres: TestPostgres? = null
    private var ownProperties: Set<String> = emptySet()

    override fun launcherSessionOpened(session: LauncherSession) {
        if (System.getProperty(URL_PROPERTY) != null) return
        val started = TestPostgres.start()
        started.springProperties().forEach(System::setProperty)
        ownProperties = started.springProperties().keys
        postgres = started
    }

    override fun launcherSessionClosed(session: LauncherSession) {
        val started = postgres ?: return
        postgres = null
        ownProperties.forEach(System::clearProperty)
        started.close()
    }

    private companion object {
        const val URL_PROPERTY = "spring.datasource.url"
    }
}
