package app.hovanki.perf

/**
 * The run's settings, from the Gradle properties `-Pperf.<name>=<value>` (perf/build.gradle.kts hands every `perf.*`
 * property to the simulation as a system property). What each simulation reads is in its own documentation.
 */
object LabConfig {
    /** The server under load: perf/server.sh or perf/up.sh --container, both with the profiles `e2e,perf`. */
    val baseUrl: String = string("baseUrl", "http://localhost:8080").trimEnd('/')

    /** How every simulation's players sync: `-Pperf.transport=poll` (the default) or `socket`. */
    val transport: Transport = string("transport", "poll").let { name ->
        requireNotNull(Transport.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }) {
            "-Pperf.transport must be poll or socket, not '$name'"
        }
    }

    fun string(name: String, default: String): String = System.getProperty("perf.$name")?.ifBlank { null } ?: default

    fun int(name: String, default: Int): Int = System.getProperty("perf.$name")?.ifBlank { null }?.let {
        requireNotNull(it.toIntOrNull()) { "-Pperf.$name must be a whole number, not '$it'" }
    } ?: default
}

/** How a player syncs, as the app does it (docs/adr/0015-websockets.md, `AdaptiveGameConnection`). */
enum class Transport {
    /** `POST /sync` every `GameRules.syncIntervalSeconds`: the app while the server has `LIVE_SOCKET` off (production). */
    POLL,

    /** The game's live channel: one WebSocket per player, the same sync as a frame; the run turns `LIVE_SOCKET` on. */
    SOCKET,
}
