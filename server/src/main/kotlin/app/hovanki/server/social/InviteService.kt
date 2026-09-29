package app.hovanki.server.social

import app.hovanki.server.account.UserRepository
import app.hovanki.server.bigGames.BigGameService
import app.hovanki.server.game.GameException
import app.hovanki.server.game.GameService
import app.hovanki.server.game.IdGenerator
import app.hovanki.server.game.PlayerRef
import app.hovanki.server.ratelimit.RateLimit
import app.hovanki.server.ratelimit.RateLimiter
import app.hovanki.server.social.SocialErrors.accountRequired
import app.hovanki.server.social.SocialErrors.badRequest
import app.hovanki.server.social.SocialErrors.groupNotFound
import app.hovanki.server.social.SocialErrors.notFriends
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameInvite
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.Inbox
import app.hovanki.shared.protocol.InviteId
import app.hovanki.shared.protocol.InviteRequest
import app.hovanki.shared.protocol.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * Game invitations and the inbox (docs/adr/0004-accounts-friends-chat.md). A logged-in player invites friends and/or
 * a whole group they are in while the game is in its lobby; the invitations wait in the invitees' inboxes
 * ([InviteRegistry], in memory) until the game leaves the lobby, [SocialLimits.INVITE_TTL] passes or the invitee
 * joins. Accepting one is joining with its join code while logged in.
 *
 * The game's lock is held only to read the game; the database is read between, never under it.
 */
@Service
class InviteService(
    private val games: GameService,
    private val invites: InviteRegistry,
    private val users: UserRepository,
    private val friends: FriendRepository,
    private val groups: GroupRepository,
    private val blocks: BlockRepository,
    private val ids: IdGenerator,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
    private val bigGames: BigGameService,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    /**
     * Invites [InviteRequest.userIds] (all must be the caller's friends) and/or every member of the group
     * [InviteRequest.groupId] (the caller must be in it; 404 otherwise, as in [GroupService]). Skipped silently: the
     * caller, users who already play in the game, and group members with a block either way. One request counts once
     * towards [RateLimit.INVITES], however many it invites. Returns the caller's snapshot.
     */
    fun invite(caller: PlayerRef, gameId: GameId, request: InviteRequest): GameSnapshot {
        val requested = request.userIds.distinct()
        if (requested.isEmpty() && request.groupId == null) throw badRequest("Nobody to invite")
        if (requested.size > SocialLimits.MAX_FRIENDS) throw badRequest("Too many users")
        val (inviterId, joinCode) = games.withGame(caller, gameId) { game, _ ->
            val inviterId = game.userIdOf(caller.playerId) ?: throw accountRequired("Log in to invite friends")
            requireLobby(game.phase)
            // Into a big game come those who signed up for it (docs/adr/0010-big-games.md).
            if (game.isServerHosted) throw GameException(ErrorCode.WRONG_STATE, "Friends sign up for a big game")
            inviterId to game.joinCode
        }
        rateLimiter.acquire(RateLimit.INVITES, inviterId.value)

        val friendIds = requested - inviterId
        val (group, candidates) = checkNotNull(
            transactions.execute {
                if (friends.friendsAmong(inviterId, friendIds).size < friendIds.size) {
                    throw notFriends("Only your friends can be invited")
                }
                val group = request.groupId?.let { id ->
                    groups.find(id)?.takeIf { groups.isMember(id, inviterId) } ?: throw groupNotFound()
                }
                val members = group?.let { groups.memberIds(it.id) }.orEmpty() - inviterId
                // Friends are never blocked either way (a block ends the friendship), group members may be.
                val blocked = blocks.blockedEitherWayAmong(inviterId, members)
                group to (friendIds.associateWith { false } + (members - blocked).associateWith { true })
            },
        )

        val (invitees, snapshot) = games.withGame(caller, gameId) { game, now ->
            requireLobby(game.phase)
            candidates.filterKeys { game.playerOf(it) == null } to games.snapshotOf(game, caller.playerId, now)
        }
        val now = clock.millis()
        for ((inviteeId, viaGroup) in invitees) {
            val invite = Invite(
                id = ids.inviteId(),
                gameId = gameId,
                joinCode = joinCode,
                inviterId = inviterId,
                inviteeId = inviteeId,
                groupId = group?.id?.takeIf { viaGroup },
                groupName = group?.name?.takeIf { viaGroup },
                createdAtMillis = now,
                expiresAtMillis = now + SocialLimits.INVITE_TTL.toMillis(),
            )
            invites.add(invite)
        }
        return snapshot
    }

    /**
     * The caller's invitations that can still be accepted, newest first, the friend requests to them, and the open
     * lobbies of the big games they signed up for (docs/adr/0010-big-games.md). Invitations
     * into games that are gone or out of the lobby, or that the caller joined, and those from users with a block
     * either way (blocks may come after the invitation) are dropped here.
     */
    fun inbox(userId: UserId): Inbox {
        val pending = invites.of(userId, clock.millis())
        val (open, closed) = pending.partition { games.isOpenFor(it.gameId, userId) }
        invites.remove(closed.map { it.id })
        return checkNotNull(
            transactions.execute {
                val inviters = users.findAll(open.map { it.inviterId }.distinct()).associateBy { it.id }
                val blocked = blocks.blockedEitherWayAmong(userId, inviters.keys)
                val (shown, refused) = open.partition { it.inviterId in inviters && it.inviterId !in blocked }
                invites.remove(refused.map { it.id })
                val views = shown.sortedWith(compareByDescending<Invite> { it.createdAtMillis }.thenBy { it.id.value })
                    .map { invite ->
                        GameInvite(
                            id = invite.id,
                            gameId = invite.gameId,
                            joinCode = invite.joinCode,
                            from = inviters.getValue(invite.inviterId).toSummary(),
                            groupId = invite.groupId,
                            groupName = invite.groupName,
                            createdAtMillis = invite.createdAtMillis,
                            expiresAtMillis = invite.expiresAtMillis,
                        )
                    }
                Inbox(invites = views, friendRequests = friends.incoming(userId).sortedByNickname())
            },
        ).copy(bigGames = bigGames.openLobbiesFor(userId))
    }

    /** Hides one of the caller's invitations; an unknown one (or someone else's) changes nothing. */
    fun dismiss(userId: UserId, inviteId: InviteId): Inbox {
        invites.dismiss(userId, inviteId)
        return inbox(userId)
    }

    private fun requireLobby(phase: GamePhase) {
        if (phase != GamePhase.LOBBY) throw GameException(ErrorCode.WRONG_STATE, "Invitations only in the lobby")
    }
}
