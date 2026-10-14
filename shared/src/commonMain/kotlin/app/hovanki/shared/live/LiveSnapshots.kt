package app.hovanki.shared.live

import app.hovanki.shared.live.v2.CatchItem
import app.hovanki.shared.live.v2.Contact
import app.hovanki.shared.live.v2.CountsSection
import app.hovanki.shared.live.v2.GameSection
import app.hovanki.shared.live.v2.Location
import app.hovanki.shared.live.v2.MeSection
import app.hovanki.shared.live.v2.PlayerItem
import app.hovanki.shared.live.v2.State
import app.hovanki.shared.protocol.Capabilities
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchView
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePause
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.PlayAgain
import app.hovanki.shared.protocol.PlayerCounts
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.RadarContact
import app.hovanki.shared.protocol.RadarState
import app.hovanki.shared.protocol.SosLocation
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.VisibleLocation
import app.hovanki.shared.live.v2.Hint as WireHint
import app.hovanki.shared.live.v2.SosCall as WireSosCall
import app.hovanki.shared.live.v2.UwbPeer as WireUwbPeer
import app.hovanki.shared.protocol.Hint as V1Hint
import app.hovanki.shared.protocol.SosCall as V1SosCall
import app.hovanki.shared.protocol.UwbPeer as V1UwbPeer

/**
 * The phone's side: the same [GameSnapshot] v1 gives, put together from a whole state of the live session
 * (docs/live-v2/spec.md §9). The screens and `GameSessionManager` see no difference.
 *
 * One per live connection: [json] keeps the last decoded settings, board and quests, so an unchanged section is not
 * decoded again.
 */
class LiveSnapshots(private val json: LiveJson = LiveJson()) {
    /**
     * [state] is whole ([LiveStates]); [serverTimeMillis] is the Down's `server_time_ms` (it also stands for the last
     * time seen of every connected player); [chat] is the Down's chat.
     */
    fun toGameSnapshot(state: State, serverTimeMillis: Long, chat: List<ChatMessage> = emptyList()): GameSnapshot {
        val game = state.game ?: GameSection()
        val me = state.me ?: MeSection()
        return GameSnapshot(
            gameId = GameId(game.game_id),
            joinCode = game.join_code,
            hostId = PlayerId(game.host_id),
            phase = LiveEnums.v1(game.phase),
            settings = json.settings.decode(state.settings?.json ?: okio.ByteString.EMPTY) ?: error("no settings"),
            serverTimeMillis = serverTimeMillis,
            phaseEndsAtMillis = game.phase_ends_at_ms,
            zoneStartedAtMillis = game.zone_started_at_ms,
            players = state.players?.upsert.orEmpty().map { player(it, serverTimeMillis) },
            me = myState(state, me),
            catches = state.catches?.upsert.orEmpty().map(::catchView),
            buildings = LiveEnums.v1(game.buildings),
            chat = chat,
            finishedAtMillis = game.finished_at_ms,
            streetZone = LiveEnums.v1(game.street_zone),
            mapRevision = game.map_revision,
            rolesDrawnAtMillis = game.roles_drawn_at_ms,
            enabledFeatures = game.enabled_features,
            items = state.board?.items_json?.let(json.items::decode).orEmpty(),
            quests = state.quests?.quests_json?.let(json.quests::decode).orEmpty(),
            capacity = json.capacity.decode(game.capacity_json),
            bigGame = json.bigGame.decode(game.big_game_json),
            counts = counts(state.counts),
            spectators = game.spectators,
            pause = game.pause?.let { GamePause(sinceMillis = it.since_ms, sos = it.sos) },
            sos = state.sos?.calls.orEmpty().map(::sosCall),
            playAgain = LiveValues.nullIfEmpty(game.play_again_code)?.let(::PlayAgain),
        )
    }

    private fun player(item: PlayerItem, serverTimeMillis: Long): PlayerView {
        val roster = item.roster ?: app.hovanki.shared.live.v2.Roster()
        return PlayerView(
            id = PlayerId(item.id),
            name = roster.name,
            role = LiveEnums.v1(roster.role),
            status = LiveEnums.v1(roster.status),
            location = item.location?.value_?.let(::visibleLocation),
            userId = LiveValues.nullIfEmpty(roster.user_id)?.let(::UserId),
            outAtMillis = roster.out_at_ms,
            caughtBy = LiveValues.nullIfEmpty(roster.caught_by)?.let(::PlayerId),
            lastSeenMillis = item.presence?.last_seen_ms ?: serverTimeMillis,
            left = roster.left,
            capabilities =
            roster.caps?.let {
                Capabilities(
                    platform = LiveEnums.v1(it.platform),
                    bluetooth = LiveEnums.v1(it.bluetooth),
                    uwb = it.uwb,
                    onScreen = !it.off_screen,
                    activitySensor = it.activity_sensor,
                )
            },
            sparks = roster.sparks,
        )
    }

    private fun visibleLocation(location: Location): VisibleLocation = VisibleLocation(
        point = GeoPoint(LiveValues.fromE7(location.lat_e7), LiveValues.fromE7(location.lon_e7)),
        accuracyMeters = LiveValues.fromDecimetres(location.accuracy_dm),
        atMillis = location.at_ms,
        reason = LiveEnums.v1(location.reason),
        cause = location.cause?.let(LiveEnums::v1),
    )

    private fun myState(state: State, me: MeSection): MyState = MyState(
        playerId = PlayerId(me.player_id),
        role = LiveEnums.v1(me.role),
        status = LiveEnums.v1(me.status),
        catchCodeSecret = LiveValues.secretFromBytes(me.catch_code_secret),
        outOfZoneDeadlineMillis = me.out_of_zone_deadline_ms,
        insideBuildingRevealAtMillis = me.inside_building_reveal_at_ms,
        radarSecret = LiveValues.secretFromBytes(me.radar_secret),
        radar = if (me.radar_on) RadarState(state.radar?.upsert.orEmpty().map(::radarContact)) else null,
        seekerTokens = state.tokens?.seeker.orEmpty().map(LiveValues::tokenFromInt),
        hiderTokens = state.tokens?.hider.orEmpty().map(LiveValues::tokenFromInt),
        bluetoothDeadlineMillis = me.bluetooth_deadline_ms,
        uwbPeers = state.uwb?.peers.orEmpty().map(::uwbPeer),
        sparks = me.sparks,
        hint = me.hint?.let(::hint),
        perks = state.perks?.perks_json?.let(json.perks::decode).orEmpty(),
    )

    private fun hint(hint: WireHint): V1Hint = V1Hint(
        kind = LiveEnums.v1(hint.kind),
        untilMillis = hint.until_ms,
        sector = hint.sector,
        band = hint.band?.let(LiveEnums::v1),
    )

    private fun radarContact(contact: Contact): RadarContact = RadarContact(
        band = LiveEnums.v1(contact.band),
        playerId = LiveValues.nullIfEmpty(contact.player_id)?.let(::PlayerId),
        atMillis = contact.at_ms,
    )

    private fun catchView(item: CatchItem): CatchView = CatchView(
        id = CatchId(item.id),
        seekerId = PlayerId(item.seeker_id),
        hiderId = PlayerId(item.hider_id),
        status = LiveEnums.v1(item.status),
        createdAtMillis = item.created_at_ms,
        deadlineMillis = item.deadline_ms,
        canVote = item.can_vote,
        myVote = item.my_vote,
    )

    private fun uwbPeer(peer: WireUwbPeer): V1UwbPeer =
        V1UwbPeer(playerId = PlayerId(peer.player_id), token = peer.token, platform = LiveEnums.v1(peer.platform))

    private fun sosCall(call: WireSosCall): V1SosCall = V1SosCall(
        playerId = PlayerId(call.player_id),
        name = call.name,
        sinceMillis = call.since_ms,
        location =
        call.location?.let {
            SosLocation(
                point = GeoPoint(LiveValues.fromE7(it.lat_e7), LiveValues.fromE7(it.lon_e7)),
                accuracyMeters = LiveValues.fromDecimetres(it.accuracy_dm),
                atMillis = it.at_ms,
            )
        },
    )

    private fun counts(counts: CountsSection?): PlayerCounts? = counts?.takeIf { it.shown }?.let {
        PlayerCounts(
            players = it.players,
            seekers = it.seekers,
            hidersActive = it.hiders_active,
            hidersCaught = it.hiders_caught,
            hidersEliminated = it.hiders_eliminated,
        )
    }
}
