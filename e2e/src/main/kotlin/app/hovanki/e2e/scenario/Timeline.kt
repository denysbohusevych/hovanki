package app.hovanki.e2e.scenario

import java.util.Locale

/** What happened during a scenario, in order: actions, observed state changes, checks. Thread-safe. */
class Timeline(val startedAtMillis: Long = System.currentTimeMillis()) {
    class Entry(val atMillis: Long, val actor: String, val text: String)

    private val entries = mutableListOf<Entry>()

    fun log(actor: String, text: String) {
        val entry = Entry(System.currentTimeMillis(), actor, text)
        synchronized(entries) { entries += entry }
    }

    fun entries(): List<Entry> = synchronized(entries) { entries.toList() }

    /** One line per entry: `+12.3s  Sam          claims a catch on Anna`. */
    fun render(): String {
        val all = entries()
        val width = all.maxOfOrNull { it.actor.length }?.coerceAtMost(MAX_ACTOR_WIDTH) ?: 0
        return all.joinToString("\n") { entry ->
            val seconds = (entry.atMillis - startedAtMillis) / 1000.0
            String.format(Locale.ROOT, "+%6.1fs  %-${width}s  %s", seconds, entry.actor, entry.text)
        }
    }

    private companion object {
        const val MAX_ACTOR_WIDTH = 16
    }
}
