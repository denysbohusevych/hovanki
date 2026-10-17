package app.hovanki.shared.live

import app.hovanki.shared.live.v2.BoardSection
import app.hovanki.shared.live.v2.CatchItem
import app.hovanki.shared.live.v2.CatchesSection
import app.hovanki.shared.live.v2.Contact
import app.hovanki.shared.live.v2.CountsSection
import app.hovanki.shared.live.v2.GameSection
import app.hovanki.shared.live.v2.Keys
import app.hovanki.shared.live.v2.LocationPart
import app.hovanki.shared.live.v2.MeSection
import app.hovanki.shared.live.v2.PerksSection
import app.hovanki.shared.live.v2.PlayerItem
import app.hovanki.shared.live.v2.PlayersSection
import app.hovanki.shared.live.v2.Presence
import app.hovanki.shared.live.v2.QuestsSection
import app.hovanki.shared.live.v2.RadarSection
import app.hovanki.shared.live.v2.Roster
import app.hovanki.shared.live.v2.SettingsSection
import app.hovanki.shared.live.v2.SosSection
import app.hovanki.shared.live.v2.State
import app.hovanki.shared.live.v2.TokensSection
import app.hovanki.shared.live.v2.UwbSection
import com.squareup.wire.ProtoAdapter

/**
 * The fingerprints of one state the server sent (docs/live-v2/spec.md §8.2): an xxHash64 of every whole section, and of
 * the parts of every item of the keyed sections, with the items' ids in order. Never the content: the server keeps
 * these per live session and the phone keeps the states themselves. Arrays equal to those of the digest a delta was
 * made against are that digest's own: the states a session keeps share what did not change (server.md §3.4).
 */
class LiveDigest internal constructor(
    internal val whole: LongArray,
    internal val players: Keyed,
    internal val catches: Keyed,
    internal val radar: Keyed,
) {
    /** The same state: every section, item and order alike (as far as 64-bit hashes tell). */
    fun sameAs(other: LiveDigest): Boolean = whole.contentEquals(other.whole) &&
        players.sameAs(other.players) &&
        catches.sameAs(other.catches) &&
        radar.sameAs(other.radar)

    /**
     * A keyed section: the ids in order and a hash per item; the players' [hashes] are their rosters and presences,
     * and [locations] their locations, apart: a state where only some players moved shares the first with the last.
     */
    internal class Keyed(val ids: Array<String>, val hashes: LongArray, val locations: LongArray = NONE) {
        fun sameAs(other: Keyed): Boolean = ids.contentEquals(other.ids) &&
            hashes.contentEquals(other.hashes) &&
            locations.contentEquals(other.locations)

        /** The item's position, or -1. Looks near [hint] first: an id usually keeps its place, or moves by one. */
        fun indexOf(id: String, hint: Int, positions: () -> Map<String, Int>): Int {
            if (at(hint, id)) return hint
            if (at(hint + 1, id)) return hint + 1
            if (at(hint - 1, id)) return hint - 1
            return positions()[id] ?: -1
        }

        private fun at(index: Int, id: String): Boolean = index in ids.indices && ids[index] == id

        /** The positions of the ids, for [indexOf]: made by the delta that needs them and not kept. */
        fun positions(): Map<String, Int> =
            HashMap<String, Int>(ids.size * 2).also { map -> ids.forEachIndexed { i, key -> map[key] = i } }

        internal companion object {
            val NONE = LongArray(0)
        }
    }
}

/** What [LiveDelta.between] gives: the sections to send, the digest of the state they make, and whether it is empty. */
class LiveDeltaResult(
    val state: State,
    val digest: LiveDigest,
    /** Nothing differs from the base: no section in [state]. */
    val isEmpty: Boolean,
)

/**
 * The server's delta (docs/live-v2/spec.md §8.2, §8.4, §8.5): what of a whole state differs from a state the phone has.
 *
 * A full state (no base) carries every section that is not at its zero, every item, and the order of every keyed
 * section; the phone applies it to [LiveStates.EMPTY].
 */
object LiveDelta {
    /**
     * [current] against [base] (null: a full state). The new digest takes its arrays from [base] or [last] (the state
     * sent last) where they are equal, so the states kept do not each hold a copy.
     */
    fun between(current: State, base: LiveDigest?, last: LiveDigest? = null): LiveDeltaResult {
        val hasher = LiveHasher()
        val whole = LongArray(WHOLE_SECTIONS)
        var changed = false

        fun <T : Any> section(slot: Int, value: T?, adapter: ProtoAdapter<T>): T? {
            if (value == null) return null
            val hash = hasher.hash(adapter, value)
            whole[slot] = hash
            val send = if (base == null) hasher.size > 0 else base.whole[slot] != hash
            if (send) changed = true
            return value.takeIf { send }
        }

        val game = section(GAME, current.game, GameSection.ADAPTER)
        val settings = section(SETTINGS, current.settings, SettingsSection.ADAPTER)
        val me = section(ME, current.me, MeSection.ADAPTER)
        val tokens = section(TOKENS, current.tokens, TokensSection.ADAPTER)
        val uwb = section(UWB, current.uwb, UwbSection.ADAPTER)
        val sos = section(SOS, current.sos, SosSection.ADAPTER)
        val board = section(BOARD, current.board, BoardSection.ADAPTER)
        val quests = section(QUESTS, current.quests, QuestsSection.ADAPTER)
        val perks = section(PERKS, current.perks, PerksSection.ADAPTER)
        val counts = section(COUNTS, current.counts, CountsSection.ADAPTER)

        val players = players(current.players?.upsert.orEmpty(), base?.players, last?.players, hasher)
        val catches = keyed(
            current.catches?.upsert.orEmpty(),
            base?.catches,
            last?.catches,
            hasher,
            CatchItem::id,
            CatchItem.ADAPTER,
        )
        val radar = keyed(
            current.radar?.upsert.orEmpty(),
            base?.radar,
            last?.radar,
            hasher,
            Contact::player_id,
            Contact.ADAPTER,
        )
        val delta =
            State(
                game = game,
                settings = settings,
                players = players.changes?.let {
                    PlayersSection(upsert = it.upsert, remove = it.remove, order = it.order)
                },
                me = me,
                catches = catches.changes?.let {
                    CatchesSection(upsert = it.upsert, remove = it.remove, order = it.order)
                },
                radar = radar.changes?.let { RadarSection(upsert = it.upsert, remove = it.remove, order = it.order) },
                tokens = tokens,
                uwb = uwb,
                sos = sos,
                board = board,
                quests = quests,
                perks = perks,
                counts = counts,
            )
        val isEmpty = !changed && players.changes == null && catches.changes == null && radar.changes == null
        return LiveDeltaResult(
            state = delta,
            digest = LiveDigest(shared(whole, base?.whole, last?.whole), players.digest, catches.digest, radar.digest),
            isEmpty = isEmpty,
        )
    }

    /** The changes of one keyed section (null: none) and its new digest. */
    private class KeyedResult<T>(val changes: Changes<T>?, val digest: LiveDigest.Keyed)

    private class Changes<T>(val upsert: List<T>, val remove: List<String>, val order: Keys?)

    /**
     * A player's hashes: the roster and the presence together (both change rarely: either one changed, both go), and
     * apart the location, which changes every few seconds (spec §8.5).
     */
    private fun players(
        items: List<PlayerItem>,
        base: LiveDigest.Keyed?,
        last: LiveDigest.Keyed?,
        hasher: LiveHasher,
    ): KeyedResult<PlayerItem> {
        val ids = Array(items.size) { items[it].id }
        val hashes = LongArray(items.size)
        val locations = LongArray(items.size)
        val upsert = ArrayList<PlayerItem>()
        var positions: Map<String, Int>? = null
        val lookup = { positions ?: base!!.positions().also { positions = it } }
        items.forEachIndexed { i, item ->
            val roster = hasher.hash(Roster.ADAPTER, item.roster ?: NO_ROSTER)
            val presence = hasher.hash(Presence.ADAPTER, item.presence ?: CONNECTED)
            val hasPresence = hasher.size > 0
            hashes[i] = roster * MIX + presence
            locations[i] = hasher.hash(LocationPart.ADAPTER, item.location ?: NOT_SEEN)
            val hasLocation = hasher.size > 0
            val was = base?.indexOf(item.id, i, lookup) ?: -1
            if (was < 0) {
                // New to the phone: an absent part is its zero, so only the roster always comes.
                upsert +=
                    PlayerItem(
                        id = item.id,
                        roster = item.roster ?: NO_ROSTER,
                        presence = item.presence.takeIf { hasPresence },
                        location = item.location.takeIf { hasLocation },
                    )
            } else {
                val rosterChanged = base!!.hashes[was] != hashes[i]
                val locationChanged = base.locations[was] != locations[i]
                if (rosterChanged || locationChanged) {
                    upsert +=
                        PlayerItem(
                            id = item.id,
                            roster = (item.roster ?: NO_ROSTER).takeIf { rosterChanged },
                            presence = (item.presence ?: CONNECTED).takeIf { rosterChanged },
                            location = (item.location ?: NOT_SEEN).takeIf { locationChanged },
                        )
                }
            }
        }
        return KeyedResult(changes(ids, upsert, base), keyed(ids, hashes, locations, base, last))
    }

    private inline fun <T : Any> keyed(
        items: List<T>,
        base: LiveDigest.Keyed?,
        last: LiveDigest.Keyed?,
        hasher: LiveHasher,
        key: (T) -> String,
        adapter: ProtoAdapter<T>,
    ): KeyedResult<T> {
        val ids = Array(items.size) { key(items[it]) }
        val hashes = LongArray(items.size)
        val upsert = ArrayList<T>()
        var positions: Map<String, Int>? = null
        val lookup = { positions ?: base!!.positions().also { positions = it } }
        items.forEachIndexed { i, item ->
            hashes[i] = hasher.hash(adapter, item)
            val was = base?.indexOf(ids[i], i, lookup) ?: -1
            if (was < 0 || base!!.hashes[was] != hashes[i]) upsert += item
        }
        return KeyedResult(changes(ids, upsert, base), keyed(ids, hashes, LiveDigest.Keyed.NONE, base, last))
    }

    /** A keyed section's digest, its arrays taken from [base] or [last] where equal; an empty one is shared. */
    private fun keyed(
        ids: Array<String>,
        hashes: LongArray,
        locations: LongArray,
        base: LiveDigest.Keyed?,
        last: LiveDigest.Keyed?,
    ): LiveDigest.Keyed {
        if (ids.isEmpty()) return EMPTY_KEYED
        val fresh = LiveDigest.Keyed(ids, hashes, locations)
        if (base != null && base.sameAs(fresh)) return base
        if (last != null && last.sameAs(fresh)) return last
        return LiveDigest.Keyed(
            shared(ids, base?.ids, last?.ids),
            shared(hashes, base?.hashes, last?.hashes),
            if (locations.isEmpty()) LiveDigest.Keyed.NONE else shared(locations, base?.locations, last?.locations),
        )
    }

    private fun shared(fresh: LongArray, base: LongArray?, last: LongArray?): LongArray = when {
        base != null && base.contentEquals(fresh) -> base
        last != null && last.contentEquals(fresh) -> last
        else -> fresh
    }

    private fun shared(fresh: Array<String>, base: Array<String>?, last: Array<String>?): Array<String> = when {
        base != null && base.contentEquals(fresh) -> base
        last != null && last.contentEquals(fresh) -> last
        else -> fresh
    }

    /**
     * The removals and the order. The order comes in a full state, and whenever the phone would get it wrong by itself
     * (its rule: the old order without the removed ids, new ids at the end).
     */
    private fun <T> changes(ids: Array<String>, upsert: List<T>, base: LiveDigest.Keyed?): Changes<T>? {
        if (base == null) return Changes(upsert, emptyList(), Keys(ids = ids.asList()))
        if (base.ids.contentEquals(ids)) return if (upsert.isEmpty()) null else Changes(upsert, emptyList(), null)
        val current = HashSet<String>(ids.size * 2).apply { addAll(ids) }
        val remove = base.ids.filter { it !in current }
        val kept = base.ids.filter { it in current }
        val known = kept.toHashSet()
        val expected = kept + ids.filter { it !in known }
        val order = if (expected == ids.asList()) null else Keys(ids = ids.asList())
        if (upsert.isEmpty() && remove.isEmpty() && order == null) return null
        return Changes(upsert, remove, order)
    }

    private const val GAME = 0
    private const val SETTINGS = 1
    private const val ME = 2
    private const val TOKENS = 3
    private const val UWB = 4
    private const val SOS = 5
    private const val BOARD = 6
    private const val QUESTS = 7
    private const val PERKS = 8
    private const val COUNTS = 9
    private const val WHOLE_SECTIONS = 10

    /** Mixes the roster's hash into the presence's: xxHash64's odd prime. */
    private const val MIX = -7046029288634856825L

    private val EMPTY_KEYED = LiveDigest.Keyed(emptyArray(), LiveDigest.Keyed.NONE)

    private val NO_ROSTER = Roster()
    private val CONNECTED = Presence()
    private val NOT_SEEN = LocationPart()
}
