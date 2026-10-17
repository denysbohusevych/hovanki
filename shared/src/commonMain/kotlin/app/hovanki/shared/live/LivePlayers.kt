package app.hovanki.shared.live

import app.hovanki.shared.live.v2.LocationPart
import app.hovanki.shared.live.v2.PlayerItem
import app.hovanki.shared.live.v2.Presence
import app.hovanki.shared.live.v2.Roster
import app.hovanki.shared.protocol.PlayerView
import kotlin.concurrent.Volatile

/**
 * What every viewer of a game sees alike of its players (docs/live-v2/server.md §3.6): a player's roster, and their
 * whole item while the viewer sees nothing more of them (connected, no position). The server gives the sessions of one
 * game the same one ([LiveJson.Game.players]), so [LiveSections] builds these once per change of the player, not once
 * per viewer and sync, and [LiveDelta] hashes such a roster once too. The same values, so the same bytes and hashes.
 *
 * One slot per player, by their place in the snapshot's list. Safe to share between threads without a lock: a slot is
 * one immutable object, used only for the same player with every roster field the same; a lost or stale slot is a
 * miss, and a miss only builds anew.
 */
class LivePlayers {
    internal class Kept(val view: PlayerView, val roster: Roster) {
        /** The player's whole item for a viewer who sees nothing more of them. */
        val unseen = PlayerItem(id = view.id.value, roster = roster, presence = CONNECTED, location = NOT_SEEN)

        /** The roster's hash, as [LiveHasher] makes it: [LiveDelta] takes it rather than hash the roster per viewer. */
        val rosterHash = XxHash64.hash(Roster.ADAPTER.encode(roster))
    }

    @Volatile
    private var slots: Array<Kept?> = arrayOfNulls(0)

    /** [player], at [index] of the snapshot's players: their item as [LiveSections.player] builds it. */
    fun item(index: Int, player: PlayerView, now: Long): PlayerItem {
        val kept = kept(index, player)
        val presence = LiveSections.presence(player.lastSeenMillis, now)
        val location = player.location?.let { LocationPart(value_ = LiveSections.location(it)) } ?: NOT_SEEN
        if (presence === CONNECTED && location === NOT_SEEN) return kept.unseen
        return PlayerItem(id = player.id.value, roster = kept.roster, presence = presence, location = location)
    }

    /** What is kept at [index] now, for [LiveDelta]: its [Kept.roster] tells whether it is the item's. */
    internal fun keptAt(index: Int): Kept? = slots.getOrNull(index)

    private fun kept(index: Int, player: PlayerView): Kept {
        var slots = this.slots
        val known = slots.getOrNull(index)
        if (known != null && sameRoster(known.view, player)) return known
        val kept = Kept(player, LiveSections.roster(player))
        if (index >= slots.size) {
            slots = slots.copyOf(maxOf(index + 1, slots.size * 2))
            this.slots = slots
        }
        slots[index] = kept
        return kept
    }

    internal companion object {
        val CONNECTED: Presence = LiveSections.presence(null, 0)
        val NOT_SEEN = LocationPart()

        /** The same player with every field [LiveSections.roster] reads the same; checked by `LivePlayersFieldsTest`. */
        fun sameRoster(a: PlayerView, b: PlayerView): Boolean = a.id == b.id &&
            a.name == b.name &&
            a.role == b.role &&
            a.status == b.status &&
            a.userId == b.userId &&
            a.outAtMillis == b.outAtMillis &&
            a.caughtBy == b.caughtBy &&
            a.left == b.left &&
            a.capabilities == b.capabilities &&
            a.sparks == b.sparks
    }
}
