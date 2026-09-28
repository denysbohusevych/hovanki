package app.hovanki.server.game

import app.hovanki.shared.debug.DebugCatch
import app.hovanki.shared.debug.DebugFixCounts
import app.hovanki.shared.debug.DebugGameState
import app.hovanki.shared.debug.DebugPlayer
import app.hovanki.shared.debug.DebugVote
import app.hovanki.shared.geo.bearingTo
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.Activity
import app.hovanki.shared.protocol.AdminGame
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.BoardItem
import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.Capabilities
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.CatchView
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.CustomQuestRequest
import app.hovanki.shared.protocol.DeviceReport
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.FeatureMode
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.Hint
import app.hovanki.shared.protocol.HintKind
import app.hovanki.shared.protocol.ItemId
import app.hovanki.shared.protocol.ItemKind
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.NearbySighting
import app.hovanki.shared.protocol.Passage
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.PerkView
import app.hovanki.shared.protocol.PlaceItemRequest
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerTrack
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.QuestId
import app.hovanki.shared.protocol.QuestReviewRequest
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.protocol.RadarContact
import app.hovanki.shared.protocol.RadarState
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.StreetZoneResponse
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.TracksResponse
import app.hovanki.shared.protocol.UsePerkRequest
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UwbPeer
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.protocol.VisibleLocation
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.rules.BoardRules
import app.hovanki.shared.rules.BuildingMap
import app.hovanki.shared.rules.BuildingRules
import app.hovanki.shared.rules.CatchRules
import app.hovanki.shared.rules.ChatRules
import app.hovanki.shared.rules.Glow
import app.hovanki.shared.rules.LocationTrack
import app.hovanki.shared.rules.PerkCatalog
import app.hovanki.shared.rules.QuestCatalog
import app.hovanki.shared.rules.RouteRecorder
import app.hovanki.shared.rules.Sectors
import app.hovanki.shared.rules.StreetZone
import app.hovanki.shared.rules.ZoneRules
import app.hovanki.shared.rules.areaAt
import app.hovanki.shared.rules.circleAt
import app.hovanki.shared.rules.isUsable
import app.hovanki.shared.rules.stateAt
import app.hovanki.shared.totp.catchCodeTotp
import java.time.Duration

/**
 * One game and all of its rules. Pure domain object: no Spring, no threads, time is passed in,
 * so every rule can be unit-tested. Not thread-safe: [GameService] serializes access per game.
 *
 * Time-based transitions (phase timers, catch deadlines, zone checks) happen in [advance],
 * which the service calls before every request, so no background ticker is needed.
 */
class Game(
    val id: GameId,
    val joinCode: String,
    hostId: PlayerId,
    settings: GameSettings,
    private val createdAtMillis: Long,
) {
    /** Starts and sets up the game; when they leave the lobby, the player who joined after them takes over. */
    var hostId: PlayerId = hostId
        private set

    /** The setup; the host may change it in the lobby ([updateSettings]), the thresholds ([rules]) excepted. */
    var settings: GameSettings = settings
        private set
    private val rules = settings.rules
    private val players = LinkedHashMap<PlayerId, Player>()

    /** Players by the join request that created them (`JoinGameRequest.requestId`), see [playerOfJoinRequest]. */
    private val playersByJoinRequest = HashMap<String, PlayerId>()
    private val catches = LinkedHashMap<CatchId, CatchClaim>()

    /** The last [ChatRules.HISTORY_SIZE] chat messages, oldest first. */
    private val chat = ArrayDeque<ChatMessage>()
    private var lastChatSeq = 0L

    var phase: GamePhase = GamePhase.LOBBY
        private set
    private var phaseStartedAtMillis = createdAtMillis
    private var zoneStartedAtMillis: Long? = null

    /** Start of HIDING: the round (and the replay tracks and routes) begins here. */
    private var hidingStartedAtMillis: Long? = null
    private var finishedAtMillis: Long? = null
    private var lastActivityMillis = createdAtMillis

    /** The history of this game once it finished (docs/adr/0007-game-history-and-routes.md), see [finishedRecord]. */
    private var record: GameRecord? = null
    private var recordTaken = false

    /** The "no hiding in buildings" rule (docs/adr/0003-map-and-buildings.md): on once the outlines are loaded. */
    var buildingsState: BuildingsState = BuildingsState.LOADING
        private set
    private var buildings = BuildingsResponse()
    private var buildingMap: BuildingMap? = null

    /**
     * Goes up whenever the host changes the zone in the lobby: the buildings and the zone by streets are loaded again,
     * and whatever arrives for an older revision is dropped.
     */
    var mapRevision: Int = 0
        private set

    /** The zone by streets (docs/adr/0009-game-setup-glow-streets.md); null for a circle zone. */
    var streetZoneState: StreetZoneState? = null
        private set
    private var streetZone: StreetZone? = null

    /** Since when the zone by streets is being built: after [STREET_ZONE_PATIENCE_MILLIS] the game uses the circles. */
    private var streetZoneSinceMillis = createdAtMillis

    /** When the host last drew the roles at random ([drawRoles]). */
    private var rolesDrawnAtMillis: Long? = null

    /** The last glow that left its marks on the hiders ([updateGlow]); 0: none yet. */
    private var glowMarksOf = 0

    /** The radar (docs/adr/0010-nearby-radar.md): what the phones of the players hear of each other. */
    private val radar = Radar()

    /** The board (docs/adr/0011-quests-sparks-and-sensors.md): the host's items, the quests, the sparks. */
    private val board = Board(rules)
    private var questsStarted = false

    /** Ids of items and quests are unique within the game only; nothing secret about them. */
    private var nextItemNumber = 1
    private var nextQuestNumber = 1

    init {
        if (settings.zoneShape == ZoneShape.STREETS) streetZoneState = StreetZoneState.LOADING
    }

    /** The zone's buildings of [revision] arrived (see `BuildingLoader`): the rule is on from now on. */
    fun onBuildingsLoaded(areas: List<BuildingArea>, passages: List<Passage>, revision: Int = mapRevision) {
        if (revision != mapRevision) return
        buildingsState = BuildingsState.READY
        buildings = BuildingsResponse(BuildingsState.READY, areas, passages)
        buildingMap = BuildingMap(areas, passages, settings.zone.initial.center)
    }

    /** The zone's buildings can't be loaded: the game runs without the rule, and the players are told. */
    fun onBuildingsUnavailable(revision: Int = mapRevision) {
        if (revision != mapRevision) return
        buildingsState = BuildingsState.UNAVAILABLE
        buildings = BuildingsResponse(BuildingsState.UNAVAILABLE)
        buildingMap = null
    }

    /** The zone by streets of [revision] is built: one polygon for the start and one per stage of the schedule. */
    fun onStreetZoneBuilt(stages: List<ZonePolygon>, revision: Int = mapRevision) {
        if (revision != mapRevision || settings.zoneShape != ZoneShape.STREETS) return
        if (stages.size != settings.zone.stages.size + 1 || stages.any { it.outline.size < 4 }) {
            onStreetZoneUnavailable(revision)
            return
        }
        streetZone = StreetZone(stages)
        streetZoneState = StreetZoneState.READY
    }

    /**
     * No zone by streets for [revision] (no streets, map data down): the game uses the circles, the players are told.
     */
    fun onStreetZoneUnavailable(revision: Int = mapRevision) {
        if (revision != mapRevision || settings.zoneShape != ZoneShape.STREETS) return
        streetZone = null
        streetZoneState = StreetZoneState.UNAVAILABLE
    }

    /** The zone by streets for [viewerId] to draw exactly what the rules check. */
    fun streetZoneFor(viewerId: PlayerId): StreetZoneResponse {
        player(viewerId)
        return StreetZoneResponse(streetZoneState, mapRevision, streetZone?.let { zone -> zone.stages }.orEmpty())
    }

    /** The buildings the rule judges by, for [viewerId] to draw exactly those on the map. */
    fun buildingsFor(viewerId: PlayerId, nowMillis: Long): BuildingsResponse {
        val viewer = player(viewerId)
        if (buildingsState == BuildingsState.READY) viewer.buildingsLoadedAtMillis = nowMillis
        return buildings.copy(state = buildingsState, mapRevision = mapRevision)
    }

    /**
     * A new player, only in the lobby; [userId] is their account (null: a guest), at most one player per account.
     * [joinRequestId]: the app's id for the join request, see [playerOfJoinRequest].
     */
    fun addPlayer(id: PlayerId, name: String, nowMillis: Long, userId: UserId? = null, joinRequestId: String? = null) {
        requirePhase(GamePhase.LOBBY)
        if (players.size >= MAX_PLAYERS) throw GameException(ErrorCode.WRONG_STATE, "The game is full")
        if (userId != null && playerOf(userId) != null) {
            throw GameException(ErrorCode.WRONG_STATE, "This account already plays in this game")
        }
        // Only players with an account have a history; a guest's route is never even kept in memory.
        players[id] = Player(
            id = id,
            name = name,
            track = LocationTrack(rules),
            userId = userId,
            route = userId?.let { RouteRecorder(rules) },
            odometer = RouteRecorder(rules, keepPoints = false),
        )
        if (joinRequestId != null) playersByJoinRequest[joinRequestId] = id
        lastActivityMillis = nowMillis
    }

    /**
     * The player that join request [requestId] created, if any: the app sent the request again because the answer got
     * lost, and gets that player back instead of a second one.
     */
    fun playerOfJoinRequest(requestId: String): PlayerId? = playersByJoinRequest[requestId]

    /** The player of the account [userId] in this game, if it has one. */
    fun playerOf(userId: UserId): PlayerId? = players.values.firstOrNull { it.userId == userId }?.id

    /** The account of [playerId]; null for a guest. */
    fun userIdOf(playerId: PlayerId): UserId? = player(playerId).userId

    /**
     * The host picks the seekers in the lobby; everybody sees the roles ([PlayerView.role]), and the start takes them
     * as the app sends them. Any choice is fine here; the start needs at least one seeker and one hider.
     */
    fun setRoles(by: PlayerId, seekers: Set<PlayerId>, nowMillis: Long) {
        requirePhase(GamePhase.LOBBY)
        requireHost(by, "pick the roles")
        if (!players.keys.containsAll(seekers)) {
            throw GameException(ErrorCode.BAD_REQUEST, "Pick seekers among the players")
        }
        for (player in players.values) player.role = if (player.id in seekers) Role.SEEKER else Role.HIDER
        lastActivityMillis = nowMillis
    }

    /** [count] seekers drawn with [random] among the players; every phone rolls the dice for it. */
    fun drawRoles(by: PlayerId, count: Int, random: java.util.Random, nowMillis: Long) {
        requirePhase(GamePhase.LOBBY)
        requireHost(by, "draw the roles")
        if (players.size < 2) throw GameException(ErrorCode.WRONG_STATE, "A draw needs at least two players")
        if (count !in 1..<players.size) {
            throw GameException(ErrorCode.BAD_REQUEST, "Draw 1..${players.size - 1} seekers")
        }
        setRoles(by, players.keys.shuffled(random).take(count).toSet(), nowMillis)
        rolesDrawnAtMillis = nowMillis
    }

    /**
     * The host changes the setup in the lobby; the thresholds stay those the game was created with. True when the
     * zone changed: its map data has to be loaded again, for the new [mapRevision].
     */
    fun updateSettings(by: PlayerId, newSettings: GameSettings, nowMillis: Long): Boolean {
        requirePhase(GamePhase.LOBBY)
        requireHost(by, "change the settings")
        val next = newSettings.copy(rules = rules)
        val mapChanged = next.zone != settings.zone || next.zoneShape != settings.zoneShape
        settings = next
        // What the host placed for a feature that is off now goes with it.
        board.items.values.removeAll { !isAllowed(it.kind) }
        if (!next.features.quests) board.customQuests.clear()
        lastActivityMillis = nowMillis
        if (mapChanged) {
            mapRevision++
            buildingsState = BuildingsState.LOADING
            buildings = BuildingsResponse()
            buildingMap = null
            streetZone = null
            streetZoneState = if (next.zoneShape == ZoneShape.STREETS) StreetZoneState.LOADING else null
            streetZoneSinceMillis = nowMillis
        }
        return mapChanged
    }

    /**
     * [playerId] leaves for good. In the lobby they are gone, and a leaving host hands the game to the player who
     * joined after them. In a round a hider is out (caught, when a claim against them is open: leaving is no answer),
     * a seeker's open claims are dropped; the round ends when no hider or no seeker is left. True when the lobby is
     * empty now: the game is to be removed.
     */
    fun leave(playerId: PlayerId, nowMillis: Long): Boolean {
        val player = player(playerId)
        lastActivityMillis = nowMillis
        when (phase) {
            GamePhase.LOBBY -> {
                players.remove(playerId)
                playersByJoinRequest.values.removeAll { it == playerId }
                if (players.isEmpty()) return true
                if (hostId == playerId) hostId = players.keys.first()
            }

            GamePhase.HIDING, GamePhase.SEEKING -> {
                if (player.left) return false
                player.left = true
                if (player.role == Role.HIDER && player.status == PlayerStatus.ACTIVE) {
                    val open = catches.values.firstOrNull { it.isOpen && it.hiderId == playerId }
                    if (open != null) {
                        resolve(open, confirmed = true, nowMillis)
                    } else {
                        player.status = PlayerStatus.ELIMINATED
                        player.outAtMillis = nowMillis
                        player.outOfZoneSinceMillis = null
                        player.insideBuildingSinceMillis = null
                        board.onOut(player)
                        if (players.values.none { it.role == Role.HIDER && it.status == PlayerStatus.ACTIVE }) {
                            finish(nowMillis)
                        }
                    }
                } else if (player.role == Role.SEEKER) {
                    catches.values.filter { it.isOpen && it.seekerId == playerId }
                        .forEach { resolve(it, confirmed = false, nowMillis) }
                    if (players.values.none { it.role == Role.SEEKER && !it.left }) finish(nowMillis)
                }
            }

            GamePhase.FINISHED -> player.left = true
        }
        return false
    }

    /** Whether [playerId] still plays a round: an active hider or a seeker who has not left. */
    fun isPlaying(playerId: PlayerId): Boolean {
        val player = players[playerId] ?: return false
        if ((phase != GamePhase.HIDING && phase != GamePhase.SEEKING) || player.left) return false
        return player.role == Role.SEEKER || player.status == PlayerStatus.ACTIVE
    }

    fun start(by: PlayerId, seekers: Set<PlayerId>, newCatchCodeSecret: () -> String, nowMillis: Long) {
        requirePhase(GamePhase.LOBBY)
        requireHost(by, "start the game")
        if (settings.zoneShape == ZoneShape.STREETS && streetZoneState == StreetZoneState.LOADING) {
            throw GameException(ErrorCode.WRONG_STATE, "The zone by streets is being built", ErrorReason.ZONE_NOT_READY)
        }
        if (seekers.isEmpty() || !players.keys.containsAll(seekers)) {
            throw GameException(ErrorCode.BAD_REQUEST, "Pick at least one seeker among the players")
        }
        if (seekers.size == players.size) throw GameException(ErrorCode.BAD_REQUEST, "At least one hider is needed")
        val features = settings.features
        if (features.radar == FeatureMode.REQUIRED) {
            // Every phone reports what it can do with every sync; one that never did has no radar either.
            val without = players.values.filter { it.device?.bluetooth != BluetoothState.ON }
            if (without.isNotEmpty()) {
                throw GameException(
                    ErrorCode.WRONG_STATE,
                    "The radar is required, but it is off on: ${without.joinToString { it.name }}",
                    ErrorReason.FEATURE_MISSING,
                )
            }
        }

        for (player in players.values) {
            player.role = if (player.id in seekers) Role.SEEKER else Role.HIDER
            if (player.role == Role.HIDER) player.catchCodeSecret = newCatchCodeSecret()
            // The radar token's secret: as long and as random as the catch code's.
            if (features.hasRadar) player.radarSecret = newCatchCodeSecret()
        }
        enterPhase(GamePhase.HIDING, nowMillis)
        hidingStartedAtMillis = nowMillis
        lastActivityMillis = nowMillis
    }

    fun recordLocations(playerId: PlayerId, samples: List<LocationSample>, nowMillis: Long) {
        val player = player(playerId)
        val inRound = phase == GamePhase.HIDING || phase == GamePhase.SEEKING
        for (sample in samples.sortedBy { it.timestampMillis }) {
            // Never trust a timestamp from the future.
            val fix = sample.copy(timestampMillis = minOf(sample.timestampMillis, nowMillis))
            val result = player.track.add(fix)
            player.fixResults[result] = (player.fixResults[result] ?: 0) + 1
            if (result != LocationTrack.Result.ACCEPTED) continue
            // Staleness is about location updates, not requests: an app with GPS off still syncs.
            player.lastFixReceivedMillis = nowMillis
            // The route is the round: not the lobby, not the results screen.
            if (inRound) {
                player.route?.add(fix)
                player.odometer.add(fix)
                if (phase == GamePhase.SEEKING && player.seekingStartFix == null && fix.isUsable(rules)) {
                    player.seekingStartFix = fix
                }
            }
            if (fix.isUsable(rules) && isInRound(player, fix.timestampMillis)) player.replay.add(fix)
        }
        lastActivityMillis = nowMillis
    }

    /**
     * What [playerId]'s phone says about itself with every sync ([DeviceReport], docs/adr/0010-nearby-radar.md):
     * what it can do and whether the radar is on. A hider whose phone has Bluetooth off while the radar is required
     * is revealed after [GameRules.radarOffRevealSeconds] ([VisibilityReason.RADAR_OFF]).
     */
    fun recordDevice(playerId: PlayerId, report: DeviceReport, nowMillis: Long) {
        val player = player(playerId)
        val previousAt = player.deviceAtMillis
        if (player.activity == Activity.RUNNING && previousAt != null && isPlaying(playerId)) {
            player.runningMillis += (nowMillis - previousAt).coerceIn(0, DEVICE_REPORT_TTL_MILLIS)
        }
        player.device = report
        player.deviceAtMillis = nowMillis
        player.activity = if (settings.features.activity) report.activity else Activity.UNKNOWN
        val required = settings.features.radar == FeatureMode.REQUIRED
        val inRound = phase == GamePhase.HIDING || phase == GamePhase.SEEKING
        val hiding = player.role == Role.HIDER && player.status == PlayerStatus.ACTIVE && !player.left
        player.bluetoothOffSinceMillis = when {
            !required || !inRound || !hiding || report.bluetooth == BluetoothState.ON -> null
            else -> player.bluetoothOffSinceMillis ?: nowMillis
        }
    }

    /**
     * Whom [playerId]'s phone heard over Bluetooth since its last sync ([SyncRequest.nearby]): the tokens of this
     * game's players count for the radar, anything else is dropped. Only in a game with the radar, during the round.
     */
    fun recordSightings(playerId: PlayerId, sightings: List<NearbySighting>, nowMillis: Long) {
        player(playerId)
        if (!settings.features.hasRadar || (phase != GamePhase.HIDING && phase != GamePhase.SEEKING)) return
        val secrets = { players.values.mapNotNull { p -> p.radarSecret?.let { p.id to it } }.toMap() }
        for (sighting in sightings.take(MAX_SIGHTINGS_PER_SYNC)) {
            // Never trust a timestamp from the future.
            radar.record(playerId, sighting.token, sighting.rssi, minOf(sighting.atMillis, nowMillis), secrets)
        }
    }

    /**
     * [seekerId] says they found [hiderId]. GPS can refuse the claim ([ErrorCode.TOO_FAR], [ErrorCode.NO_LOCATION]);
     * otherwise it is open and waits for the hider's code. With [code] (one scan of the hider's QR code), the code is
     * checked right away, as [confirmCatch] does: the right one confirms the catch, a wrong one counts as a failed
     * attempt ([ErrorCode.INVALID_CODE]) and leaves the claim open for the hider to show the current code.
     */
    fun claimCatch(seekerId: PlayerId, hiderId: PlayerId, catchId: CatchId, nowMillis: Long, code: String? = null) {
        requirePhase(GamePhase.SEEKING)
        val seeker = player(seekerId)
        val hider = player(hiderId)
        if (seeker.role != Role.SEEKER || seeker.status != PlayerStatus.ACTIVE) {
            throw GameException(ErrorCode.FORBIDDEN, "Only active seekers can claim a catch")
        }
        if (hider.role != Role.HIDER || hider.status != PlayerStatus.ACTIVE) {
            throw GameException(ErrorCode.WRONG_STATE, "This player can't be caught")
        }
        if (catches.values.any { it.isOpen && (it.hiderId == hiderId || it.seekerId == seekerId) }) {
            throw GameException(ErrorCode.WRONG_STATE, "There is already an open catch claim")
        }

        val seekerFixes = seeker.track.recentUsableFixes(nowMillis)
        if (seekerFixes.isEmpty()) {
            throw GameException(ErrorCode.NO_LOCATION, "No accurate GPS fix yet, step into the open")
        }
        // Without fixes of the hider GPS can't disprove the claim: the code decides.
        val hiderFixes = hider.track.recentUsableFixes(nowMillis)
        val closest = CatchRules.closestPossibleDistanceMeters(seekerFixes, hiderFixes)
        if (closest != null && closest > rules.catchMaxDistanceMeters) {
            throw GameException(ErrorCode.TOO_FAR, "GPS says you are too far away from this player")
        }
        // A claim only up close (docs/adr/0010-nearby-radar.md, section 2.5): when both phones have the radar, it
        // must have heard them «burning» lately; a phone without it is judged by GPS alone, as before.
        if (settings.features.proximityCatch && seeker.hasRadarOn() && hider.hasRadarOn() &&
            !radar.wasBurningWithin(seekerId, hiderId, nowMillis, rules.nearbyWindowSeconds * 1000L)
        ) {
            throw GameException(
                ErrorCode.TOO_FAR,
                "The radar has not heard you next to this player",
                ErrorReason.NOT_NEARBY,
            )
        }

        catches[catchId] = CatchClaim(
            id = catchId,
            seekerId = seekerId,
            hiderId = hiderId,
            createdAtMillis = nowMillis,
            deadlineMillis = nowMillis + rules.catchCodeTimeoutSeconds * 1000L,
            estimatedDistanceAtClaimMeters = CatchRules.estimatedDistanceMeters(seekerFixes, hiderFixes),
        )
        seeker.catchClaims++
        lastActivityMillis = nowMillis
        if (!code.isNullOrBlank()) confirmCatch(catchId, seekerId, code, nowMillis)
    }

    fun confirmCatch(catchId: CatchId, by: PlayerId, code: String, nowMillis: Long) {
        val claim = catch(catchId)
        if (claim.seekerId != by) throw GameException(ErrorCode.FORBIDDEN, "Only the claiming seeker enters the code")
        if (claim.status != CatchStatus.AWAITING_CODE) throw GameException(ErrorCode.WRONG_STATE, "The claim is closed")

        val secret = checkNotNull(player(claim.hiderId).catchCodeSecret)
        if (catchCodeTotp(secret, rules).verify(code.trim(), nowMillis)) {
            resolve(claim, confirmed = true, nowMillis)
        } else {
            claim.failedAttempts++
            val attemptsLeft = rules.catchCodeMaxAttempts - claim.failedAttempts
            if (attemptsLeft <= 0) resolve(claim, confirmed = false, nowMillis)
            throw GameException(ErrorCode.INVALID_CODE, "Wrong code, attempts left: ${attemptsLeft.coerceAtLeast(0)}")
        }
        lastActivityMillis = nowMillis
    }

    fun disputeCatch(catchId: CatchId, by: PlayerId, nowMillis: Long) {
        val claim = catch(catchId)
        if (claim.hiderId != by) throw GameException(ErrorCode.FORBIDDEN, "Only the hider can dispute")
        if (claim.status != CatchStatus.AWAITING_CODE) throw GameException(ErrorCode.WRONG_STATE, "The claim is closed")
        claim.status = CatchStatus.DISPUTED
        claim.wasDisputed = true
        claim.deadlineMillis = nowMillis + rules.disputeVoteSeconds * 1000L
        lastActivityMillis = nowMillis
        if (eligibleVoters(claim).isEmpty()) resolveDispute(claim, nowMillis)
    }

    fun vote(catchId: CatchId, voter: PlayerId, confirm: Boolean, nowMillis: Long) {
        val claim = catch(catchId)
        if (claim.status != CatchStatus.DISPUTED) throw GameException(ErrorCode.WRONG_STATE, "Voting is closed")
        val eligible = eligibleVoters(claim)
        if (voter !in eligible) throw GameException(ErrorCode.FORBIDDEN, "Players in the dispute can't vote")
        claim.votes[voter] = confirm
        lastActivityMillis = nowMillis
        if (claim.votes.keys.containsAll(eligible)) resolveDispute(claim, nowMillis)
    }

    /**
     * A chat message by [playerId], in any phase (the results screen has a chat too): to everybody, or with [team] to
     * the sender's team only (not in the lobby, see [ChatRules.channelFor]). [text] is cleaned ([ChatRules.clean]);
     * at most [ChatRules.RATE_LIMIT_MESSAGES] per player within [ChatRules.RATE_LIMIT_WINDOW_MILLIS].
     * [clientMessageId]: the app's id for the message; sent again (the answer got lost), the message is kept once and
     * returned as it was, without counting towards the limit.
     */
    fun sendChat(
        playerId: PlayerId,
        text: String,
        team: Boolean,
        nowMillis: Long,
        clientMessageId: String? = null,
    ): ChatMessage {
        val sender = player(playerId)
        clientMessageId?.let { sender.chatByClientId[it] }?.let { return it }
        val cleaned = ChatRules.clean(text)
        if (cleaned.length !in 1..ChatRules.MAX_LENGTH) {
            throw GameException(
                ErrorCode.BAD_REQUEST,
                "A message has 1..${ChatRules.MAX_LENGTH} characters",
                ErrorReason.INVALID_MESSAGE,
            )
        }
        val recent = sender.chatSentAtMillis
        while (recent.isNotEmpty() && recent.first() <= nowMillis - ChatRules.RATE_LIMIT_WINDOW_MILLIS) {
            recent.removeFirst()
        }
        if (recent.size >= ChatRules.RATE_LIMIT_MESSAGES) {
            val retryAfter = recent.first() + ChatRules.RATE_LIMIT_WINDOW_MILLIS - nowMillis
            throw GameException.tooManyRequests(Duration.ofMillis(retryAfter), "Too many messages, wait a little")
        }
        recent.addLast(nowMillis)

        val message = ChatMessage(
            seq = ++lastChatSeq,
            playerId = playerId,
            text = cleaned,
            sentAtMillis = nowMillis,
            channel = ChatRules.channelFor(phase, sender.role, team),
        )
        chat.addLast(message)
        while (chat.size > ChatRules.HISTORY_SIZE) chat.removeFirst()
        if (clientMessageId != null) {
            val byClientId = sender.chatByClientId
            byClientId[clientMessageId] = message
            if (byClientId.size > CHAT_IDS_KEPT) byClientId.remove(byClientId.keys.first())
        }
        lastActivityMillis = nowMillis
        return message
    }

    /**
     * Chat message [seq] as [reporterId] reports it to the moderators. Only a message the game still keeps and the
     * reporter can see (else [ErrorCode.NOT_FOUND], the same for both: nobody learns about the other team's messages),
     * and not their own ([ErrorCode.FORBIDDEN]).
     */
    fun reportedMessage(reporterId: PlayerId, seq: Long): ReportedMessage {
        val reporter = player(reporterId)
        val message = chat.firstOrNull { it.seq == seq }?.takeIf { ChatRules.canSee(it.channel, reporter.role) }
            ?: throw GameException(ErrorCode.NOT_FOUND, "No such message")
        if (message.playerId == reporterId) throw GameException(ErrorCode.FORBIDDEN, "That is your own message")
        val sender = player(message.playerId)
        return ReportedMessage(message, sender.name, sender.userId, reporter.userId)
    }

    /**
     * Every player's way through the round, for the replay: only once the game is over, when nothing is hidden any more
     * ([ErrorCode.WRONG_STATE] before, the tracks would give the hiders away).
     */
    fun tracks(): TracksResponse {
        requirePhase(GamePhase.FINISHED)
        return TracksResponse(players.values.map { PlayerTrack(it.id, it.replay.points()) })
    }

    /** Applies everything that happens by itself as time passes. */
    fun advance(nowMillis: Long) {
        val streetZoneOverdue = nowMillis - streetZoneSinceMillis >= STREET_ZONE_PATIENCE_MILLIS
        if (streetZoneState == StreetZoneState.LOADING && streetZoneOverdue) {
            onStreetZoneUnavailable()
        }
        if (phase == GamePhase.HIDING) {
            val hidingEnds = phaseStartedAtMillis + settings.hidingSeconds * 1000L
            if (nowMillis >= hidingEnds) {
                enterPhase(GamePhase.SEEKING, hidingEnds)
                zoneStartedAtMillis = hidingEnds
            }
        }
        if (phase != GamePhase.SEEKING) return

        for (claim in catches.values) {
            if (claim.status == CatchStatus.AWAITING_CODE && nowMillis >= claim.deadlineMillis) {
                // Silence doesn't help: no reaction before the deadline counts as caught.
                resolve(claim, confirmed = true, claim.deadlineMillis)
            } else if (claim.status == CatchStatus.DISPUTED && nowMillis >= claim.deadlineMillis) {
                resolveDispute(claim, claim.deadlineMillis)
            }
        }
        checkZone(nowMillis)
        checkBuildings(nowMillis)
        updateGlow(nowMillis)
        advanceBoard(nowMillis)
        val seekingEnds = phaseStartedAtMillis + settings.seekingSeconds * 1000L
        if (phase == GamePhase.SEEKING && nowMillis >= seekingEnds) finish(seekingEnds)
    }

    /**
     * The history of this game (docs/adr/0007-game-history-and-routes.md), once: the first call after the game
     * finished returns it, every other call null. The caller saves it after releasing the game's lock.
     */
    fun takeFinishedRecord(): GameRecord? {
        if (recordTaken) return null
        val finished = finishedRecord() ?: return null
        recordTaken = true
        return finished
    }

    /**
     * The history of this finished game, as often as asked (a player turned saving routes on after the end, while the
     * game is still in memory); null before the end. Nothing changes after the end, so it is built once.
     */
    fun finishedRecord(): GameRecord? {
        if (phase != GamePhase.FINISHED) return null
        return record ?: buildRecord().also { record = it }
    }

    fun isExpired(nowMillis: Long, finishedRetentionMillis: Long, idleRetentionMillis: Long): Boolean {
        val finishedAt = finishedAtMillis
        return (finishedAt != null && nowMillis - finishedAt >= finishedRetentionMillis) ||
            nowMillis - lastActivityMillis >= idleRetentionMillis
    }

    /**
     * State as [viewerId] is allowed to see it. [chatAfter]: the viewer's chat cursor; the snapshot brings the newest
     * [ChatRules.MAX_PER_RESPONSE] messages after it that the viewer may see. Null (a client without chat): none.
     */
    fun snapshotFor(
        viewerId: PlayerId,
        nowMillis: Long,
        chatAfter: Long? = null,
        /** The server features the operator has on, by name ([GameSnapshot.enabledFeatures]). */
        enabledFeatures: List<String> = emptyList(),
    ): GameSnapshot {
        val viewer = player(viewerId)
        // Every request ends in a snapshot for its player: the lobby shows who is connected.
        viewer.lastSeenMillis = nowMillis
        val features = settings.features
        val inRound = phase == GamePhase.HIDING || phase == GamePhase.SEEKING
        return GameSnapshot(
            gameId = id,
            joinCode = joinCode,
            hostId = hostId,
            phase = phase,
            settings = settings,
            serverTimeMillis = nowMillis,
            phaseEndsAtMillis = phaseEndsAtMillis(),
            zoneStartedAtMillis = zoneStartedAtMillis,
            players = players.values.map { player ->
                PlayerView(
                    player.id,
                    player.name,
                    player.role,
                    player.status,
                    visibleLocation(viewer, player, nowMillis),
                    player.userId,
                    player.outAtMillis,
                    player.caughtBy,
                    lastSeenMillis = player.lastSeenMillis,
                    left = player.left,
                    capabilities = player.device?.toCapabilities(),
                    sparks = player.sparks.takeIf { features.hasSparks },
                )
            },
            me = MyState(
                playerId = viewer.id,
                role = viewer.role,
                status = viewer.status,
                catchCodeSecret = viewer.catchCodeSecret,
                outOfZoneDeadlineMillis = viewer.outOfZoneDeadlineMillis(),
                insideBuildingRevealAtMillis = viewer.insideBuildingRevealAtMillis(),
                radarSecret = viewer.radarSecret.takeIf { features.hasRadar && inRound },
                radar = radarStateFor(viewer, nowMillis),
                bluetoothDeadlineMillis = viewer.bluetoothDeadlineMillis(),
                uwbPeers = uwbPeersFor(viewer, nowMillis),
                sparks = if (features.hasSparks) viewer.sparks else 0,
                hint = hintFor(viewer, nowMillis),
                perks = perkViewsFor(viewer, nowMillis),
            ),
            catches = catches.values
                .filter { it.seekerId == viewerId || it.hiderId == viewerId || viewerId in eligibleVoters(it) }
                .takeLast(MAX_CATCHES_IN_SNAPSHOT)
                .map { it.toView(viewerId) },
            buildings = buildingsState,
            finishedAtMillis = finishedAtMillis,
            chat = chatAfter?.let { after ->
                chat.filter { it.seq > after && ChatRules.canSee(it.channel, viewer.role) }
                    .takeLast(ChatRules.MAX_PER_RESPONSE)
            }.orEmpty(),
            streetZone = streetZoneState,
            mapRevision = mapRevision,
            rolesDrawnAtMillis = rolesDrawnAtMillis,
            enabledFeatures = enabledFeatures,
            items = itemsFor(viewer),
            quests = if (features.quests) board.questViewsFor(viewer, isHost = viewer.id == hostId) else emptyList(),
        )
    }

    fun hasPlayer(id: PlayerId): Boolean = id in players

    /** What staff see of this game in the admin (docs/adr/0008-admin.md): no zone center, no positions, no chat. */
    fun adminView(): AdminGame = AdminGame(
        gameId = id,
        phase = phase,
        hostName = players[hostId]?.name.orEmpty(),
        players = players.size,
        guests = players.values.count { it.userId == null },
        seekers = players.values.count { it.role == Role.SEEKER },
        createdAtMillis = createdAtMillis,
        phaseStartedAtMillis = phaseStartedAtMillis,
        lastActivityMillis = lastActivityMillis,
        zoneRadiusMeters = settings.zone.initial.radiusMeters,
        chatMessages = lastChatSeq.toInt(),
        buildings = buildingsState,
        buildingCount = buildings.buildings.size.takeIf { buildingsState == BuildingsState.READY },
        zoneShape = settings.zoneShape,
        streetZone = streetZoneState,
    )

    /**
     * Staff end a started round now (docs/adr/0008-admin.md), as if its time ran out: the players see the results, the
     * history is saved. A game in the lobby is removed instead ([GameService.endByStaff]).
     */
    fun endNow(nowMillis: Long) {
        if (phase == GamePhase.LOBBY) throw GameException(ErrorCode.WRONG_STATE, "The game has not started")
        finish(nowMillis)
        lastActivityMillis = nowMillis
    }

    /**
     * Everything, unfiltered, for the e2e observer (served only with the `e2e` Spring profile).
     * Never use it for player-facing responses: those go through [snapshotFor].
     */
    fun debugState(nowMillis: Long): DebugGameState {
        val zoneStart = zoneStartedAtMillis
        return DebugGameState(
            gameId = id,
            joinCode = joinCode,
            hostId = hostId,
            phase = phase,
            settings = settings,
            serverTimeMillis = nowMillis,
            phaseStartedAtMillis = phaseStartedAtMillis,
            phaseEndsAtMillis = phaseEndsAtMillis(),
            zoneStartedAtMillis = zoneStart,
            zone = if (zoneStart != null &&
                phase == GamePhase.SEEKING
            ) {
                settings.zone.circleAt(nowMillis - zoneStart)
            } else {
                null
            },
            streetZone = streetZoneState,
            streetZonePolygon = streetZone?.let { zone ->
                val stage = zoneStart?.takeIf { phase == GamePhase.SEEKING }
                    ?.let { settings.zone.stateAt(nowMillis - it).stage }
                zone.areaAt(stage ?: 0).polygon
            },
            mapRevision = mapRevision,
            rolesDrawnAtMillis = rolesDrawnAtMillis,
            buildingCount = buildings.buildings.size,
            finishedAtMillis = finishedAtMillis,
            players = players.values.map { player ->
                DebugPlayer(
                    id = player.id,
                    name = player.name,
                    role = player.role,
                    status = player.status,
                    latestFix = player.track.latest,
                    latestUsableFix = player.track.latestUsable(),
                    lastFixReceivedMillis = player.lastFixReceivedMillis,
                    lastMockAtMillis = player.track.lastMockAtMillis,
                    outOfZoneSinceMillis = player.outOfZoneSinceMillis,
                    outOfZoneDeadlineMillis = player.outOfZoneDeadlineMillis(),
                    insideBuildingSinceMillis = player.insideBuildingSinceMillis,
                    buildingsLoadedAtMillis = player.buildingsLoadedAtMillis,
                    revealedToSeekers = revealReason(player, nowMillis) ?: markCause(player),
                    catchCodeSecret = player.catchCodeSecret,
                    fixes = DebugFixCounts(
                        accepted = player.fixResults[LocationTrack.Result.ACCEPTED] ?: 0,
                        mock = player.fixResults[LocationTrack.Result.MOCK] ?: 0,
                        outOfOrder = player.fixResults[LocationTrack.Result.OUT_OF_ORDER] ?: 0,
                        implausible = player.fixResults[LocationTrack.Result.IMPLAUSIBLE] ?: 0,
                    ),
                    userId = player.userId,
                    outAtMillis = player.outAtMillis,
                    caughtBy = player.caughtBy,
                    replayPoints = player.replay.size,
                    left = player.left,
                    lastSeenMillis = player.lastSeenMillis,
                    glowMark = player.glowMark,
                    capabilities = player.device?.toCapabilities(),
                    activity = player.activity,
                    radarSecret = player.radarSecret,
                    sparks = player.sparks,
                    bluetoothOffSinceMillis = player.bluetoothOffSinceMillis,
                )
            },
            catches = catches.values.map { claim ->
                DebugCatch(
                    id = claim.id,
                    seekerId = claim.seekerId,
                    hiderId = claim.hiderId,
                    status = claim.status,
                    createdAtMillis = claim.createdAtMillis,
                    deadlineMillis = claim.deadlineMillis,
                    failedAttempts = claim.failedAttempts,
                    votes = claim.votes.map { (voter, confirm) -> DebugVote(voter, confirm) },
                    estimatedDistanceAtClaimMeters = claim.estimatedDistanceAtClaimMeters,
                )
            },
            buildings = buildingsState,
            chat = chat.toList(),
            radar = radar.debugPairs(nowMillis),
            items = board.items.values.map { it.toView(withCode = true) },
            quests = board.debugQuests(players.values),
        )
    }

    // ---- The board and the perks (docs/adr/0011-quests-sparks-and-sensors.md) ----

    /**
     * The host places an item on the map in the lobby (section 2.4): in or near the zone (up to
     * [ITEMS_BEYOND_ZONE_SHARE] of the radius plus [ITEMS_BEYOND_ZONE_METERS] beyond the first circle: the risky spots
     * outside the shrinking zone are the point), of a kind the game's features allow. A scan checkpoint gets its code
     * from [newCode]; the host sees it in the snapshot to print the QR code.
     */
    fun placeItem(by: PlayerId, request: PlaceItemRequest, newCode: () -> String, nowMillis: Long): BoardItem {
        requirePhase(GamePhase.LOBBY)
        requireHost(by, "place items")
        if (!isAllowed(request.kind)) throw featureOff(request.kind.name)
        val name = ChatRules.clean(request.name)
        if (name.length > BoardRules.MAX_NAME_LENGTH) {
            throw GameException(ErrorCode.BAD_REQUEST, "A name has at most ${BoardRules.MAX_NAME_LENGTH} characters")
        }
        val sparks = request.sparks ?: BoardRules.defaultSparks(request.kind)
        if (sparks !in 0..QuestCatalog.MAX_SPARKS) {
            throw GameException(ErrorCode.BAD_REQUEST, "Sparks: 0..${QuestCatalog.MAX_SPARKS}")
        }
        val perk = when (request.kind) {
            ItemKind.PICKUP -> request.perk ?: throw GameException(ErrorCode.BAD_REQUEST, "A pickup needs a perk")
            else -> null
        }
        val initial = settings.zone.initial
        val reach = initial.radiusMeters * (1 + ITEMS_BEYOND_ZONE_SHARE) + ITEMS_BEYOND_ZONE_METERS
        if (request.point.distanceTo(initial.center) > reach) {
            throw GameException(ErrorCode.BAD_REQUEST, "Place items in or near the zone")
        }
        val item = Item(
            id = ItemId("i${nextItemNumber++}"),
            kind = request.kind,
            point = request.point,
            name = name,
            audience = request.audience,
            sparks = sparks,
            perk = perk,
            code = if (request.kind == ItemKind.CHECKPOINT_SCAN) newCode() else null,
        )
        board.place(item)
        lastActivityMillis = nowMillis
        return item.toView(withCode = true)
    }

    fun removeItem(by: PlayerId, itemId: ItemId, nowMillis: Long) {
        requirePhase(GamePhase.LOBBY)
        requireHost(by, "remove items")
        board.remove(itemId)
        lastActivityMillis = nowMillis
    }

    /**
     * The host makes up a quest in words (section 2.3), in the lobby or during the round: the players of its audience
     * see it, say when they did it ([markQuestDone]) and the host confirms ([reviewQuest]).
     */
    fun addCustomQuest(by: PlayerId, request: CustomQuestRequest, nowMillis: Long) {
        if (phase == GamePhase.FINISHED) throw GameException(ErrorCode.WRONG_STATE, "The game is over")
        requireHost(by, "add quests")
        if (!settings.features.quests) throw featureOff("Quests")
        val text = ChatRules.clean(request.text)
        if (text.length !in 1..QuestCatalog.MAX_CUSTOM_TEXT) {
            throw GameException(ErrorCode.BAD_REQUEST, "A quest has 1..${QuestCatalog.MAX_CUSTOM_TEXT} characters")
        }
        if (request.sparks !in 1..QuestCatalog.MAX_SPARKS) {
            throw GameException(ErrorCode.BAD_REQUEST, "Sparks: 1..${QuestCatalog.MAX_SPARKS}")
        }
        board.addCustomQuest(CustomQuest(newQuestId(), text, request.audience, request.sparks))
        lastActivityMillis = nowMillis
    }

    /** [playerId] says they did the host's quest [questId], during the round; the host answers ([reviewQuest]). */
    fun markQuestDone(playerId: PlayerId, questId: QuestId, nowMillis: Long) {
        val player = player(playerId)
        if (!isPlaying(playerId)) {
            throw GameException(ErrorCode.WRONG_STATE, "Not in a round", ErrorReason.QUEST_NOT_ACTIVE)
        }
        board.markDone(player, questId)
        lastActivityMillis = nowMillis
    }

    /** The host confirms or refuses what [QuestReviewRequest.playerId] said about quest [questId]. */
    fun reviewQuest(by: PlayerId, questId: QuestId, request: QuestReviewRequest, nowMillis: Long) {
        requireHost(by, "review quests")
        board.review(questId, player(request.playerId), request.approved)
        lastActivityMillis = nowMillis
    }

    /**
     * [playerId] scanned the code of a checkpoint (section 2.4), during the search: the code must be one of this
     * game's, and the fixes must not prove the player far from it.
     */
    fun scanCheckpoint(playerId: PlayerId, code: String, nowMillis: Long) {
        requirePhase(GamePhase.SEEKING)
        val player = player(playerId)
        if (!player.isPlayingNow) throw GameException(ErrorCode.FORBIDDEN, "You are out of the round")
        if (!settings.features.checkpoints) throw featureOff("Checkpoints")
        board.scan(player, code, nowMillis)
        lastActivityMillis = nowMillis
    }

    /**
     * [playerId] uses a perk (section 3): one they picked up on the map, else one bought for sparks. The effects
     * work on what the seekers see between glows ([visibleLocation]) and on the hints ([hintFor]).
     */
    fun usePerk(playerId: PlayerId, request: UsePerkRequest, nowMillis: Long) {
        requirePhase(GamePhase.SEEKING)
        val features = settings.features
        if (!features.perks && !features.pickups) throw featureOff("Perks")
        val player = player(playerId)
        if (!player.isPlayingNow) throw GameException(ErrorCode.FORBIDDEN, "You are out of the round")
        val spec = PerkCatalog.spec(request.perk)
        if (spec.role != player.role) throw perkUnavailable("Not for your role")
        if (spec.needsGlow && !Glow.isOn(settings)) throw perkUnavailable("This perk needs the glow")
        if ((player.perkUses[spec.perk] ?: 0) >= spec.maxUses) throw perkUnavailable("Used up for this round")
        player.lastPerkAtMillis?.let { last ->
            if (nowMillis - last < rules.perkCooldownSeconds * 1000L) {
                throw perkUnavailable("Wait a little between perks")
            }
        }
        val owned = player.perksOwned[spec.perk] ?: 0
        if (owned == 0) {
            if (!features.perks) throw perkUnavailable("Only perks found on the map in this game")
            if (player.sparks < spec.price) {
                throw GameException(ErrorCode.WRONG_STATE, "Not enough sparks", ErrorReason.NOT_ENOUGH_SPARKS)
            }
        }
        applyPerk(player, spec.perk, request, nowMillis)
        if (owned > 0) player.perksOwned[spec.perk] = owned - 1 else player.sparks -= spec.price
        player.perkUses[spec.perk] = (player.perkUses[spec.perk] ?: 0) + 1
        player.lastPerkAtMillis = nowMillis
        lastActivityMillis = nowMillis
    }

    private fun applyPerk(player: Player, perk: PerkKind, request: UsePerkRequest, nowMillis: Long) {
        val seekingStart = checkNotNull(zoneStartedAtMillis)
        val lasts = PerkCatalog.spec(perk).effectSeconds * 1000L
        when (perk) {
            PerkKind.ERASE_TRAIL -> {
                if (player.glowMark == null && player.decoyMark == null) throw perkUnavailable("No spot to erase yet")
                player.glowMark = null
                player.decoyMark = null
                player.freshMark = null
            }

            PerkKind.DECOY -> {
                val point = request.point ?: throw GameException(ErrorCode.BAD_REQUEST, "A decoy needs a point")
                // It passes for the spot of the last glow: the same time stamp, inside the zone like a real one.
                val last = Glow.lastStarted(settings, seekingStart, nowMillis)?.takeIf { !it.isOpenAt(nowMillis) }
                    ?: throw perkUnavailable("A decoy works after a glow")
                val zone = settings.zone.areaAt(nowMillis - seekingStart, streetZone)
                if (zone.signedDistanceMeters(point) > 0) {
                    throw GameException(ErrorCode.BAD_REQUEST, "Put the decoy inside the zone")
                }
                player.decoyMark = LocationSample(point, DECOY_ACCURACY_METERS, last.endMillis - 1)
                player.freshMark = null
            }

            PerkKind.INVISIBLE -> {
                val next = Glow.next(settings, seekingStart, nowMillis) ?: throw perkUnavailable("No glow left")
                player.invisibleGlowIndex = next.index
            }

            PerkKind.SENSE -> player.hint = Hint(HintKind.SENSE, nowMillis + lasts)

            PerkKind.DIRECTION -> player.hint = Hint(HintKind.DIRECTION, nowMillis + lasts)

            PerkKind.RADIUS -> player.hint = Hint(HintKind.RADIUS, nowMillis + lasts)

            PerkKind.SPOTLIGHT -> perkTarget(request).spotlightUntilMillis = nowMillis + rules.spotlightSeconds * 1000L

            PerkKind.FRESH_TRAIL -> {
                val target = perkTarget(request)
                val fresh = target.track.latestAtOrBefore(nowMillis - PerkCatalog.FRESH_TRAIL_AGE_MILLIS)
                    ?.takeIf { it.timestampMillis > (target.glowMark?.timestampMillis ?: Long.MIN_VALUE) }
                    ?: throw perkUnavailable("No newer trail of this player")
                target.freshMark = fresh
            }
        }
    }

    private fun perkTarget(request: UsePerkRequest): Player {
        val id = request.targetId ?: throw GameException(ErrorCode.BAD_REQUEST, "This perk needs a hider")
        val target = player(id)
        if (target.role != Role.HIDER || target.status != PlayerStatus.ACTIVE || target.left) {
            throw GameException(ErrorCode.WRONG_STATE, "This player can't be the target")
        }
        return target
    }

    /** The board during the search: the quests start with it, then every rule is judged on the fixes so far. */
    private fun advanceBoard(nowMillis: Long) {
        val seekingStart = zoneStartedAtMillis ?: return
        if (phase != GamePhase.SEEKING || !settings.features.hasSparks) return
        if (!questsStarted) {
            questsStarted = true
            board.startQuests(players.values, settings.quests, ::newQuestId, seekingStart)
        }
        val context = BoardContext(
            nowMillis = nowMillis,
            zoneCenter = settings.zone.initial.center,
            lastGlow = Glow.lastStarted(settings, seekingStart, nowMillis),
            radarBand = { a, b -> radar.bandBetween(a, b, nowMillis) },
        )
        board.advance(players.values, context)
    }

    /**
     * The items as [viewer] sees them: every one in the lobby (the host with the codes of the scan checkpoints), the
     * ones of their audience in the round.
     */
    private fun itemsFor(viewer: Player): List<BoardItem> {
        if (!settings.features.hasBoard) return emptyList()
        val lobby = phase == GamePhase.LOBBY
        return board.items.values
            .filter { lobby || BoardRules.isFor(it.audience, viewer.role) }
            .map { it.toView(withCode = lobby && viewer.id == hostId) }
    }

    /** The perks [viewer] may use: every one of their role with the shop, only the ones found without it. */
    private fun perkViewsFor(viewer: Player, nowMillis: Long): List<PerkView> {
        val features = settings.features
        if (!features.perks && !features.pickups) return emptyList()
        val cooldownEnds = viewer.lastPerkAtMillis?.let { it + rules.perkCooldownSeconds * 1000L }
            ?.takeIf { it > nowMillis }
        return PerkCatalog.forRole(viewer.role).mapNotNull { spec ->
            val owned = viewer.perksOwned[spec.perk] ?: 0
            if (!features.perks && owned == 0) return@mapNotNull null
            val usesLeft = (spec.maxUses - (viewer.perkUses[spec.perk] ?: 0)).coerceAtLeast(0)
            val affordable = owned > 0 || (features.perks && viewer.sparks >= spec.price)
            PerkView(
                perk = spec.perk,
                price = spec.price,
                owned = owned,
                usesLeft = usesLeft,
                canUse = phase == GamePhase.SEEKING && viewer.isPlayingNow && usesLeft > 0 && cooldownEnds == null &&
                    affordable && (!spec.needsGlow || Glow.isOn(settings)),
                availableAtMillis = cooldownEnds,
            )
        }
    }

    /**
     * The hint a perk bought [viewer], while it lasts: the compass sector towards the nearest player of the other team
     * and how far they are, as a band, from where both are right now. No coordinates leave the server.
     */
    private fun hintFor(viewer: Player, nowMillis: Long): Hint? {
        val hint = viewer.hint?.takeIf { phase == GamePhase.SEEKING && nowMillis < it.untilMillis } ?: return null
        val here = viewer.track.latestUsable()?.point ?: return hint
        val nearest = players.values
            .filter { it.isPlayingNow && it.role != viewer.role }
            .mapNotNull { it.track.latestUsable()?.point }
            .minByOrNull { it.distanceTo(here) } ?: return hint
        return hint.copy(
            sector = Sectors.compassSector(here.bearingTo(nearest)).takeIf { hint.kind != HintKind.RADIUS },
            band = Sectors.bandFor(here.distanceTo(nearest)).takeIf { hint.kind != HintKind.DIRECTION },
        )
    }

    // ---- The radar (docs/adr/0010-nearby-radar.md) ----

    /**
     * The viewer's radar during the search (section 2.4): a seeker gets every active hider the radar hears, by name;
     * a hider with the sense on gets the nearest seeker, nameless.
     */
    private fun radarStateFor(viewer: Player, nowMillis: Long): RadarState? {
        if (!settings.features.hasRadar || phase != GamePhase.SEEKING || !viewer.isPlayingNow) return null
        val contacts = when (viewer.role) {
            Role.SEEKER ->
                players.values
                    .filter { it.role == Role.HIDER && it.status == PlayerStatus.ACTIVE && !it.left }
                    .mapNotNull { hider ->
                        val band = radar.bandBetween(viewer.id, hider.id, nowMillis)
                        if (band == RadarBand.NONE) return@mapNotNull null
                        RadarContact(band, hider.id, radar.lastHeardMillis(viewer.id, hider.id))
                    }

            Role.HIDER -> {
                if (!settings.features.hiderSense) return null
                players.values.filter { it.role == Role.SEEKER && !it.left }
                    .map { radar.bandBetween(viewer.id, it.id, nowMillis) to radar.lastHeardMillis(viewer.id, it.id) }
                    .filter { (band, _) -> band != RadarBand.NONE }
                    .maxByOrNull { (band, _) -> band }
                    ?.let { (band, at) -> listOf(RadarContact(band, atMillis = at)) }
                    .orEmpty()
            }
        }
        return RadarState(contacts)
    }

    /**
     * Whom [viewer]'s phone may range with by UWB right now (section 3): the other team's players whose phones are of
     * the same kind, have UWB and are on the screen too («peeking»), the nearest [MAX_UWB_PEERS]. In fair mode only
     * when every player's phone has UWB and all are of one kind. A hider gets their peers too, since ranging takes
     * both sides; whether their app shows the arrow is [GameFeatures.precisionForHiders].
     */
    private fun uwbPeersFor(viewer: Player, nowMillis: Long): List<UwbPeer> {
        val features = settings.features
        if (!features.precisionRadar || phase != GamePhase.SEEKING || !viewer.isPlayingNow) return emptyList()
        val mine = viewer.device?.takeIf { viewer.isReporting(nowMillis) && it.uwb && it.onScreen }
        val myPlatform = mine?.platform?.takeIf { it != Platform.OTHER } ?: return emptyList()
        if (mine.uwbToken == null) return emptyList()
        val playing = players.values.filter { it.isPlayingNow }
        if (features.fairOnly) {
            val platforms = playing.mapTo(HashSet()) { it.device?.platform ?: Platform.OTHER }
            if (playing.any { it.device?.uwb != true } || platforms.size != 1) return emptyList()
        }
        val here = viewer.track.latestUsable()?.point
        return playing
            .filter { it.role != viewer.role }
            .mapNotNull { other ->
                val device = other.device?.takeIf { other.isReporting(nowMillis) } ?: return@mapNotNull null
                val token = device.uwbToken ?: return@mapNotNull null
                if (!device.uwb || !device.onScreen || device.platform != myPlatform) return@mapNotNull null
                val meters = here?.let { h -> other.track.latestUsable()?.point?.distanceTo(h) } ?: Double.MAX_VALUE
                meters to UwbPeer(other.id, token, device.platform)
            }
            .sortedBy { (meters, _) -> meters }
            .take(MAX_UWB_PEERS)
            .map { (_, peer) -> peer }
    }

    private fun visibleLocation(viewer: Player, target: Player, nowMillis: Long): VisibleLocation? {
        if (viewer.id == target.id || viewer.role != Role.SEEKER) return null
        val reason = revealReason(target, nowMillis)
        // Between glows: where the last glow left the hider (or what a perk put there), not where they are now.
        val fix = if (reason != null) target.track.latest else target.shownMark()
        val cause = reason ?: markCause(target)
        if (fix == null || cause == null) return null
        return VisibleLocation(fix.point, fix.accuracyMeters, fix.timestampMillis, cause.forFirstClients(), cause)
    }

    /**
     * `VisibleLocation.reason` has no default: the first app versions fail on a value they don't know. A reason added
     * later goes to `cause` and, in `reason`, becomes the closest one they know.
     */
    private fun VisibilityReason.forFirstClients(): VisibilityReason = when (this) {
        VisibilityReason.INSIDE_BUILDING,
        VisibilityReason.GLOW,
        VisibilityReason.RADAR_OFF,
        VisibilityReason.SPOTLIGHT,
        VisibilityReason.FRESH_TRAIL,
        -> VisibilityReason.OUT_OF_ZONE

        else -> this
    }

    /** Why seekers may see [target] right now (live), or null when it stays hidden from them. */
    private fun revealReason(target: Player, nowMillis: Long): VisibilityReason? = when {
        phase != GamePhase.HIDING && phase != GamePhase.SEEKING -> null
        target.left -> null
        target.role == Role.SEEKER -> VisibilityReason.TEAMMATE
        phase != GamePhase.SEEKING || target.status != PlayerStatus.ACTIVE -> null
        target.outOfZoneSinceMillis != null -> VisibilityReason.OUT_OF_ZONE
        target.recentlyMocked(nowMillis) -> VisibilityReason.MOCK_LOCATION
        target.isStale(nowMillis) -> VisibilityReason.STALE_SIGNAL
        target.isRevealedInsideBuilding(nowMillis) -> VisibilityReason.INSIDE_BUILDING
        target.isRevealedRadarOff(nowMillis) -> VisibilityReason.RADAR_OFF
        target.isSpotlit(nowMillis) -> VisibilityReason.SPOTLIGHT
        isGlowing(nowMillis) && !target.isInvisible(nowMillis) -> VisibilityReason.GLOW
        else -> null
    }

    /** A glow is on: the seekers see every active hider live (docs/adr/0009-game-setup-glow-streets.md). */
    private fun isGlowing(nowMillis: Long): Boolean {
        val seekingStart = zoneStartedAtMillis ?: return false
        return phase == GamePhase.SEEKING && Glow.openAt(settings, seekingStart, nowMillis) != null
    }

    /**
     * Between glows the seekers see the spot where the last one left an active hider, or what a perk put in its place:
     * a seeker's «Fresh trail» first, else a hider's «Decoy», else the glow's own spot.
     */
    private fun Player.shownMark(): LocationSample? {
        if (phase != GamePhase.SEEKING || left || role != Role.HIDER || status != PlayerStatus.ACTIVE) return null
        return freshMark ?: decoyMark ?: glowMark
    }

    /** Why the seekers see [target]'s spot between glows; null when there is none. */
    private fun markCause(target: Player): VisibilityReason? = when {
        target.shownMark() == null -> null
        target.freshMark != null -> VisibilityReason.FRESH_TRAIL
        else -> VisibilityReason.GLOW
    }

    /** «Invisible» (docs/adr/0011): the hider bought their way out of the glow going on right now. */
    private fun Player.isInvisible(nowMillis: Long): Boolean {
        val seekingStart = zoneStartedAtMillis ?: return false
        val open = Glow.openAt(settings, seekingStart, nowMillis) ?: return false
        return invisibleGlowIndex == open.index
    }

    private fun Player.isSpotlit(nowMillis: Long): Boolean = spotlightUntilMillis?.let { nowMillis < it } == true

    private fun Player.bluetoothDeadlineMillis(): Long? =
        bluetoothOffSinceMillis?.let { it + rules.radarOffRevealSeconds * 1000L }

    private fun Player.isRevealedRadarOff(nowMillis: Long): Boolean =
        bluetoothDeadlineMillis()?.let { nowMillis >= it } == true

    private fun Player.hasRadarOn(): Boolean = device?.bluetooth == BluetoothState.ON

    private fun Player.isReporting(nowMillis: Long): Boolean =
        deviceAtMillis?.let { nowMillis - it <= DEVICE_REPORT_TTL_MILLIS } == true

    private fun DeviceReport.toCapabilities() = Capabilities(platform, bluetooth, uwb, onScreen, activitySensor)

    private fun isAllowed(kind: ItemKind): Boolean = when (kind) {
        ItemKind.QUEST_POINT -> settings.features.quests
        ItemKind.CHECKPOINT_GEO, ItemKind.CHECKPOINT_SCAN -> settings.features.checkpoints
        ItemKind.PICKUP -> settings.features.pickups
    }

    private fun newQuestId() = QuestId("q${nextQuestNumber++}")

    private fun featureOff(what: String) =
        GameException(ErrorCode.WRONG_STATE, "$what: off in this game", ErrorReason.FEATURE_DISABLED)

    private fun perkUnavailable(why: String) = GameException(ErrorCode.WRONG_STATE, why, ErrorReason.PERK_UNAVAILABLE)

    /**
     * Once a glow is over, where it left each active hider: the last fix taken during it (or before), which the seekers
     * see until the next glow. A hider with no fix at all is seen by the stale-signal rule anyway.
     */
    private fun updateGlow(nowMillis: Long) {
        val seekingStart = zoneStartedAtMillis ?: return
        val last = Glow.lastStarted(settings, seekingStart, nowMillis) ?: return
        if (last.isOpenAt(nowMillis) || last.index <= glowMarksOf) return
        glowMarksOf = last.index
        for (hider in players.values.filter { it.role == Role.HIDER && it.status == PlayerStatus.ACTIVE }) {
            // What a perk put on the map lasts until the next glow.
            hider.decoyMark = null
            hider.freshMark = null
            // «Invisible»: this glow leaves no new spot, the old one stays.
            if (hider.invisibleGlowIndex == last.index) continue
            hider.track.latestAtOrBefore(last.endMillis - 1)?.let {
                hider.glowMark = it
                hider.glowMarkIndex = last.index
            }
        }
    }

    private fun phaseEndsAtMillis(): Long? = when (phase) {
        GamePhase.HIDING -> phaseStartedAtMillis + settings.hidingSeconds * 1000L
        GamePhase.SEEKING -> phaseStartedAtMillis + settings.seekingSeconds * 1000L
        else -> null
    }

    private fun Player.outOfZoneDeadlineMillis(): Long? = outOfZoneSinceMillis?.let {
        it +
            rules.outOfZoneGraceSeconds * 1000L
    }

    private fun checkZone(nowMillis: Long) {
        val zoneStart = zoneStartedAtMillis ?: return
        // The zone by streets when it was built, else the circle (also when building it failed).
        val zone = settings.zone.areaAt(nowMillis - zoneStart, streetZone)
        var lastOutMillis: Long? = null
        for (hider in players.values.filter { it.role == Role.HIDER && it.status == PlayerStatus.ACTIVE }) {
            // Players in an open catch claim or dispute are frozen until it is resolved.
            if (catches.values.any { it.isOpen && it.hiderId == hider.id }) continue
            val recent = hider.track.recentUsableFixes(nowMillis)
            val since = hider.outOfZoneSinceMillis
            when {
                ZoneRules.isConfidentlyOutside(recent, zone, rules) -> {
                    if (since == null) {
                        hider.outOfZoneSinceMillis = nowMillis
                        hider.zoneWarnings++
                    } else if (nowMillis - since >= rules.outOfZoneGraceSeconds * 1000L) {
                        hider.status = PlayerStatus.ELIMINATED
                        // Out when the time to return ran out, however long it took anybody to ask.
                        val outAt = since + rules.outOfZoneGraceSeconds * 1000L
                        hider.outAtMillis = outAt
                        hider.outOfZoneSinceMillis = null
                        board.onOut(hider)
                        lastOutMillis = maxOf(lastOutMillis ?: outAt, outAt)
                    }
                }

                // Back inside, judged on several fixes like leaving: one fix that jumps inside lifts nothing.
                since != null && ZoneRules.isConfidentlyBack(recent, zone, rules) -> {
                    hider.outOfZoneSinceMillis = null
                }
            }
        }
        // The last hider out of the zone ends the round when their time ran out.
        if (players.values.none { it.role == Role.HIDER && it.status == PlayerStatus.ACTIVE }) {
            finish(lastOutMillis ?: nowMillis)
        }
    }

    /**
     * Inside a building: warned as soon as the server is confident (several fixes, each deeper inside than its
     * accuracy), revealed to the seekers after [GameRules.insideBuildingRevealSeconds]. Never eliminated: GPS near
     * houses is a hint, not a judge. Out again, also judged on several fixes, lifts the warning and the reveal.
     */
    private fun checkBuildings(nowMillis: Long) {
        val map = buildingMap ?: return
        for (hider in players.values.filter { it.role == Role.HIDER && it.status == PlayerStatus.ACTIVE }) {
            // Players in an open catch claim or dispute are frozen until it is resolved.
            if (catches.values.any { it.isOpen && it.hiderId == hider.id }) continue
            val recent = hider.track.recentUsableFixes(nowMillis)
            val since = hider.insideBuildingSinceMillis
            if (since == null && BuildingRules.isConfidentlyInside(recent, map, rules)) {
                hider.insideBuildingSinceMillis = nowMillis
                hider.buildingWarnings++
            } else if (since != null && BuildingRules.hasLeft(recent, map, rules)) {
                hider.insideBuildingSinceMillis = null
            }
        }
    }

    private fun resolveDispute(claim: CatchClaim, atMillis: Long) {
        val yes = claim.votes.values.count { it }
        val no = claim.votes.size - yes
        val confirmed = if (yes != no) {
            yes > no
        } else {
            // Default rule when nobody voted (or a tie): the most likely GPS distance decides,
            // an unknown distance (the hider sent no fixes) counts for the seeker.
            (claim.estimatedDistanceAtClaimMeters ?: 0.0) <= rules.catchMaxDistanceMeters
        }
        resolve(claim, confirmed, atMillis)
    }

    private fun resolve(claim: CatchClaim, confirmed: Boolean, atMillis: Long) {
        claim.status = if (confirmed) CatchStatus.CONFIRMED else CatchStatus.REJECTED
        claim.deadlineMillis = atMillis
        if (confirmed) {
            player(claim.seekerId).catches++
            val hider = player(claim.hiderId)
            hider.status = PlayerStatus.CAUGHT
            hider.outAtMillis = atMillis
            hider.caughtBy = claim.seekerId
            board.onCatch(claim.seekerId, players.values)
            board.onOut(hider)
            if (players.values.none { it.role == Role.HIDER && it.status == PlayerStatus.ACTIVE }) finish(atMillis)
        }
    }

    private fun finish(atMillis: Long) {
        // Several rules can end the game within one advance() (last catch confirmed, then the zone check):
        // the first one decides when it ended.
        if (phase == GamePhase.FINISHED) return
        enterPhase(GamePhase.FINISHED, atMillis)
        finishedAtMillis = atMillis
        catches.values.filter { it.isOpen }.forEach { it.status = CatchStatus.REJECTED }
    }

    private fun enterPhase(next: GamePhase, atMillis: Long) {
        phase = next
        phaseStartedAtMillis = atMillis
    }

    /**
     * Whether a fix at [atMillis] belongs to [player]'s replay: from the start of hiding until the end of the round, for
     * a hider until they were out. Late fixes (sent after the moment) count by their own time.
     */
    private fun isInRound(player: Player, atMillis: Long): Boolean {
        val start = hidingStartedAtMillis ?: return false
        val end = minOf(finishedAtMillis ?: Long.MAX_VALUE, player.outAtMillis ?: Long.MAX_VALUE)
        return atMillis in start..<end
    }

    /** Everybody but the two in the claim and those who left. */
    private fun eligibleVoters(claim: CatchClaim): Set<PlayerId> =
        players.values.filter { !it.left }.mapTo(HashSet()) { it.id } - setOf(claim.seekerId, claim.hiderId)

    private fun buildRecord(): GameRecord {
        val finishedAt = checkNotNull(finishedAtMillis)
        val zoneStart = zoneStartedAtMillis
        val hiders = players.values.filter { it.role == Role.HIDER }
        val seekersWon = hiders.none { it.status == PlayerStatus.ACTIVE }
        return GameRecord(
            gameId = id,
            createdAtMillis = createdAtMillis,
            startedAtMillis = checkNotNull(hidingStartedAtMillis),
            zoneStartedAtMillis = zoneStart,
            finishedAtMillis = finishedAt,
            settings = settings,
            players = players.size,
            guests = players.values.count { it.userId == null },
            seekers = players.size - hiders.size,
            hidersCaught = hiders.count { it.status == PlayerStatus.CAUGHT },
            hidersEliminated = hiders.count { it.status == PlayerStatus.ELIMINATED },
            catchClaims = catches.size,
            catches = catches.values.count { it.status == CatchStatus.CONFIRMED },
            disputes = catches.values.count { it.wasDisputed },
            chatMessages = lastChatSeq.toInt(),
            buildings = buildingsState,
            results = players.values.mapNotNull { player ->
                val userId = player.userId ?: return@mapNotNull null
                val route = checkNotNull(player.route)
                PlayerResult(
                    userId = userId,
                    role = player.role,
                    status = player.status,
                    won = if (player.role == Role.HIDER) player.status == PlayerStatus.ACTIVE else seekersWon,
                    catchClaims = player.catchClaims,
                    catches = player.catches,
                    survivedSeconds = zoneStart?.takeIf { player.role == Role.HIDER }?.let { start ->
                        (((player.outAtMillis ?: finishedAt) - start) / 1000).coerceAtLeast(0).toInt()
                    },
                    zoneWarnings = player.zoneWarnings,
                    buildingWarnings = player.buildingWarnings,
                    fixes = route.fixes,
                    distanceMeters = route.distanceMeters,
                    movingSeconds = (route.movingMillis / 1000).toInt(),
                    maxSpeedMetersPerSecond = route.maxSpeedMetersPerSecond,
                    route = route.points(),
                    sparks = player.sparks,
                    questsDone = player.questsDone,
                )
            },
        )
    }

    private fun CatchClaim.toView(viewerId: PlayerId) = CatchView(
        id = id,
        seekerId = seekerId,
        hiderId = hiderId,
        status = status,
        createdAtMillis = createdAtMillis,
        deadlineMillis = deadlineMillis,
        canVote = status == CatchStatus.DISPUTED && viewerId in eligibleVoters(this) && viewerId !in votes,
        myVote = votes[viewerId],
    )

    private fun Player.isStale(nowMillis: Long): Boolean {
        val lastFix = lastFixReceivedMillis ?: phaseStartedAtMillis
        return nowMillis - lastFix >= rules.staleLocationRevealSeconds * 1000L
    }

    private fun Player.insideBuildingRevealAtMillis(): Long? =
        insideBuildingSinceMillis?.takeIf { phase == GamePhase.SEEKING && status == PlayerStatus.ACTIVE }
            ?.let { it + rules.insideBuildingRevealSeconds * 1000L }

    private fun Player.isRevealedInsideBuilding(nowMillis: Long): Boolean =
        insideBuildingRevealAtMillis()?.let { nowMillis >= it } == true

    private fun Player.recentlyMocked(nowMillis: Long): Boolean =
        track.lastMockAtMillis?.let { nowMillis - it <= MOCK_REVEAL_MILLIS } == true

    private fun player(id: PlayerId): Player = players[id] ?: throw GameException(ErrorCode.NOT_FOUND, "Unknown player")

    private fun catch(id: CatchId): CatchClaim =
        catches[id] ?: throw GameException(ErrorCode.NOT_FOUND, "Unknown catch claim")

    private fun requireHost(by: PlayerId, what: String) {
        if (by != hostId) throw GameException(ErrorCode.FORBIDDEN, "Only the host can $what")
    }

    private fun requirePhase(expected: GamePhase) {
        if (phase != expected) throw GameException(ErrorCode.WRONG_STATE, "Not possible in phase $phase")
    }

    companion object {
        const val MAX_PLAYERS = 30
        private const val MAX_CATCHES_IN_SNAPSHOT = 20

        /** The zone by streets is given up on (circles instead) after this long; the loader gives up well before. */
        const val STREET_ZONE_PATIENCE_MILLIS = 120_000L
        private const val MOCK_REVEAL_MILLIS = 60_000L

        /** A message is sent again within seconds of the first try: a few ids per player are plenty. */
        private const val CHAT_IDS_KEPT = 20

        /** A phone's report counts this long: the UWB pairing needs both on the screen right now. */
        private const val DEVICE_REPORT_TTL_MILLIS = 20_000L
        private const val MAX_UWB_PEERS = 4
        private const val MAX_SIGHTINGS_PER_SYNC = 200

        /** How far beyond the first circle the host may place items: risky spots outside the shrinking zone. */
        private const val ITEMS_BEYOND_ZONE_SHARE = 0.5
        private const val ITEMS_BEYOND_ZONE_METERS = 100.0

        /** A decoy's spot looks like a fix of the usual accuracy. */
        private const val DECOY_ACCURACY_METERS = 12.0
    }
}

/** A chat message being reported ([Game.reportedMessage]), with what the report keeps about both players. */
class ReportedMessage(
    val message: ChatMessage,
    val senderName: String,
    /** Null: the sender is a guest. */
    val senderUserId: UserId?,
    /** Null: the reporter is a guest. */
    val reporterUserId: UserId?,
) {
    // Never the chat text in logs.
    override fun toString(): String = "ReportedMessage(seq ${message.seq})"
}
