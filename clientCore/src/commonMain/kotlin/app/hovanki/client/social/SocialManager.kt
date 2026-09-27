package app.hovanki.client.social

import app.hovanki.client.account.AccountManager
import app.hovanki.client.network.ApiException
import app.hovanki.client.network.ApiResult
import app.hovanki.client.network.SocialApi
import app.hovanki.client.network.apiResult
import app.hovanki.shared.protocol.CreateGroupRequest
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.GroupView
import app.hovanki.shared.protocol.GroupsResponse
import app.hovanki.shared.protocol.Inbox
import app.hovanki.shared.protocol.InviteId
import app.hovanki.shared.protocol.SendFriendRequest
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.rules.GroupRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Friends, blocks, groups and the inbox of the logged-in player (docs/adr/0004-accounts-friends-chat.md). App-scoped;
 * runs on the main thread. Everything here needs an account ([AccountManager]; its email need not be confirmed); the
 * state is cleared when the player logs out or another account logs in, and a 401 logs the player out.
 *
 * [friends] load when the account is there (login, app start) and again when the inbox shows new requests; [groups] on
 * demand ([refreshGroups], [refreshFriends]: when their screen opens). The [inbox] is polled every
 * [inboxIntervalMillis] while someone collects it (the start screen is open). Every command updates the state from
 * the server's response and returns an [ApiResult]; none throws.
 */
class SocialManager(
    private val api: SocialApi,
    private val account: AccountManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val inboxIntervalMillis: Long = INBOX_INTERVAL_MILLIS,
) {
    private val mutableFriends = MutableStateFlow<FriendsResponse?>(null)
    private val mutableGroups = MutableStateFlow<GroupsResponse?>(null)
    private val mutableInbox = MutableStateFlow(Inbox())
    private val mutableBlockedIds = MutableStateFlow<Set<UserId>>(emptySet())

    /** The account the state was loaded for; the state of another one is never shown. */
    private var loadedFor: String? = null

    /** Friends, requests both ways and blocked users; null until loaded. */
    val friends: StateFlow<FriendsResponse?> = mutableFriends.asStateFlow()

    /** The groups the player is in; null until loaded. */
    val groups: StateFlow<GroupsResponse?> = mutableGroups.asStateFlow()

    /**
     * Game invites and incoming friend requests. Collecting it polls the server every [inboxIntervalMillis] (right
     * away first), as long as the player is logged in; nobody collecting, no polling.
     */
    val inbox: StateFlow<Inbox> = mutableInbox.asStateFlow()

    /** Users the player blocked: their chat messages are hidden (`chatLines`). */
    val blockedIds: StateFlow<Set<UserId>> = mutableBlockedIds.asStateFlow()

    init {
        scope.launch {
            account.tokens.collect { token ->
                if (token != loadedFor) clear()
                // Blocks hide chat messages and the lobby shows who is a friend: no waiting for the friends screen.
                if (token != null) launch { refreshFriends() }
            }
        }
        scope.launch {
            val watched = mutableInbox.subscriptionCount.map { it > 0 }.distinctUntilChanged()
            combine(watched, account.tokens) { isWatched, token -> token.takeIf { isWatched } }
                .distinctUntilChanged()
                .collectLatest { token ->
                    while (token != null) {
                        pollInbox(token)
                        delay(inboxIntervalMillis)
                    }
                }
        }
    }

    /** What [userId] is to the player, from [friends] (load them first); [UserRelation.NONE] while not loaded. */
    fun relationTo(userId: UserId): UserRelation = userRelation(userId, account.state.value.user?.id, friends.value)

    suspend fun refreshFriends(): ApiResult<Unit> = friendsCommand { api.friends(it) }

    suspend fun refreshGroups(): ApiResult<Unit> = groupsCommand { api.groups(it) }

    suspend fun refreshInbox(): ApiResult<Unit> = command { token -> applyInbox(token, api.inbox(token)) }

    /** A friend request by exact nickname; if they already asked the player, they are friends right away. */
    suspend fun sendFriendRequest(nickname: String): ApiResult<Unit> =
        friendsCommand { api.sendFriendRequest(it, SendFriendRequest(nickname = nickname.trim())) }

    /** A friend request to a player seen in a game ([app.hovanki.shared.protocol.PlayerView.userId]). */
    suspend fun sendFriendRequest(userId: UserId): ApiResult<Unit> =
        friendsCommand { api.sendFriendRequest(it, SendFriendRequest(userId = userId)) }

    suspend fun acceptFriendRequest(userId: UserId): ApiResult<Unit> =
        friendsCommand { api.acceptFriendRequest(it, userId) }

    /** Declines [userId]'s request, or withdraws the player's own request to them. */
    suspend fun declineFriendRequest(userId: UserId): ApiResult<Unit> =
        friendsCommand { api.declineFriendRequest(it, userId) }

    suspend fun removeFriend(userId: UserId): ApiResult<Unit> = friendsCommand { api.removeFriend(it, userId) }

    /**
     * Ends the friendship and requests both ways, removes them from the player's groups (reload [groups]) and hides
     * their chat messages; their requests and invites are refused from now on.
     */
    suspend fun block(userId: UserId): ApiResult<Unit> = friendsCommand { api.block(it, userId) }

    suspend fun unblock(userId: UserId): ApiResult<Unit> = friendsCommand { api.unblock(it, userId) }

    /** A new group owned by the player, with some of their friends; the result is the new group. */
    suspend fun createGroup(name: String, memberIds: List<UserId> = emptyList()): ApiResult<GroupView?> =
        command { token ->
            val before = mutableGroups.value?.groups.orEmpty().map { it.id }.toSet()
            val groups = api.createGroup(token, CreateGroupRequest(GroupRules.normalizeName(name), memberIds))
            applyGroups(token, groups)
            // The only group that wasn't there before; ambiguous only if another device created one meanwhile.
            groups.groups.filter { it.id !in before && it.ownerId == account.state.value.user?.id }.singleOrNull()
        }

    /** Owner only: adds some of the owner's friends. */
    suspend fun addGroupMembers(groupId: GroupId, userIds: List<UserId>): ApiResult<Unit> =
        groupsCommand { api.addGroupMembers(it, groupId, userIds) }

    /** Owner only: removes a member. */
    suspend fun removeGroupMember(groupId: GroupId, userId: UserId): ApiResult<Unit> =
        groupsCommand { api.removeGroupMember(it, groupId, userId) }

    /** The player leaves the group; if they own it, the longest-standing member takes it over. */
    suspend fun leaveGroup(groupId: GroupId): ApiResult<Unit> = groupsCommand { token ->
        api.removeGroupMember(token, groupId, checkNotNull(account.state.value.user).id)
    }

    suspend fun renameGroup(groupId: GroupId, name: String): ApiResult<Unit> =
        groupsCommand { api.renameGroup(it, groupId, GroupRules.normalizeName(name)) }

    suspend fun deleteGroup(groupId: GroupId): ApiResult<Unit> = groupsCommand { api.deleteGroup(it, groupId) }

    /**
     * Hides an invite from the inbox. Accepting one is joining its game with
     * [app.hovanki.shared.protocol.GameInvite.joinCode] while logged in.
     */
    suspend fun dismissInvite(inviteId: InviteId): ApiResult<Unit> =
        command { token -> applyInbox(token, api.dismissInvite(token, inviteId)) }

    private suspend fun friendsCommand(call: suspend (token: String) -> FriendsResponse): ApiResult<Unit> =
        command { token -> applyFriends(token, call(token)) }

    private suspend fun groupsCommand(call: suspend (token: String) -> GroupsResponse): ApiResult<Unit> =
        command { token -> applyGroups(token, call(token)) }

    private suspend fun <T> command(call: suspend (token: String) -> T): ApiResult<T> {
        val token = account.accountToken ?: return checkNotNull(account.missingAccount())
        return apiResult(onRejected = { it.logOutIfRejected(token) }) { call(token) }
    }

    /** One poll: errors are left for the next one, except a rejected session. */
    private suspend fun pollInbox(token: String) {
        val inbox = apiResult(onRejected = { it.logOutIfRejected(token) }) { api.inbox(token) }
        if (inbox !is ApiResult.Success) return
        applyInbox(token, inbox.value)
        // Not loaded yet (no network at login), or new or answered friend requests: the friends list is out of date.
        val incoming = mutableFriends.value?.incoming?.map { it.id }?.toSet()
        if (inbox.value.friendRequests.map { it.id }.toSet() != incoming) refreshFriends()
    }

    private fun ApiException.logOutIfRejected(token: String) {
        if (status == UNAUTHORIZED) account.onTokenRejected(token)
    }

    private fun applyFriends(token: String, friends: FriendsResponse) {
        if (!isCurrent(token)) return
        mutableFriends.value = friends
        val blocked = friends.blocked.map { it.id }.toSet()
        mutableBlockedIds.value = blocked
        // The inbox shows the same incoming requests; blocked users' invites are gone on the server too.
        mutableInbox.value = mutableInbox.value.let { inbox ->
            inbox.copy(friendRequests = friends.incoming, invites = inbox.invites.filter { it.from.id !in blocked })
        }
    }

    private fun applyGroups(token: String, groups: GroupsResponse) {
        if (isCurrent(token)) mutableGroups.value = groups
    }

    private fun applyInbox(token: String, inbox: Inbox) {
        if (isCurrent(token)) mutableInbox.value = inbox
    }

    /** Whether a response for [token] may be shown: the player has not logged out or switched accounts meanwhile. */
    private fun isCurrent(token: String): Boolean {
        if (account.accountToken != token) return false
        if (loadedFor != token) {
            clear()
            loadedFor = token
        }
        return true
    }

    private fun clear() {
        loadedFor = null
        mutableFriends.value = null
        mutableGroups.value = null
        mutableInbox.value = Inbox()
        mutableBlockedIds.value = emptySet()
    }

    companion object {
        const val INBOX_INTERVAL_MILLIS = 10_000L
        private const val UNAUTHORIZED = 401
    }
}

/** What another user is to the player. */
enum class UserRelation {
    /** The player themselves. */
    SELF,
    FRIEND,

    /** They asked the player to be friends. */
    INCOMING,

    /** The player asked them. */
    OUTGOING,

    /** The player blocked them. */
    BLOCKED,
    NONE,
}

/** What [userId] is to the player [selfId] according to [friends] (null: not loaded, so [UserRelation.NONE]). */
fun userRelation(userId: UserId, selfId: UserId?, friends: FriendsResponse?): UserRelation = when {
    userId == selfId -> UserRelation.SELF
    friends == null -> UserRelation.NONE
    friends.blocked.any { it.id == userId } -> UserRelation.BLOCKED
    friends.friends.any { it.id == userId } -> UserRelation.FRIEND
    friends.incoming.any { it.id == userId } -> UserRelation.INCOMING
    friends.outgoing.any { it.id == userId } -> UserRelation.OUTGOING
    else -> UserRelation.NONE
}
