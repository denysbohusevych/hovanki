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
 * every part of every item of the keyed sections, with the items' ids in order. Never the content: the server keeps
 * these per live session and the phone keeps the states themselves.
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

    /** A keyed section: the ids in order and [parts] hashes per item, item after item. */
    internal class Keyed(val ids: Array<String>, val hashes: LongArray, val parts: Int) {
        fun sameAs(other: Keyed): Boolean = ids.contentEquals(other.ids) && hashes.contentEquals(other.hashes)

        private var index: HashMap<String, Int>? = null

        /** The item's position, or -1. Looks at [hint] first: the same id usually keeps its place. */
        fun indexOf(id: String, hint: Int): Int {
            if (hint < ids.size && ids[hint] == id) return hint
            val byId =
                index
                    ?: HashMap<String, Int>(ids.size * 2).also { map -> ids.forEachIndexed { i, key -> map[key] = i } }
            index = byId
            return byId[id] ?: -1
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
    fun between(current: State, base: LiveDigest?): LiveDeltaResult {
        val whole = LongArray(WHOLE_SECTIONS)
        var changed = false

        fun <T : Any> section(slot: Int, value: T?, adapter: ProtoAdapter<T>): T? {
            if (value == null) return null
            val bytes = adapter.encode(value)
            val hash = XxHash64.hash(bytes)
            whole[slot] = hash
            val send = if (base == null) bytes.isNotEmpty() else base.whole[slot] != hash
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

        val players = players(current.players?.upsert.orEmpty(), base?.players)
        val catches =
            keyed(current.catches?.upsert.orEmpty(), base?.catches, CatchItem::id, CatchItem.ADAPTER)
        val radar =
            keyed(current.radar?.upsert.orEmpty(), base?.radar, Contact::player_id, Contact.ADAPTER)
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
            digest = LiveDigest(whole, players.digest, catches.digest, radar.digest),
            isEmpty = isEmpty,
        )
    }

    /** The changes of one keyed section (null: none) and its new digest. */
    private class KeyedResult<T>(val changes: Changes<T>?, val digest: LiveDigest.Keyed)

    private class Changes<T>(val upsert: List<T>, val remove: List<String>, val order: Keys?)

    private fun players(items: List<PlayerItem>, base: LiveDigest.Keyed?): KeyedResult<PlayerItem> {
        val ids = Array(items.size) { items[it].id }
        val hashes = LongArray(items.size * PLAYER_PARTS)
        val upsert = ArrayList<PlayerItem>()
        items.forEachIndexed { i, item ->
            val roster = Roster.ADAPTER.encode(item.roster ?: NO_ROSTER)
            val presence = Presence.ADAPTER.encode(item.presence ?: CONNECTED)
            val location = LocationPart.ADAPTER.encode(item.location ?: NOT_SEEN)
            val at = i * PLAYER_PARTS
            hashes[at] = XxHash64.hash(roster)
            hashes[at + 1] = XxHash64.hash(presence)
            hashes[at + 2] = XxHash64.hash(location)
            val was = base?.indexOf(item.id, i) ?: -1
            if (was < 0) {
                // New to the phone: an absent part is its zero, so only the roster always comes.
                upsert +=
                    PlayerItem(
                        id = item.id,
                        roster = item.roster ?: NO_ROSTER,
                        presence = item.presence.takeIf { presence.isNotEmpty() },
                        location = item.location.takeIf { location.isNotEmpty() },
                    )
            } else {
                val before = was * PLAYER_PARTS
                val rosterChanged = base!!.hashes[before] != hashes[at]
                val presenceChanged = base.hashes[before + 1] != hashes[at + 1]
                val locationChanged = base.hashes[before + 2] != hashes[at + 2]
                if (rosterChanged || presenceChanged || locationChanged) {
                    upsert +=
                        PlayerItem(
                            id = item.id,
                            roster = (item.roster ?: NO_ROSTER).takeIf { rosterChanged },
                            presence = (item.presence ?: CONNECTED).takeIf { presenceChanged },
                            location = (item.location ?: NOT_SEEN).takeIf { locationChanged },
                        )
                }
            }
        }
        val digest = LiveDigest.Keyed(ids, hashes, PLAYER_PARTS)
        return KeyedResult(changes(ids, upsert, base), digest)
    }

    private inline fun <T : Any> keyed(
        items: List<T>,
        base: LiveDigest.Keyed?,
        key: (T) -> String,
        adapter: ProtoAdapter<T>,
    ): KeyedResult<T> {
        val ids = Array(items.size) { key(items[it]) }
        val hashes = LongArray(items.size)
        val upsert = ArrayList<T>()
        items.forEachIndexed { i, item ->
            hashes[i] = XxHash64.hash(adapter.encode(item))
            val was = base?.indexOf(ids[i], i) ?: -1
            if (was < 0 || base!!.hashes[was] != hashes[i]) upsert += item
        }
        return KeyedResult(changes(ids, upsert, base), LiveDigest.Keyed(ids, hashes, 1))
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
    private const val PLAYER_PARTS = 3

    private val NO_ROSTER = Roster()
    private val CONNECTED = Presence()
    private val NOT_SEEN = LocationPart()
}
