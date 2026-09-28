package app.hovanki.server.game

import app.hovanki.server.map.TerrainGrid
import app.hovanki.shared.debug.DebugCatch
import app.hovanki.shared.debug.DebugFixCounts
import app.hovanki.shared.debug.DebugGameState
import app.hovanki.shared.debug.DebugPlayer
import app.hovanki.shared.debug.DebugVote
import app.hovanki.shared.protocol.AdminGame
import app.hovanki.shared.protocol.AdminLiveGame
import app.hovanki.shared.protocol.AreaNorms
import app.hovanki.shared.protocol.BigGameInfo
import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.CapacityState
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.CatchView
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.Passage
import app.hovanki.shared.protocol.PlayerCounts
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerTrack
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.SpectatedPlayer
import app.hovanki.shared.protocol.SpectatorId
import app.hovanki.shared.protocol.SpectatorSnapshot
import app.hovanki.shared.protocol.StreetZoneResponse
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.TerrainAreas
import app.hovanki.shared.protocol.TracksResponse
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.protocol.VisibleLocation
import app.hovanki.shared.protocol.ZoneCapacity
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.rules.BuildingMap
import app.hovanki.shared.rules.BuildingRules
import app.hovanki.shared.rules.Capacity
import app.hovanki.shared.rules.CatchRules
import app.hovanki.shared.rules.ChatRules
import app.hovanki.shared.rules.Glow
import app.hovanki.shared.rules.LocationTrack
import app.hovanki.shared.rules.RouteRecorder
import app.hovanki.shared.rules.StreetZone
import app.hovanki.shared.rules.ZoneRules
import app.hovanki.shared.rules.areaAt
import app.hovanki.shared.rules.circleAt
import app.hovanki.shared.rules.hasPolygons
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
    /** How much ground one player needs (docs/adr/0010-big-games.md): the server's settings when the game was made. */
    private var norms: AreaNorms = AreaNorms(),
    /**
     * A big game's round (docs/adr/0010-big-games.md): the server hosts it ([hostId] is nobody's player), the lobby is
     * never handed over or removed when empty, up to [maxPlayers] come in.
     */
    bigGame: BigGameInfo? = null,
    maxPlayers: Int = MAX_PLAYERS,
) {
    /** The big game this round belongs to; the service updates the title, the time and the count of sign-ups. */
    var bigGame: BigGameInfo? = bigGame
        private set

    /** How many players the lobby takes. */
    var maxPlayers: Int = maxPlayers
        private set

    /** Hosted by the server: nobody's player is the host. */
    val isServerHosted: Boolean get() = bigGame != null

    /**
     * Open to spectators (docs/adr/0011-spectators-and-recordings.md): its host opened it. Never a big game: up to 1 600
     * players, and each of them is shown only a few (docs/adr/0010-big-games.md).
     */
    val isOpenToSpectators: Boolean get() = settings.openGame && !isServerHosted

    /** Starts and sets up the game; when they leave the lobby, the player who joined after them takes over. */
    var hostId: PlayerId = hostId
        private set

    /** The setup; the host may change it in the lobby ([updateSettings]), the thresholds ([rules]) excepted. */
    var settings: GameSettings = settings
        private set
    private val rules = settings.rules
    private val players = LinkedHashMap<PlayerId, Player>()

    /** Who watches this open game without playing it (docs/adr/0011-spectators-and-recordings.md); memory only. */
    private val spectators = LinkedHashMap<SpectatorId, Spectator>()

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

    /**
     * The ground under the zone (docs/adr/0010-big-games.md), for how many players it fits; null until loaded, and when
     * it can't be ([capacityState] says which).
     */
    private var terrain: TerrainGrid? = null
    private var capacityState = CapacityState.LOADING
    private var capacityAreas: TerrainAreas? = null

    /** Since when the ground is being read: after [MAP_PATIENCE_MILLIS] the game goes without an estimate. */
    private var terrainSinceMillis = createdAtMillis

    /** The host chose to play in a crowded zone, or one with few places to hide: no more warning in this game. */
    private var crowdingAccepted = false

    /** When [advance] last looked at the hiders (zone, buildings, glow); see [BIG_GAME_CHECKS_MILLIS]. */
    private var lastChecksMillis: Long? = null

    /** The last glow that left its marks on the hiders ([updateGlow]); 0: none yet. */
    private var glowMarksOf = 0

    init {
        if (settings.zoneShape.hasPolygons) streetZoneState = StreetZoneState.LOADING
    }

    /**
     * The service's news about the big game: its title, time and sign-ups, the limit and the norms an admin changed.
     * Only for a big game's round.
     */
    fun updateBigGame(info: BigGameInfo, maxPlayers: Int, norms: AreaNorms) {
        check(isServerHosted) { "Not a big game" }
        bigGame = info
        this.maxPlayers = maxPlayers
        if (norms != this.norms) this.norms = norms
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
        if (revision != mapRevision || !settings.zoneShape.hasPolygons) return
        if (stages.size != settings.zone.stages.size + 1 || stages.any { it.outline.size < 4 }) {
            onStreetZoneUnavailable(revision)
            return
        }
        streetZone = StreetZone(stages)
        streetZoneState = StreetZoneState.READY
        countCapacity()
    }

    /**
     * No zone by streets for [revision] (no streets, map data down): the game uses the circles, the players are told.
     */
    fun onStreetZoneUnavailable(revision: Int = mapRevision) {
        if (revision != mapRevision || !settings.zoneShape.hasPolygons) return
        streetZone = null
        streetZoneState = StreetZoneState.UNAVAILABLE
        countCapacity()
    }

    /** The ground under the zone of [revision] was read: how many players it fits is known from now on. */
    fun onTerrainLoaded(grid: TerrainGrid, revision: Int = mapRevision) {
        if (revision != mapRevision) return
        terrain = grid
        countCapacity()
    }

    /** The ground under the zone can't be read: no estimate, no warning. */
    fun onTerrainUnavailable(revision: Int = mapRevision) {
        if (revision != mapRevision) return
        terrain = null
        capacityAreas = null
        capacityState = CapacityState.UNAVAILABLE
    }

    /**
     * The host plays anyway (docs/adr/0010-big-games.md): the zone fits fewer players than there are, or has few places
     * to hide. The lobby warns no more in this game, whatever the zone becomes.
     */
    fun acceptCrowding(by: PlayerId, nowMillis: Long) {
        requirePhase(GamePhase.LOBBY)
        requireHost(by, "decide to play anyway")
        crowdingAccepted = true
        lastActivityMillis = nowMillis
    }

    /** About how many players the zone at the start fits, as the lobby shows it. */
    fun capacity(): ZoneCapacity {
        val areas = capacityAreas
        return ZoneCapacity(
            state = capacityState,
            players = areas?.let { Capacity.players(it, norms) },
            areas = areas,
            fewCovers = areas?.let(Capacity::fewCovers) == true,
            accepted = crowdingAccepted,
        )
    }

    /**
     * The ground within the zone at the start: the zone by streets once it is there, the circle meanwhile (and when it
     * could not be built).
     */
    private fun countCapacity() {
        val grid = terrain ?: return
        capacityAreas = grid.areasWithin(settings.zone.areaAt(0, streetZone))
        capacityState = CapacityState.READY
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
        if (players.size >= maxPlayers) throw GameException(ErrorCode.WRONG_STATE, "The game is full")
        if (userId != null && playerOf(userId) != null) {
            throw GameException(ErrorCode.WRONG_STATE, "This account already plays in this game")
        }
        // Only players with an account have a history; a guest's route is never even kept in memory.
        players[id] = Player(id, name, LocationTrack(rules), userId, userId?.let { RouteRecorder(rules) })
        if (joinRequestId != null) playersByJoinRequest[joinRequestId] = id
        // Watching it until now: a player never sees everybody.
        if (userId != null) spectators.values.removeIf { it.userId == userId }
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
        lastActivityMillis = nowMillis
        if (mapChanged) {
            mapRevision++
            buildingsState = BuildingsState.LOADING
            buildings = BuildingsResponse()
            buildingMap = null
            streetZone = null
            streetZoneState = if (next.zoneShape.hasPolygons) StreetZoneState.LOADING else null
            streetZoneSinceMillis = nowMillis
            terrain = null
            capacityAreas = null
            capacityState = CapacityState.LOADING
            terrainSinceMillis = nowMillis
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
                // A big game's lobby waits for its start, empty or not; the server stays its host.
                if (isServerHosted) return false
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
        if (settings.zoneShape.hasPolygons && streetZoneState == StreetZoneState.LOADING) {
            throw GameException(ErrorCode.WRONG_STATE, "The zone by streets is being built", ErrorReason.ZONE_NOT_READY)
        }
        if (seekers.isEmpty() || !players.keys.containsAll(seekers)) {
            throw GameException(ErrorCode.BAD_REQUEST, "Pick at least one seeker among the players")
        }
        if (seekers.size == players.size) throw GameException(ErrorCode.BAD_REQUEST, "At least one hider is needed")

        for (player in players.values) {
            player.role = if (player.id in seekers) Role.SEEKER else Role.HIDER
            if (player.role == Role.HIDER) player.catchCodeSecret = newCatchCodeSecret()
        }
        enterPhase(GamePhase.HIDING, nowMillis)
        hidingStartedAtMillis = nowMillis
        lastActivityMillis = nowMillis
    }

    /**
     * The server starts a big game's round (docs/adr/0010-big-games.md): it draws [seekers] seekers with [random] among
     * the players in the lobby (at least one, and at least one hider), the others hide. [ErrorCode.WRONG_STATE] with
     * fewer than two players.
     */
    fun startByServer(seekers: Int, random: java.util.Random, newCatchCodeSecret: () -> String, nowMillis: Long) {
        check(isServerHosted) { "Not a big game" }
        requirePhase(GamePhase.LOBBY)
        if (players.size < 2) throw GameException(ErrorCode.WRONG_STATE, "A round needs at least two players")
        val count = seekers.coerceIn(1, players.size - 1)
        val drawn = players.keys.shuffled(random).take(count).toSet()
        rolesDrawnAtMillis = nowMillis
        start(hostId, drawn, newCatchCodeSecret, nowMillis)
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
            if (inRound) player.route?.add(fix)
            if (fix.isUsable(rules) && isInRound(player, fix.timestampMillis)) player.replay.add(fix)
        }
        lastActivityMillis = nowMillis
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
    fun tracks(viewerId: PlayerId? = null): TracksResponse {
        requirePhase(GamePhase.FINISHED)
        // A big game's replay: the viewer and their friends, not a thousand tracks.
        val shown = if (isServerHosted && viewerId != null) {
            val viewer = player(viewerId)
            players.values.filter { it === viewer || it.userId in viewer.friends }
        } else {
            players.values
        }
        return TracksResponse(shown.map { PlayerTrack(it.id, it.replay.points()) })
    }

    /** [playerId]'s friends among the accounts (loaded at the join): a big game's snapshot shows them. */
    fun setFriends(playerId: PlayerId, friends: Set<UserId>) {
        player(playerId).friends = friends
    }

    /** Applies everything that happens by itself as time passes. */
    fun advance(nowMillis: Long) {
        val streetZoneOverdue = nowMillis - streetZoneSinceMillis >= STREET_ZONE_PATIENCE_MILLIS
        if (streetZoneState == StreetZoneState.LOADING && streetZoneOverdue) {
            onStreetZoneUnavailable()
        }
        if (capacityState == CapacityState.LOADING && nowMillis - terrainSinceMillis >= MAP_PATIENCE_MILLIS) {
            onTerrainUnavailable()
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
        // A big game's hundreds of hiders are looked at once a second, not on each of hundreds of requests a second.
        val lastChecks = lastChecksMillis
        if (!isServerHosted || lastChecks == null || nowMillis - lastChecks >= BIG_GAME_CHECKS_MILLIS) {
            lastChecksMillis = nowMillis
            checkZone(nowMillis)
            checkBuildings(nowMillis)
            updateGlow(nowMillis)
        }
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

    /** When the round ends (or ended): null in the lobby. */
    fun roundEndsAtMillis(): Long? = when (phase) {
        GamePhase.LOBBY -> null
        GamePhase.HIDING -> phaseStartedAtMillis + (settings.hidingSeconds + settings.seekingSeconds) * 1000L
        GamePhase.SEEKING -> phaseStartedAtMillis + settings.seekingSeconds * 1000L
        GamePhase.FINISHED -> finishedAtMillis
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
    fun snapshotFor(viewerId: PlayerId, nowMillis: Long, chatAfter: Long? = null): GameSnapshot {
        val viewer = player(viewerId)
        // Every request ends in a snapshot for its player: the lobby shows who is connected.
        viewer.lastSeenMillis = nowMillis
        return GameSnapshot(
            gameId = id,
            joinCode = joinCode,
            hostId = hostId,
            phase = phase,
            settings = settings,
            serverTimeMillis = nowMillis,
            phaseEndsAtMillis = phaseEndsAtMillis(),
            zoneStartedAtMillis = zoneStartedAtMillis,
            players = shownTo(viewer, nowMillis).map { (player, location) ->
                PlayerView(
                    player.id,
                    player.name,
                    player.role,
                    player.status,
                    location,
                    player.userId,
                    player.outAtMillis,
                    player.caughtBy,
                    lastSeenMillis = player.lastSeenMillis,
                    left = player.left,
                )
            },
            me = MyState(
                playerId = viewer.id,
                role = viewer.role,
                status = viewer.status,
                catchCodeSecret = viewer.catchCodeSecret,
                outOfZoneDeadlineMillis = viewer.outOfZoneDeadlineMillis(),
                insideBuildingRevealAtMillis = viewer.insideBuildingRevealAtMillis(),
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
            capacity = capacity(),
            bigGame = bigGame,
            counts = if (isServerHosted) counts() else null,
            spectators = spectatorCount(nowMillis),
        )
    }

    /**
     * The players [viewer] gets, each with the position they may see. Everybody in an ordinary game; in a big game
     * (docs/adr/0010-big-games.md) only the viewer, whom they see, who is in their catch claims, and their friends: a
     * poll of a thousand players brings a few dozen.
     */
    private fun shownTo(viewer: Player, nowMillis: Long): List<Pair<Player, VisibleLocation?>> {
        if (!isServerHosted) return players.values.map { it to visibleLocation(viewer, it, nowMillis) }
        val inClaims = catches.values.filter { it.seekerId == viewer.id || it.hiderId == viewer.id }
            .flatMapTo(HashSet()) { listOf(it.seekerId, it.hiderId) }
        return players.values.mapNotNull { player ->
            val location = visibleLocation(viewer, player, nowMillis)
            val shown = player === viewer || location != null || player.id in inClaims ||
                (player.userId != null && player.userId in viewer.friends)
            if (shown) player to location else null
        }
    }

    /** How many players there are in all, by role and state. */
    private fun counts(): PlayerCounts {
        val hiders = players.values.filter { it.role == Role.HIDER }
        return PlayerCounts(
            players = players.size,
            seekers = players.size - hiders.size,
            hidersActive = hiders.count { it.status == PlayerStatus.ACTIVE },
            hidersCaught = hiders.count { it.status == PlayerStatus.CAUGHT },
            hidersEliminated = hiders.count { it.status == PlayerStatus.ELIMINATED },
        )
    }

    fun hasPlayer(id: PlayerId): Boolean = id in players

    /** Everybody who came in (who left the lobby is gone; who left the round still counts). */
    fun playerCount(): Int = players.size

    // ---- Spectators (docs/adr/0011-spectators-and-recordings.md) ----

    /**
     * [userId] watches this game, as [spectatorId]: only an open one, and never a game they play in (they would see
     * everybody). The same account watching again (a second phone, a reinstalled app) gets its spectator back: the id
     * it watches as.
     */
    fun watch(spectatorId: SpectatorId, userId: UserId, nowMillis: Long): SpectatorId {
        if (!isOpenToSpectators) {
            throw GameException(ErrorCode.FORBIDDEN, "This game is not open to spectators", ErrorReason.GAME_NOT_OPEN)
        }
        if (playerOf(userId) != null) {
            throw GameException(ErrorCode.FORBIDDEN, "You play in this game", ErrorReason.PLAYING_THIS_GAME)
        }
        spectators.values.firstOrNull { it.userId == userId }?.let { existing ->
            existing.lastSeenMillis = nowMillis
            return existing.id
        }
        if (spectators.size >= MAX_SPECTATORS) {
            throw GameException(ErrorCode.WRONG_STATE, "Too many spectators", ErrorReason.LIMIT_REACHED)
        }
        spectators[spectatorId] = Spectator(spectatorId, userId, nowMillis)
        return spectatorId
    }

    fun stopWatching(spectatorId: SpectatorId) {
        spectators.remove(spectatorId)
    }

    /** The game is not open any more: everybody watching it stops. Their ids, for their tokens. */
    fun dropSpectators(): List<SpectatorId> = spectators.keys.toList().also { spectators.clear() }

    /** How many watch right now: those who asked for the game within the last half minute. */
    fun spectatorCount(nowMillis: Long): Int =
        spectators.values.count { nowMillis - it.lastSeenMillis < SPECTATOR_ACTIVE_MILLIS }

    /**
     * The game as [spectatorId] sees it: everybody, [GameSettings.spectatorDelaySeconds] behind. Only what was so at
     * that moment: nothing the game would give away that happened since.
     */
    fun spectatorSnapshot(spectatorId: SpectatorId, nowMillis: Long): SpectatorSnapshot {
        val spectator = spectators[spectatorId] ?: throw GameException(ErrorCode.NOT_FOUND, "Not watching this game")
        spectator.lastSeenMillis = nowMillis
        val delay = settings.spectatorDelaySeconds
        val at = nowMillis - delay * 1000L
        val moment = momentAt(at, SPECTATOR_TRAIL_MILLIS)
        return SpectatorSnapshot(
            gameId = id,
            settings = settings,
            serverTimeMillis = nowMillis,
            atMillis = at,
            delaySeconds = delay,
            phase = moment.phase,
            phaseEndsAtMillis = moment.phaseEndsAtMillis,
            zoneStartedAtMillis = moment.zoneStartedAtMillis,
            finishedAtMillis = moment.finishedAtMillis,
            players = moment.players,
            spectators = spectatorCount(nowMillis),
            streetZone = streetZoneState,
            mapRevision = mapRevision,
        )
    }

    /** The zone by streets for [spectatorId]'s map, as players get it. */
    fun streetZoneForSpectator(spectatorId: SpectatorId): StreetZoneResponse {
        requireSpectator(spectatorId)
        return StreetZoneResponse(streetZoneState, mapRevision, streetZone?.stages.orEmpty())
    }

    /**
     * An open game right now, for an admin who watches it: live, everybody with the last two minutes of their way, the
     * zone and the buildings. Only open games.
     */
    fun liveView(nowMillis: Long): AdminLiveGame {
        if (!isOpenToSpectators) {
            throw GameException(ErrorCode.FORBIDDEN, "This game is not open to spectators", ErrorReason.GAME_NOT_OPEN)
        }
        val moment = momentAt(nowMillis, ADMIN_TRAIL_MILLIS)
        val zone = moment.zoneStartedAtMillis?.let { settings.zone.stateAt(nowMillis - it) }
        return AdminLiveGame(
            gameId = id,
            phase = moment.phase,
            serverTimeMillis = nowMillis,
            settings = settings,
            zoneStartedAtMillis = moment.zoneStartedAtMillis,
            phaseEndsAtMillis = moment.phaseEndsAtMillis,
            streetZone = streetZone?.stages,
            buildings = if (buildingsState == BuildingsState.READY) buildings.buildings else emptyList(),
            players = moment.players,
            spectators = spectatorCount(nowMillis),
            zoneNow = zone?.current,
            nextZone = zone?.next,
            zoneStage = zone?.stage ?: 0,
        )
    }

    private fun requireSpectator(spectatorId: SpectatorId) {
        if (spectatorId !in spectators) throw GameException(ErrorCode.NOT_FOUND, "Not watching this game")
    }

    private class Moment(
        val phase: GamePhase,
        val phaseEndsAtMillis: Long?,
        val zoneStartedAtMillis: Long?,
        val finishedAtMillis: Long?,
        val players: List<SpectatedPlayer>,
    )

    /**
     * The game as it was at [atMillis]: the phase then, everybody's status then (a hider caught later is still
     * playing), where each of them was (their last point of the round before it) and their way over [trailMillis].
     */
    private fun momentAt(atMillis: Long, trailMillis: Long): Moment {
        val hidingStart = hidingStartedAtMillis
        val seekingStart = zoneStartedAtMillis
        val finished = finishedAtMillis
        val phaseThen = when {
            hidingStart == null || atMillis < hidingStart -> GamePhase.LOBBY
            seekingStart == null || atMillis < seekingStart -> GamePhase.HIDING
            finished == null || atMillis < finished -> GamePhase.SEEKING
            else -> GamePhase.FINISHED
        }
        val endsAt = when (phaseThen) {
            GamePhase.HIDING -> hidingStart?.plus(settings.hidingSeconds * 1000L)
            GamePhase.SEEKING -> seekingStart?.plus(settings.seekingSeconds * 1000L)
            else -> null
        }
        return Moment(
            phase = phaseThen,
            phaseEndsAtMillis = endsAt,
            zoneStartedAtMillis = seekingStart?.takeIf { atMillis >= it },
            finishedAtMillis = finished?.takeIf { atMillis >= it },
            players = players.values.map { player ->
                val outThen = player.outAtMillis?.takeIf { it <= atMillis }
                val points = player.replay.points().filter { it.atMillis <= atMillis }
                SpectatedPlayer(
                    id = player.id,
                    name = player.name,
                    role = player.role,
                    status = if (outThen != null) player.status else PlayerStatus.ACTIVE,
                    location = points.lastOrNull(),
                    trail = points.filter { it.atMillis > atMillis - trailMillis },
                    outAtMillis = outThen,
                    caughtBy = player.caughtBy.takeIf { outThen != null },
                )
            },
        )
    }

    /** What staff see of this game in the admin (docs/adr/0008-admin.md): no zone center, no positions, no chat. */
    fun adminView(nowMillis: Long): AdminGame = AdminGame(
        gameId = id,
        phase = phase,
        hostName = players[hostId]?.name ?: if (isServerHosted) SERVER_HOST_NAME else "",
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
        capacity = capacity().players,
        crowdingAccepted = crowdingAccepted,
        bigGameId = bigGame?.id,
        openGame = isOpenToSpectators,
        spectators = spectatorCount(nowMillis),
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
            capacity = capacity(),
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
                    revealedToSeekers = revealReason(player, nowMillis)
                        ?: VisibilityReason.GLOW.takeIf { glowMarkShown(player) },
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
        )
    }

    private fun visibleLocation(viewer: Player, target: Player, nowMillis: Long): VisibleLocation? {
        if (viewer.id == target.id || viewer.role != Role.SEEKER) return null
        val reason = revealReason(target, nowMillis)
        // Between glows: where the last glow left the hider, not where they are now.
        val fix = when {
            reason != null -> target.track.latest
            glowMarkShown(target) -> target.glowMark
            else -> null
        } ?: return null
        val cause = reason ?: VisibilityReason.GLOW
        return VisibleLocation(fix.point, fix.accuracyMeters, fix.timestampMillis, cause.forFirstClients(), cause)
    }

    /**
     * `VisibleLocation.reason` has no default: the first app versions fail on a value they don't know. A reason added
     * later goes to `cause` and, in `reason`, becomes the closest one they know.
     */
    private fun VisibilityReason.forFirstClients(): VisibilityReason = when (this) {
        VisibilityReason.INSIDE_BUILDING, VisibilityReason.GLOW -> VisibilityReason.OUT_OF_ZONE
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
        isGlowing(nowMillis) -> VisibilityReason.GLOW
        else -> null
    }

    /** A glow is on: the seekers see every active hider live (docs/adr/0009-game-setup-glow-streets.md). */
    private fun isGlowing(nowMillis: Long): Boolean {
        val seekingStart = zoneStartedAtMillis ?: return false
        return phase == GamePhase.SEEKING && Glow.openAt(settings, seekingStart, nowMillis) != null
    }

    /** Between glows the seekers see the spot where the last one left an active hider. */
    private fun glowMarkShown(target: Player): Boolean = phase == GamePhase.SEEKING && !target.left &&
        target.role == Role.HIDER && target.status == PlayerStatus.ACTIVE && target.glowMark != null

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
            hider.track.latestAtOrBefore(last.endMillis - 1)?.let { hider.glowMark = it }
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
     * Inside a building: warned as soon as the server is confident (the dot of most recent fixes inside, see
     * [BuildingRules]), revealed to the seekers after [GameRules.insideBuildingRevealSeconds]. Never eliminated: GPS
     * near houses is a hint, not a judge. Out again, also judged on several fixes, lifts the warning and the reveal.
     */
    private fun checkBuildings(nowMillis: Long) {
        val map = buildingMap ?: return
        for (hider in players.values.filter { it.role == Role.HIDER && it.status == PlayerStatus.ACTIVE }) {
            // Players in an open catch claim or dispute are frozen until it is resolved.
            if (catches.values.any { it.isOpen && it.hiderId == hider.id }) continue
            // Of any accuracy: indoors GPS is worse than the other rules accept, the rule filters for itself.
            val recent = hider.track.recentFixes(nowMillis)
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
    private fun eligibleVoters(claim: CatchClaim): Set<PlayerId> {
        // A big game: nobody votes, the rule by GPS decides a dispute at once (a vote of a thousand is no vote).
        if (isServerHosted) return emptySet()
        return players.values.filter { !it.left }.mapTo(HashSet()) { it.id } - setOf(claim.seekerId, claim.hiderId)
    }

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
            streetZone = streetZone?.stages,
            // Not a big game's: a thousand ways, and each of its players is shown only their own and their friends'.
            recording = if (isServerHosted) emptyList() else players.values.map { player ->
                RecordedTrack(
                    playerId = player.id,
                    userId = player.userId,
                    name = player.name,
                    role = player.role,
                    status = player.status,
                    outAtMillis = player.outAtMillis,
                    caughtBy = player.caughtBy,
                    points = player.replay.points(),
                )
            },
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

    private class Player(
        val id: PlayerId,
        val name: String,
        val track: LocationTrack,
        val userId: UserId?,
        /** The whole round, for the history; players with an account only. */
        val route: RouteRecorder?,
    ) {
        var role: Role = Role.HIDER
        var status: PlayerStatus = PlayerStatus.ACTIVE

        /** When a hider was caught or eliminated, and by whom they were caught. */
        var outAtMillis: Long? = null
        var caughtBy: PlayerId? = null
        var catchClaims = 0
        var catches = 0
        var zoneWarnings = 0
        var buildingWarnings = 0
        var catchCodeSecret: String? = null
        var lastFixReceivedMillis: Long? = null
        var outOfZoneSinceMillis: Long? = null
        var insideBuildingSinceMillis: Long? = null

        /** Left the game for good (the leave button, or joining another game). */
        var left = false

        /** Server time of the player's last request. */
        var lastSeenMillis: Long? = null

        /** Where the last glow left this hider: what the seekers see between glows. */
        var glowMark: LocationSample? = null

        /** The accounts of the player's friends, loaded at the join of a big game: its snapshot shows them. */
        var friends: Set<UserId> = emptySet()

        /**
         * The whole round, thinned, for the replay right after it: every player, only in memory (unlike [route], which
         * may be saved to the history).
         */
        val replay = ReplayTrack()

        /** When the player's app last fetched the READY buildings (for the e2e observer). */
        var buildingsLoadedAtMillis: Long? = null
        val fixResults = HashMap<LocationTrack.Result, Int>()

        /** When the player's recent chat messages were sent, oldest first (the chat's rate limit). */
        val chatSentAtMillis = ArrayDeque<Long>()

        /** The player's last [CHAT_IDS_KEPT] messages by the app's id for them (`SendChatRequest.clientMessageId`). */
        val chatByClientId = LinkedHashMap<String, ChatMessage>()
    }

    private class Spectator(val id: SpectatorId, val userId: UserId, var lastSeenMillis: Long)

    private class CatchClaim(
        val id: CatchId,
        val seekerId: PlayerId,
        val hiderId: PlayerId,
        val createdAtMillis: Long,
        var deadlineMillis: Long,
        val estimatedDistanceAtClaimMeters: Double?,
    ) {
        var status: CatchStatus = CatchStatus.AWAITING_CODE
        var wasDisputed = false
        var failedAttempts = 0
        val votes = LinkedHashMap<PlayerId, Boolean>()
        val isOpen get() = status == CatchStatus.AWAITING_CODE || status == CatchStatus.DISPUTED
    }

    companion object {
        const val MAX_PLAYERS = 30

        /** How often [advance] looks at a big game's hiders. */
        const val BIG_GAME_CHECKS_MILLIS = 1_000L

        /** The host of a big game's round: the server, nobody's player. */
        val SERVER_HOST = PlayerId("server")
        const val SERVER_HOST_NAME = "server"
        private const val MAX_CATCHES_IN_SNAPSHOT = 20

        /** The zone by streets is given up on (circles instead) after this long; the loader gives up well before. */
        const val STREET_ZONE_PATIENCE_MILLIS = 120_000L

        /** The ground under the zone is given up on (no estimate) after this long; the loader gives up well before. */
        const val MAP_PATIENCE_MILLIS = 120_000L
        private const val MOCK_REVEAL_MILLIS = 60_000L

        /** A message is sent again within seconds of the first try: a few ids per player are plenty. */
        private const val CHAT_IDS_KEPT = 20

        const val MAX_SPECTATORS = 50

        /** A spectator counts as watching while their app asked within this long (it asks every few seconds). */
        private const val SPECTATOR_ACTIVE_MILLIS = 30_000L

        /** How much of everybody's way spectators see behind them, and admins watching live. */
        private const val SPECTATOR_TRAIL_MILLIS = 60_000L
        private const val ADMIN_TRAIL_MILLIS = 120_000L
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
