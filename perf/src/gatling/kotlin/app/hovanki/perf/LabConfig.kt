package app.hovanki.perf

/**
 * The run's settings, from the Gradle properties `-Pperf.<name>=<value>` (perf/build.gradle.kts hands every `perf.*`
 * property to the simulation as a system property). What each simulation reads is in its own documentation.
 */
object LabConfig {
    /** The server under load: perf/server.sh or perf/up.sh --container, both with the profiles `e2e,perf`. */
    val baseUrl: String = string("baseUrl", "http://localhost:8080").trimEnd('/')

    fun string(name: String, default: String): String = System.getProperty("perf.$name")?.ifBlank { null } ?: default

    fun int(name: String, default: Int): Int = System.getProperty("perf.$name")?.ifBlank { null }?.let {
        requireNotNull(it.toIntOrNull()) { "-Pperf.$name must be a whole number, not '$it'" }
    } ?: default
}
