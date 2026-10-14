package app.hovanki.server.social

import app.hovanki.server.account.BeforeAccountDeletion
import app.hovanki.server.db.ReadCache
import app.hovanki.shared.protocol.Inbox
import app.hovanki.shared.protocol.UserId
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The part of a user's inbox read from the database, the friend requests to them and the open lobbies of the big games
 * they signed up for, kept in memory for [SocialProperties.inboxCacheTtl] ([InviteService.inbox]): the app asks for
 * the inbox every 10 seconds while it is open, and nearly always nothing changed. The changes forget what they touch:
 * [forget] the two users of a friend request or a block and the one who signs up for a big game; [forgetAll] a big
 * game's state, a nickname and a deleted account. Within the TTL stay only what others change about a big game the
 * user signed up for (how many and which friends signed up) and the requests deleted after
 * [app.hovanki.server.account.AccountProperties.requestRetention].
 */
@Component
class InboxCache(clock: Clock, properties: SocialProperties) : BeforeAccountDeletion {
    private val cache = ReadCache<UserId, Inbox>(properties.inboxCacheTtl, clock)

    /** [userId]'s stored part of the inbox (no [Inbox.invites]): kept, or [read] now. */
    fun of(userId: UserId, read: () -> Inbox): Inbox = checkNotNull(cache.get(userId, read))

    fun forget(userId: UserId) = cache.forget(userId)

    /** The two users of a friend request or a block. */
    fun forget(one: UserId, other: UserId) {
        cache.forget(one)
        cache.forget(other)
    }

    fun forgetAll() = cache.forgetAll()

    /** Their friend requests go with them (the foreign key), in everybody's inbox. */
    override fun beforeDelete(userId: UserId) = forgetAll()

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    fun sweep() = cache.sweep()

    /** Inboxes kept right now (for tests). */
    fun size(): Int = cache.size()
}

/** `hovanki.social.*`. */
@ConfigurationProperties("hovanki.social")
data class SocialProperties(
    /** [InboxCache]: how long a user's friend requests and big games' lobbies are kept without reading them again. */
    val inboxCacheTtl: Duration = Duration.ofMinutes(1),
)
