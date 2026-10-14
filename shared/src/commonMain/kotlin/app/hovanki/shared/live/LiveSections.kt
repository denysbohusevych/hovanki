package app.hovanki.shared.live

import app.hovanki.shared.live.v2.BoardSection
import app.hovanki.shared.live.v2.Caps
import app.hovanki.shared.live.v2.CatchItem
import app.hovanki.shared.live.v2.CatchesSection
import app.hovanki.shared.live.v2.Contact
import app.hovanki.shared.live.v2.CountsSection
import app.hovanki.shared.live.v2.GameSection
import app.hovanki.shared.live.v2.Location
import app.hovanki.shared.live.v2.LocationPart
import app.hovanki.shared.live.v2.MeSection
import app.hovanki.shared.live.v2.Pause
import app.hovanki.shared.live.v2.PerksSection
import app.hovanki.shared.live.v2.PlayerItem
import app.hovanki.shared.live.v2.PlayersSection
import app.hovanki.shared.live.v2.Presence
import app.hovanki.shared.live.v2.QuestsSection
import app.hovanki.shared.live.v2.RadarSection
import app.hovanki.shared.live.v2.Roster
import app.hovanki.shared.live.v2.SettingsSection
import app.hovanki.shared.live.v2.SosCall
import app.hovanki.shared.live.v2.SosLocation
import app.hovanki.shared.live.v2.SosSection
import app.hovanki.shared.live.v2.State
import app.hovanki.shared.live.v2.TokensSection
import app.hovanki.shared.live.v2.UwbPeer
import app.hovanki.shared.live.v2.UwbSection
import app.hovanki.shared.protocol.Capabilities
import app.hovanki.shared.protocol.CatchView
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.RadarContact
import app.hovanki.shared.protocol.VisibleLocation
import app.hovanki.shared.live.v2.Hint as WireHint
import app.hovanki.shared.protocol.Hint as V1Hint
import app.hovanki.shared.protocol.SosCall as V1SosCall
import app.hovanki.shared.protocol.UwbPeer as V1UwbPeer

/**
 * The server's side of v2.0: the state of a live session cut from the v1 snapshot of the same player
 * (docs/live-v2/spec.md §9), so the phone gets exactly what v1 would give it, by construction.
 *
 * The result is a whole state ([LiveStates]): every section present, keyed sections with every item, in v1's order.
 */
object LiveSections {
    fun fromSnapshot(snapshot: GameSnapshot, json: LiveJson = LiveJson()): State {
        val now = snapshot.serverTimeMillis
        return State(
            game = game(snapshot, json),
            settings = SettingsSection(json = json.settings.encode(snapshot.settings)),
            players = PlayersSection(upsert = snapshot.players.map { player(it, now) }),
            me = me(snapshot.me),
            catches = CatchesSection(upsert = snapshot.catches.map(::catchItem)),
            radar = RadarSection(upsert = snapshot.me.radar?.contacts.orEmpty().map(::contact)),
            tokens =
            TokensSection(
                seeker = snapshot.me.seekerTokens.mapNotNull(LiveValues::tokenToInt),
                hider = snapshot.me.hiderTokens.mapNotNull(LiveValues::tokenToInt),
            ),
            uwb = UwbSection(peers = snapshot.me.uwbPeers.map(::uwbPeer)),
            sos = SosSection(calls = snapshot.sos.map(::sosCall)),
            board = BoardSection(
                items_json = if (snapshot.items.isEmpty()) EMPTY else json.items.encode(snapshot.items),
            ),
            quests = QuestsSection(
                quests_json = if (snapshot.quests.isEmpty()) EMPTY else json.quests.encode(snapshot.quests),
            ),
            perks = PerksSection(
                perks_json = if (snapshot.me.perks.isEmpty()) EMPTY else json.perks.encode(snapshot.me.perks),
            ),
            counts = counts(snapshot),
        )
    }

    private fun game(snapshot: GameSnapshot, json: LiveJson): GameSection = GameSection(
        game_id = snapshot.gameId.value,
        join_code = snapshot.joinCode,
        host_id = snapshot.hostId.value,
        phase = LiveEnums.wire(snapshot.phase),
        phase_ends_at_ms = snapshot.phaseEndsAtMillis,
        zone_started_at_ms = snapshot.zoneStartedAtMillis,
        finished_at_ms = snapshot.finishedAtMillis,
        map_revision = snapshot.mapRevision,
        roles_drawn_at_ms = snapshot.rolesDrawnAtMillis,
        buildings = LiveEnums.wire(snapshot.buildings),
        street_zone = LiveEnums.wire(snapshot.streetZone),
        enabled_features = snapshot.enabledFeatures,
        spectators = snapshot.spectators,
        pause = snapshot.pause?.let { Pause(since_ms = it.sinceMillis, sos = it.sos) },
        play_again_code = LiveValues.orEmpty(snapshot.playAgain?.joinCode),
        capacity_json = json.capacity.encode(snapshot.capacity),
        big_game_json = json.bigGame.encode(snapshot.bigGame),
    )

    /** A player whole: [now] decides whether their presence says «connected» (docs/live-v2/spec.md §9.1). */
    fun player(player: PlayerView, now: Long): PlayerItem = PlayerItem(
        id = player.id.value,
        roster = roster(player),
        presence = presence(player.lastSeenMillis, now),
        location = LocationPart(value_ = player.location?.let(::location)),
    )

    private fun roster(player: PlayerView): Roster = Roster(
        name = player.name,
        role = LiveEnums.wire(player.role),
        status = LiveEnums.wire(player.status),
        user_id = LiveValues.orEmpty(player.userId?.value),
        out_at_ms = player.outAtMillis,
        caught_by = LiveValues.orEmpty(player.caughtBy?.value),
        left = player.left,
        caps = player.capabilities?.let(::caps),
        sparks = player.sparks,
    )

    private fun caps(capabilities: Capabilities): Caps = Caps(
        platform = LiveEnums.wire(capabilities.platform),
        bluetooth = LiveEnums.wire(capabilities.bluetooth),
        uwb = capabilities.uwb,
        off_screen = !capabilities.onScreen,
        activity_sensor = capabilities.activitySensor,
    )

    /** Silent for longer than [LiveLimits.OFFLINE_AFTER_MILLIS]: the real time; else nothing (connected). */
    fun presence(lastSeenMillis: Long?, now: Long): Presence =
        if (lastSeenMillis != null && now - lastSeenMillis > LiveLimits.OFFLINE_AFTER_MILLIS) {
            Presence(last_seen_ms = lastSeenMillis)
        } else {
            CONNECTED
        }

    private fun location(location: VisibleLocation): Location = Location(
        lat_e7 = LiveValues.toE7(location.point.lat),
        lon_e7 = LiveValues.toE7(location.point.lon),
        accuracy_dm = LiveValues.toDecimetres(location.accuracyMeters),
        at_ms = location.atMillis,
        reason = LiveEnums.wire(location.reason),
        cause = location.cause?.let(LiveEnums::wire),
    )

    private fun me(me: MyState): MeSection = MeSection(
        player_id = me.playerId.value,
        role = LiveEnums.wire(me.role),
        status = LiveEnums.wire(me.status),
        catch_code_secret = LiveValues.secretToBytes(me.catchCodeSecret),
        out_of_zone_deadline_ms = me.outOfZoneDeadlineMillis,
        inside_building_reveal_at_ms = me.insideBuildingRevealAtMillis,
        radar_secret = LiveValues.secretToBytes(me.radarSecret),
        bluetooth_deadline_ms = me.bluetoothDeadlineMillis,
        sparks = me.sparks,
        hint = me.hint?.let(::hint),
        radar_on = me.radar != null,
    )

    private fun hint(hint: V1Hint): WireHint = WireHint(
        kind = LiveEnums.wire(hint.kind),
        until_ms = hint.untilMillis,
        sector = hint.sector,
        band = hint.band?.let(LiveEnums::wire),
    )

    private fun catchItem(catch: CatchView): CatchItem = CatchItem(
        id = catch.id.value,
        seeker_id = catch.seekerId.value,
        hider_id = catch.hiderId.value,
        status = LiveEnums.wire(catch.status),
        created_at_ms = catch.createdAtMillis,
        deadline_ms = catch.deadlineMillis,
        can_vote = catch.canVote,
        my_vote = catch.myVote,
    )

    private fun contact(contact: RadarContact): Contact = Contact(
        player_id = LiveValues.orEmpty(contact.playerId?.value),
        band = LiveEnums.wire(contact.band),
        at_ms = contact.atMillis,
    )

    private fun uwbPeer(peer: V1UwbPeer): UwbPeer =
        UwbPeer(player_id = peer.playerId.value, token = peer.token, platform = LiveEnums.wire(peer.platform))

    private fun sosCall(call: V1SosCall): SosCall = SosCall(
        player_id = call.playerId.value,
        name = call.name,
        since_ms = call.sinceMillis,
        location =
        call.location?.let {
            SosLocation(
                lat_e7 = LiveValues.toE7(it.point.lat),
                lon_e7 = LiveValues.toE7(it.point.lon),
                accuracy_dm = LiveValues.toDecimetres(it.accuracyMeters),
                at_ms = it.atMillis,
            )
        },
    )

    private fun counts(snapshot: GameSnapshot): CountsSection = snapshot.counts?.let {
        CountsSection(
            shown = true,
            players = it.players,
            seekers = it.seekers,
            hiders_active = it.hidersActive,
            hiders_caught = it.hidersCaught,
            hiders_eliminated = it.hidersEliminated,
        )
    } ?: CountsSection()

    private val EMPTY = okio.ByteString.EMPTY
    private val CONNECTED = Presence()
}
