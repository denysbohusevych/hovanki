package app.hovanki.server.game

import app.hovanki.shared.debug.DebugCatch
import app.hovanki.shared.debug.DebugFixCounts
import app.hovanki.shared.debug.DebugGameState
import app.hovanki.shared.debug.DebugPlayer
import app.hovanki.shared.debug.DebugVote
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.CatchView
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.protocol.VisibleLocation
import app.hovanki.shared.rules.CatchRules
import app.hovanki.shared.rules.LocationTrack
import app.hovanki.shared.rules.ZoneRules
import app.hovanki.shared.rules.circleAt
import app.hovanki.shared.totp.catchCodeTotp

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
    createdAtMillis: Long,
) {
    private val rules = settings.rules
    private val players = LinkedHashMap<PlayerId, Player>()
    private val catches = LinkedHashMap<CatchId, CatchClaim>()

    var phase: GamePhase = GamePhase.LOBBY
        private set
    private var phaseStartedAtMillis = createdAtMillis
    private var zoneStartedAtMillis: Long? = null
    private var finishedAtMillis: Long? = null
    private var lastActivityMillis = createdAtMillis

    fun addPlayer(id: PlayerId, name: String, nowMillis: Long) {
        requirePhase(GamePhase.LOBBY)
        if (players.size >= MAX_PLAYERS) throw GameException(ErrorCode.WRONG_STATE, "The game is full")
        players[id] = Player(id, name, LocationTrack(rules))
        lastActivityMillis = nowMillis
    }

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
        lastActivityMillis = nowMillis
    }

    fun recordLocations(playerId: PlayerId, samples: List<LocationSample>, nowMillis: Long) {
        val player = player(playerId)
        for (sample in samples.sortedBy { it.timestampMillis }) {
            // Never trust a timestamp from the future.
            val result = player.track.add(sample.copy(timestampMillis = minOf(sample.timestampMillis, nowMillis)))
            player.fixResults[result] = (player.fixResults[result] ?: 0) + 1
            // Staleness is about location updates, not requests: an app with GPS off still syncs.
            if (result == LocationTrack.Result.ACCEPTED) player.lastFixReceivedMillis = nowMillis
        }
        lastActivityMillis = nowMillis
    }

    fun claimCatch(seekerId: PlayerId, hiderId: PlayerId, catchId: CatchId, nowMillis: Long) {
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
        lastActivityMillis = nowMillis
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
        val seekingEnds = phaseStartedAtMillis + settings.seekingSeconds * 1000L
        if (phase == GamePhase.SEEKING && nowMillis >= seekingEnds) finish(seekingEnds)
    }

    fun isExpired(nowMillis: Long, finishedRetentionMillis: Long, idleRetentionMillis: Long): Boolean {
        val finishedAt = finishedAtMillis
        return (finishedAt != null && nowMillis - finishedAt >= finishedRetentionMillis) ||
            nowMillis - lastActivityMillis >= idleRetentionMillis
    }

    /** State as [viewerId] is allowed to see it. */
    fun snapshotFor(viewerId: PlayerId, nowMillis: Long): GameSnapshot {
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
                )
            },
            me = MyState(
                playerId = viewer.id,
                role = viewer.role,
                status = viewer.status,
                catchCodeSecret = viewer.catchCodeSecret,
                outOfZoneDeadlineMillis = viewer.outOfZoneDeadlineMillis(),
            ),
            catches = catches.values
                .filter { it.seekerId == viewerId || it.hiderId == viewerId || viewerId in eligibleVoters(it) }
                .takeLast(MAX_CATCHES_IN_SNAPSHOT)
                .map { it.toView(viewerId) },
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
                    revealedToSeekers = revealReason(player, nowMillis),
                    catchCodeSecret = player.catchCodeSecret,
                    fixes = DebugFixCounts(
                        accepted = player.fixResults[LocationTrack.Result.ACCEPTED] ?: 0,
                        mock = player.fixResults[LocationTrack.Result.MOCK] ?: 0,
                        outOfOrder = player.fixResults[LocationTrack.Result.OUT_OF_ORDER] ?: 0,
                        implausible = player.fixResults[LocationTrack.Result.IMPLAUSIBLE] ?: 0,
                    ),
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
        )
    }

    private fun visibleLocation(viewer: Player, target: Player, nowMillis: Long): VisibleLocation? {
        if (viewer.id == target.id || viewer.role != Role.SEEKER) return null
        val fix = target.track.latest ?: return null
        val reason = revealReason(target, nowMillis) ?: return null
        return VisibleLocation(fix.point, fix.accuracyMeters, fix.timestampMillis, reason)
    }

    /** Why seekers may see [target] right now, or null when it stays hidden from them. */
    private fun revealReason(target: Player, nowMillis: Long): VisibilityReason? = when {
        phase != GamePhase.HIDING && phase != GamePhase.SEEKING -> null
        target.role == Role.SEEKER -> VisibilityReason.TEAMMATE
        phase != GamePhase.SEEKING || target.status != PlayerStatus.ACTIVE -> null
        target.outOfZoneSinceMillis != null -> VisibilityReason.OUT_OF_ZONE
        target.recentlyMocked(nowMillis) -> VisibilityReason.MOCK_LOCATION
        target.isStale(nowMillis) -> VisibilityReason.STALE_SIGNAL
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
        for (hider in players.values.filter { it.role == Role.HIDER && it.status == PlayerStatus.ACTIVE }) {
            // Players in an open catch claim or dispute are frozen until it is resolved.
            if (catches.values.any { it.isOpen && it.hiderId == hider.id }) continue
            val recent = hider.track.recentUsableFixes(nowMillis)
            val since = hider.outOfZoneSinceMillis
            when {
                ZoneRules.isConfidentlyOutside(recent, zone, rules) -> {
                    if (since == null) {
                        hider.outOfZoneSinceMillis = nowMillis
                    } else if (nowMillis - since >= rules.outOfZoneGraceSeconds * 1000L) {
                        hider.status = PlayerStatus.ELIMINATED
                        hider.outOfZoneSinceMillis = null
                    }
                }

                // Back inside, judged on several fixes like leaving: one fix that jumps inside lifts nothing.
                since != null && ZoneRules.isConfidentlyBack(recent, zone, rules) -> {
                    hider.outOfZoneSinceMillis = null
                }
            }
        }
        if (players.values.none { it.role == Role.HIDER && it.status == PlayerStatus.ACTIVE }) finish(nowMillis)
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
            player(claim.hiderId).status = PlayerStatus.CAUGHT
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

    private fun eligibleVoters(claim: CatchClaim): Set<PlayerId> = players.keys - setOf(claim.seekerId, claim.hiderId)

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

    private fun Player.recentlyMocked(nowMillis: Long): Boolean =
        track.lastMockAtMillis?.let { nowMillis - it <= MOCK_REVEAL_MILLIS } == true

    private fun player(id: PlayerId): Player = players[id] ?: throw GameException(ErrorCode.NOT_FOUND, "Unknown player")

    private fun catch(id: CatchId): CatchClaim =
        catches[id] ?: throw GameException(ErrorCode.NOT_FOUND, "Unknown catch claim")

    private fun requirePhase(expected: GamePhase) {
        if (phase != expected) throw GameException(ErrorCode.WRONG_STATE, "Not possible in phase $phase")
    }

    private class Player(val id: PlayerId, val name: String, val track: LocationTrack) {
        var role: Role = Role.HIDER
        var status: PlayerStatus = PlayerStatus.ACTIVE
        var catchCodeSecret: String? = null
        var lastFixReceivedMillis: Long? = null
        var outOfZoneSinceMillis: Long? = null
        val fixResults = HashMap<LocationTrack.Result, Int>()
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
        var failedAttempts = 0
        val votes = LinkedHashMap<PlayerId, Boolean>()
        val isOpen get() = status == CatchStatus.AWAITING_CODE || status == CatchStatus.DISPUTED
    }

    companion object {
        const val MAX_PLAYERS = 30
        private const val MAX_CATCHES_IN_SNAPSHOT = 20
        private const val MOCK_REVEAL_MILLIS = 60_000L
    }
}
