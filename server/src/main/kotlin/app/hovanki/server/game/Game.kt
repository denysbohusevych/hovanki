package app.hovanki.server.game

import app.hovanki.shared.debug.DebugCatch
import app.hovanki.shared.debug.DebugFixCounts
import app.hovanki.shared.debug.DebugGameState
import app.hovanki.shared.debug.DebugPlayer
import app.hovanki.shared.debug.DebugVote
import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.BuildingsState
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
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerTrack
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.TracksResponse
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.protocol.VisibleLocation
import app.hovanki.shared.rules.BuildingMap
import app.hovanki.shared.rules.BuildingRules
import app.hovanki.shared.rules.CatchRules
import app.hovanki.shared.rules.ChatRules
import app.hovanki.shared.rules.LocationTrack
import app.hovanki.shared.rules.RouteRecorder
import app.hovanki.shared.rules.ZoneRules
import app.hovanki.shared.rules.circleAt
import app.hovanki.shared.rules.isUsable
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
    val hostId: PlayerId,
    val settings: GameSettings,
    private val createdAtMillis: Long,
) {
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

    /** The zone's buildings arrived (see `BuildingLoader`): the rule is on from now on. */
    fun onBuildingsLoaded(areas: List<BuildingArea>, passages: List<Passage>) {
        buildingsState = BuildingsState.READY
        buildings = BuildingsResponse(BuildingsState.READY, areas, passages)
        buildingMap = BuildingMap(areas, passages, settings.zone.initial.center)
    }

    /** The zone's buildings can't be loaded: the game runs without the rule, and the players are told. */
    fun onBuildingsUnavailable() {
        buildingsState = BuildingsState.UNAVAILABLE
        buildings = BuildingsResponse(BuildingsState.UNAVAILABLE)
        buildingMap = null
    }

    /** The buildings the rule judges by, for [viewerId] to draw exactly those on the map. */
    fun buildingsFor(viewerId: PlayerId, nowMillis: Long): BuildingsResponse {
        val viewer = player(viewerId)
        if (buildingsState == BuildingsState.READY) viewer.buildingsLoadedAtMillis = nowMillis
        return buildings.copy(state = buildingsState)
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
        players[id] = Player(id, name, LocationTrack(rules), userId, userId?.let { RouteRecorder(rules) })
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

    fun start(by: PlayerId, seekers: Set<PlayerId>, newCatchCodeSecret: () -> String, nowMillis: Long) {
        requirePhase(GamePhase.LOBBY)
        if (by != hostId) throw GameException(ErrorCode.FORBIDDEN, "Only the host can start the game")
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
    fun tracks(): TracksResponse {
        requirePhase(GamePhase.FINISHED)
        return TracksResponse(players.values.map { PlayerTrack(it.id, it.replay.points()) })
    }

    /** Applies everything that happens by itself as time passes. */
    fun advance(nowMillis: Long) {
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
    fun snapshotFor(viewerId: PlayerId, nowMillis: Long, chatAfter: Long? = null): GameSnapshot {
        val viewer = player(viewerId)
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
        )
    }

    fun hasPlayer(id: PlayerId): Boolean = id in players

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
                    revealedToSeekers = revealReason(player, nowMillis),
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
        val fix = target.track.latest ?: return null
        val reason = revealReason(target, nowMillis) ?: return null
        return VisibleLocation(fix.point, fix.accuracyMeters, fix.timestampMillis, reason.forFirstClients(), reason)
    }

    /**
     * `VisibleLocation.reason` has no default: the first app versions fail on a value they don't know. A reason added
     * later goes to `cause` and, in `reason`, becomes the closest one they know.
     */
    private fun VisibilityReason.forFirstClients(): VisibilityReason = when (this) {
        VisibilityReason.INSIDE_BUILDING -> VisibilityReason.OUT_OF_ZONE
        else -> this
    }

    /** Why seekers may see [target] right now, or null when it stays hidden from them. */
    private fun revealReason(target: Player, nowMillis: Long): VisibilityReason? = when {
        phase != GamePhase.HIDING && phase != GamePhase.SEEKING -> null
        target.role == Role.SEEKER -> VisibilityReason.TEAMMATE
        phase != GamePhase.SEEKING || target.status != PlayerStatus.ACTIVE -> null
        target.outOfZoneSinceMillis != null -> VisibilityReason.OUT_OF_ZONE
        target.recentlyMocked(nowMillis) -> VisibilityReason.MOCK_LOCATION
        target.isStale(nowMillis) -> VisibilityReason.STALE_SIGNAL
        target.isRevealedInsideBuilding(nowMillis) -> VisibilityReason.INSIDE_BUILDING
        else -> null
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
        val zone = settings.zone.circleAt(nowMillis - zoneStart)
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

    private fun eligibleVoters(claim: CatchClaim): Set<PlayerId> = players.keys - setOf(claim.seekerId, claim.hiderId)

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
        private const val MAX_CATCHES_IN_SNAPSHOT = 20
        private const val MOCK_REVEAL_MILLIS = 60_000L

        /** A message is sent again within seconds of the first try: a few ids per player are plenty. */
        private const val CHAT_IDS_KEPT = 20
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
