package app.hovanki.server.social

import app.hovanki.server.account.BeforeAccountDeletion
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.InviteId
import app.hovanki.shared.protocol.UserId
import org.springframework.stereotype.Component

/** An invitation of [inviteeId] into a game in its lobby, from [inviterId]; gone after [expiresAtMillis]. */
data class Invite(
    val id: InviteId,
    val gameId: GameId,
    val joinCode: String,
    val inviterId: UserId,
    val inviteeId: UserId,
    /** Set when the invitee came in with a whole group. */
    val groupId: GroupId? = null,
    /** The group's name when the invitation was sent. */
    val groupName: String? = null,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
) {
    // Never the group's name (user-typed text) or the join code in logs.
    override fun toString(): String = "Invite(${id.value}, game ${gameId.value})"
}

/**
 * Game invitations, in memory like the games themselves (docs/adr/0004-accounts-friends-chat.md): a restart loses the
 * games, and invitations into them would be useless anyway. At most one per game and invitee (a new one replaces the
 * old one), at most [maxPerUser] per invitee and [maxTotal] in all: beyond that, the oldest go. Time is passed in.
 *
 * An invitation ends when it expires, when its game leaves the lobby or is gone ([sweep], and lazily in the inbox),
 * when the invitee joins the game ([removeInvitee]) or dismisses it, and when the inviter's or the invitee's account is
 * deleted ([beforeDelete]). Thread-safe.
 */
@Component
class InviteRegistry(
    private val maxPerUser: Int = SocialLimits.MAX_INVITES_PER_USER,
    private val maxTotal: Int = SocialLimits.MAX_INVITES,
) : BeforeAccountDeletion {
    private val lock = Any()

    /** Oldest first: insertion order, and a replaced invitation is inserted anew. */
    private val invites = LinkedHashMap<InviteId, Invite>()

    /** Each invitee's invitations by game, oldest first. */
    private val byInvitee = HashMap<UserId, LinkedHashMap<GameId, InviteId>>()

    /** Stores [invite], replacing the invitee's invitation into the same game. */
    fun add(invite: Invite) = synchronized(lock) {
        byInvitee[invite.inviteeId]?.get(invite.gameId)?.let(::removeLocked)
        invites[invite.id] = invite
        val ofInvitee = byInvitee.getOrPut(invite.inviteeId) { LinkedHashMap() }
        ofInvitee[invite.gameId] = invite.id
        while (ofInvitee.size > maxPerUser) removeLocked(ofInvitee.values.first())
        while (invites.size > maxTotal) removeLocked(invites.keys.first())
    }

    /** [inviteeId]'s invitations that have not expired at [nowMillis], oldest first; their games are not checked. */
    fun of(inviteeId: UserId, nowMillis: Long): List<Invite> = synchronized(lock) {
        byInvitee[inviteeId].orEmpty().values.mapNotNull { invites[it] }.filter { it.expiresAtMillis > nowMillis }
    }

    /** Removes [inviteId] if it is [inviteeId]'s; false otherwise. */
    fun dismiss(inviteeId: UserId, inviteId: InviteId): Boolean = synchronized(lock) {
        if (invites[inviteId]?.inviteeId != inviteeId) return false
        removeLocked(inviteId)
        true
    }

    fun remove(ids: Collection<InviteId>) = synchronized(lock) { ids.forEach(::removeLocked) }

    /** [inviteeId] joined [gameId]: their invitation into it is answered. */
    fun removeInvitee(gameId: GameId, inviteeId: UserId) = synchronized(lock) {
        byInvitee[inviteeId]?.get(gameId)?.let(::removeLocked)
        Unit
    }

    /**
     * Drops the invitations that expired at [nowMillis] and those into games that [isLobby] says are gone or out of
     * their lobby. [isLobby] runs without this registry's lock (it may take a game's). Returns how many went.
     */
    fun sweep(nowMillis: Long, isLobby: (GameId) -> Boolean): Int {
        val gameIds = synchronized(lock) { invites.values.mapTo(HashSet()) { it.gameId } }
        val closed = gameIds.filterNotTo(HashSet(), isLobby)
        return synchronized(lock) {
            val gone = invites.values.filter { it.expiresAtMillis <= nowMillis || it.gameId in closed }
            gone.forEach { removeLocked(it.id) }
            gone.size
        }
    }

    /** The account goes: its invitations, sent and received, go with it. */
    override fun beforeDelete(userId: UserId) = synchronized(lock) {
        invites.values.filter { it.inviterId == userId || it.inviteeId == userId }.forEach { removeLocked(it.id) }
    }

    fun size(): Int = synchronized(lock) { invites.size }

    private fun removeLocked(id: InviteId) {
        val invite = invites.remove(id) ?: return
        val ofInvitee = byInvitee[invite.inviteeId] ?: return
        if (ofInvitee[invite.gameId] == id) ofInvitee.remove(invite.gameId)
        if (ofInvitee.isEmpty()) byInvitee.remove(invite.inviteeId)
    }
}
