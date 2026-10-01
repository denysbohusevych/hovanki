package app.hovanki.server.game

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject

/**
 * One event of the server for a game's field log (docs/adr/0018-field-test-build.md §3.3): at [atMillis] on the
 * server's clock, of kind [kind] (`app.hovanki.shared.lab.ServerKinds`) with its [fields]. Players by id, distances in
 * meters, never a position or a nickname.
 */
class FieldEvent(val atMillis: Long, val kind: String, val fields: JsonObject) {
    override fun toString(): String = "FieldEvent($kind at $atMillis)"
}

/**
 * The field events a [Game] collected since they were last taken, the way it collects its [Pokes]: under the game's
 * lock, handed to the [app.hovanki.server.lab.FieldEventWriter] after it. Only a field game collects any
 * ([Game.fieldLog]); at most [MAX] between two takes, the rest is counted as [dropped].
 */
class FieldEvents {
    private val events = ArrayList<FieldEvent>()

    /** Events not kept since the last take: more than [MAX] of them. */
    var dropped: Int = 0
        private set

    val isEmpty: Boolean get() = events.isEmpty() && dropped == 0

    val list: List<FieldEvent> get() = events

    fun add(atMillis: Long, kind: String, fields: JsonObjectBuilder.() -> Unit) {
        if (events.size >= MAX) {
            dropped++
            return
        }
        events += FieldEvent(atMillis, kind, buildJsonObject(fields))
    }

    companion object {
        /** A take comes after every request: this many in one is a game gone wild, not a game. */
        const val MAX = 5_000
    }
}
