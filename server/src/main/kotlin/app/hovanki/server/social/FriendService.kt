package app.hovanki.server.social

import app.hovanki.server.account.UserRepository
import app.hovanki.server.ratelimit.RateLimit
import app.hovanki.server.ratelimit.RateLimiter
import app.hovanki.server.social.SocialErrors.badRequest
import app.hovanki.server.social.SocialErrors.blockedByYou
import app.hovanki.server.social.SocialErrors.limitReached
import app.hovanki.server.social.SocialErrors.notFound
import app.hovanki.server.social.SocialErrors.userNotFound
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.SendFriendRequest
import app.hovanki.shared.protocol.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

/**
 * Friends and blocks (docs/adr/0004-accounts-friends-chat.md). Every command answers with the caller's fresh
 * [FriendsResponse]. Any account takes part, whether its email is confirmed or not (confirming is optional).
 *
 * Nobody learns that they were blocked: a friend request to someone who blocked the caller is stored like any other
 * (the caller sees it among their outgoing requests, it counts towards their limit), but the one who blocked never
 * sees it ([FriendRepository.incoming]) and it is deleted when they unblock. Blocks end friendships, so friends never
 * block each other.
 *
 * Each change runs in one transaction that first locks the two users ([UserRepository.lock]): two users asking each
 * other at the same moment become friends, and the limits hold under parallel requests.
 */
@Service
class FriendService(
    private val users: UserRepository,
    private val friends: FriendRepository,
    private val blocks: BlockRepository,
    private val groups: GroupRepository,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun friends(userId: UserId): FriendsResponse = inTransaction { view(userId) }

    /**
     * By the exact nickname (any case, [UserRepository.findByNickname]) or by the user id seen in a game. A request to
     * someone who asked the caller makes them friends; asking a friend or asking again changes nothing.
     */
    fun sendRequest(userId: UserId, request: SendFriendRequest): FriendsResponse {
        val nickname = request.nickname?.takeUnless { it.isBlank() }
        if ((nickname == null) == (request.userId == null)) throw badRequest("Either a nickname or a user id")
        // Counts failures too: it also slows down guessing which nicknames exist.
        rateLimiter.acquire(RateLimit.FRIEND_REQUESTS, userId.value)
        val target = (nickname?.let(users::findByNickname) ?: request.userId?.let(users::findById))
            ?: throw userNotFound()
        if (target.id == userId) throw badRequest("You can't be your own friend")
        return inTransaction {
            if (target.id !in users.lock(listOf(userId, target.id))) throw userNotFound()
            when {
                blocks.isBlocked(userId, target.id) -> throw blockedByYou()

                friends.areFriends(userId, target.id) || friends.hasRequest(userId, target.id) -> Unit

                // Never from someone who blocked the caller: a block deletes the requests both ways, and one who
                // blocked can't send any.
                friends.hasRequest(target.id, userId) -> befriend(userId, target.id, clock.instant())

                else -> {
                    checkMaySendRequest(userId)
                    // Hidden from the target if they blocked the caller (see the class comment).
                    friends.addRequest(userId, target.id, clock.instant())
                }
            }
            view(userId)
        }
    }

    /** Accepts [from]'s request; 404 without one. Accepting twice changes nothing. */
    fun accept(userId: UserId, from: UserId): FriendsResponse = inTransaction {
        users.lock(listOf(userId, from))
        if (!friends.areFriends(userId, from)) {
            // A request the caller can't see (they blocked its sender) can't be accepted either.
            if (!friends.hasRequest(from, userId) || blocks.isBlocked(userId, from)) {
                throw notFound("No friend request from this user")
            }
            befriend(userId, from, clock.instant())
        }
        view(userId)
    }

    /** Declines [other]'s request or withdraws the caller's request to them; nothing to do is fine. */
    fun decline(userId: UserId, other: UserId): FriendsResponse = inTransaction {
        friends.removeRequestsBetween(userId, other)
        view(userId)
    }

    /** Ends the friendship both ways; they stay in each other's groups. Idempotent. */
    fun remove(userId: UserId, friend: UserId): FriendsResponse = inTransaction {
        friends.removeFriendship(userId, friend)
        view(userId)
    }

    /**
     * Blocks [target]: ends the friendship and the requests both ways and removes them from the groups the caller
     * owns. Their friend requests and invites to the caller are refused silently from now on. Idempotent.
     */
    fun block(userId: UserId, target: UserId): FriendsResponse {
        if (target == userId) throw badRequest("You can't block yourself")
        return inTransaction {
            if (target !in users.lock(listOf(userId, target))) throw userNotFound()
            blocks.block(userId, target, clock.instant())
            friends.removeFriendship(userId, target)
            friends.removeRequestsBetween(userId, target)
            groups.removeFromOwnedGroups(ownerId = userId, userId = target)
            view(userId)
        }
    }

    /** Idempotent. The requests [target] sent while blocked were never shown: they go with the block. */
    fun unblock(userId: UserId, target: UserId): FriendsResponse = inTransaction {
        users.lock(listOf(userId, target))
        if (blocks.unblock(userId, target)) friends.removeRequest(from = target, to = userId)
        view(userId)
    }

    /** Both have room for one more friend: the friendship starts and the requests between them end. */
    private fun befriend(userId: UserId, other: UserId, now: Instant) {
        if (friends.countFriends(userId) >= SocialLimits.MAX_FRIENDS) throw tooManyFriends()
        if (friends.countFriends(other) >= SocialLimits.MAX_FRIENDS) {
            throw limitReached("They have ${SocialLimits.MAX_FRIENDS} friends already")
        }
        friends.addFriendship(userId, other, now)
        friends.removeRequestsBetween(userId, other)
    }

    private fun checkMaySendRequest(userId: UserId) {
        if (friends.countFriends(userId) >= SocialLimits.MAX_FRIENDS) throw tooManyFriends()
        if (friends.countOutgoing(userId) >= SocialLimits.MAX_OUTGOING_REQUESTS) {
            throw limitReached("${SocialLimits.MAX_OUTGOING_REQUESTS} friend requests are waiting for an answer")
        }
    }

    private fun tooManyFriends() = limitReached("You have ${SocialLimits.MAX_FRIENDS} friends already")

    private fun view(userId: UserId) = FriendsResponse(
        friends = friends.friends(userId).sortedByNickname(),
        incoming = friends.incoming(userId).sortedByNickname(),
        outgoing = friends.outgoing(userId).sortedByNickname(),
        blocked = blocks.blocked(userId).sortedByNickname(),
    )

    private fun <T : Any> inTransaction(block: () -> T): T = checkNotNull(transactions.execute { block() })
}
