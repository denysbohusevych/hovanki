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

/**
 * Whole states and how a delta applies to one (docs/live-v2/spec.md §8.3–8.5).
 *
 * A whole state has every section; a keyed section lists every item in `upsert`, in v1's order, and has no `remove`
 * or `order`; a player has all three parts. [LiveSections.fromSnapshot] makes one on the server, [apply] keeps one on
 * the phone.
 */
object LiveStates {
    /** The state before the first Down: every section at its zero. A full state (base 0) applies to it. */
    val EMPTY: State =
        State(
            game = GameSection(),
            settings = SettingsSection(),
            players = PlayersSection(),
            me = MeSection(),
            catches = CatchesSection(),
            radar = RadarSection(),
            tokens = TokensSection(),
            uwb = UwbSection(),
            sos = SosSection(),
            board = BoardSection(),
            quests = QuestsSection(),
            perks = PerksSection(),
            counts = CountsSection(),
        )

    /**
     * [base] (whole) plus the sections of [delta]: a whole section present replaces the old one, absent is unchanged;
     * keyed sections merge by id. Null: the delta does not fit this base (its `order` names an id the state does not
     * have, or misses one): the phone drops the Down and asks for a full state.
     */
    fun apply(base: State, delta: State): State? {
        val players =
            delta.players?.let { section ->
                PlayersSection(
                    upsert =
                    merge(
                        items = base.players?.upsert.orEmpty(),
                        upsert = section.upsert,
                        remove = section.remove,
                        order = section.order,
                        key = PlayerItem::id,
                        combine = ::mergePlayer,
                    ) ?: return null,
                )
            } ?: base.players
        val catches =
            delta.catches?.let { section ->
                CatchesSection(
                    upsert =
                    merge(
                        items = base.catches?.upsert.orEmpty(),
                        upsert = section.upsert,
                        remove = section.remove,
                        order = section.order,
                        key = CatchItem::id,
                        combine = { _, new -> new },
                    ) ?: return null,
                )
            } ?: base.catches
        val radar =
            delta.radar?.let { section ->
                RadarSection(
                    upsert =
                    merge(
                        items = base.radar?.upsert.orEmpty(),
                        upsert = section.upsert,
                        remove = section.remove,
                        order = section.order,
                        key = Contact::player_id,
                        combine = { _, new -> new },
                    ) ?: return null,
                )
            } ?: base.radar
        return State(
            game = delta.game ?: base.game,
            settings = delta.settings ?: base.settings,
            players = players,
            me = delta.me ?: base.me,
            catches = catches,
            radar = radar,
            tokens = delta.tokens ?: base.tokens,
            uwb = delta.uwb ?: base.uwb,
            sos = delta.sos ?: base.sos,
            board = delta.board ?: base.board,
            quests = delta.quests ?: base.quests,
            perks = delta.perks ?: base.perks,
            counts = delta.counts ?: base.counts,
        )
    }

    /** A new player: an absent part is its zero; a known one: only the parts that came are replaced. */
    private fun mergePlayer(old: PlayerItem?, new: PlayerItem): PlayerItem = if (old == null) {
        PlayerItem(
            id = new.id,
            roster = new.roster ?: NO_ROSTER,
            presence = new.presence ?: CONNECTED,
            location = new.location ?: NOT_SEEN,
        )
    } else {
        PlayerItem(
            id = old.id,
            roster = new.roster ?: old.roster,
            presence = new.presence ?: old.presence,
            location = new.location ?: old.location,
        )
    }

    private inline fun <T> merge(
        items: List<T>,
        upsert: List<T>,
        remove: List<String>,
        order: Keys?,
        key: (T) -> String,
        combine: (T?, T) -> T,
    ): List<T>? {
        val byId = LinkedHashMap<String, T>(items.size + upsert.size)
        for (item in items) byId[key(item)] = item
        for (id in remove) byId.remove(id)
        for (item in upsert) {
            val id = key(item)
            byId[id] = combine(byId[id], item)
        }
        if (order == null) return byId.values.toList()
        if (order.ids.size != byId.size) return null
        val ordered = ArrayList<T>(order.ids.size)
        for (id in order.ids) ordered += byId.remove(id) ?: return null
        return ordered
    }

    private val NO_ROSTER = Roster()
    private val CONNECTED = Presence()
    private val NOT_SEEN = LocationPart()
}
