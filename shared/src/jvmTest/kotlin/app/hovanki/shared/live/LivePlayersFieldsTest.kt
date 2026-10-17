package app.hovanki.shared.live

import app.hovanki.shared.live.v2.Roster
import app.hovanki.shared.protocol.PlayerView
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [LivePlayers] keeps a roster for a player while every field it is built from stays the same
 * ([LivePlayers.sameRoster]): a new field of [PlayerView] or of the roster must be compared there too, else a viewer
 * gets an old roster. These lists fail first.
 */
class LivePlayersFieldsTest {
    @Test
    fun aNewPlayerViewFieldIsInTheRosterOrNot() {
        assertEquals(
            ROSTER_SOURCES + NOT_IN_THE_ROSTER,
            fields(PlayerView::class.java),
            "A new PlayerView field: if the roster carries it, compare it in LivePlayers.sameRoster",
        )
    }

    @Test
    fun aNewRosterFieldIsCompared() {
        assertEquals(
            setOf("name", "role", "status", "user_id", "out_at_ms", "caught_by", "left", "caps", "sparks"),
            fields(Roster::class.java),
            "A new roster field: compare the PlayerView field it comes from in LivePlayers.sameRoster",
        )
    }

    private fun fields(type: Class<*>): Set<String> =
        type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()

    private companion object {
        /** What [LivePlayers.sameRoster] compares, besides the id. */
        val ROSTER_SOURCES =
            setOf("name", "role", "status", "userId", "outAtMillis", "caughtBy", "left", "capabilities", "sparks")

        /** The slot's player, and what a viewer may see of them apart from the roster: the item's other parts. */
        val NOT_IN_THE_ROSTER = setOf("id", "location", "lastSeenMillis")
    }
}
