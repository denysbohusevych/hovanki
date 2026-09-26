package app.hovanki.client.social

import app.hovanki.client.account.AccountManager
import app.hovanki.client.account.FakeAccountApi
import app.hovanki.client.account.TEST_ACCOUNT_TOKEN
import app.hovanki.client.account.sessionExpired
import app.hovanki.client.account.testUser
import app.hovanki.client.network.ApiResult
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.FakeSecureStore
import app.hovanki.client.storage.SavedAccount
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameInvite
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.GroupView
import app.hovanki.shared.protocol.GroupsResponse
import app.hovanki.shared.protocol.Inbox
import app.hovanki.shared.protocol.InviteId
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.UserSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SocialManagerTest {
    private class Offline : Exception("offline")

    private val server = "https://hovanki.example.org"
    private val storage = ClientStorage(FakeSecureStore())
    private val accountApi = FakeAccountApi()
    private val api = FakeSocialApi()
    private val unconfirmed = testUser.copy(emailVerified = false)

    private val bo = UserSummary(UserId("u2"), "bo")
    private val cy = UserSummary(UserId("u3"), "cy")
    private val dee = UserSummary(UserId("u4"), "dee")
    private val eve = UserSummary(UserId("u5"), "eve")
    private val invite =
        GameInvite(InviteId("i1"), GameId("game1"), "ABC234", bo, createdAtMillis = 1, expiresAtMillis = 2)

    private lateinit var account: AccountManager

    /**
     * A social manager of a player restored as [user] ([testUser] has a confirmed email; null: logged out), with the
     * calls of the start (loading the friends) forgotten.
     */
    private fun TestScope.social(user: UserProfile? = testUser): SocialManager {
        if (user != null) {
            accountApi.user = user
            storage.saveAccount(SavedAccount(server, TEST_ACCOUNT_TOKEN, user))
        }
        account = AccountManager(accountApi, storage, ServerUrl(server), backgroundScope)
        account.restore()
        return SocialManager(api, account, backgroundScope).also {
            runCurrent()
            api.calls.clear()
        }
    }

    @Test
    fun everythingNeedsAConfirmedAccount() = runTest {
        val cases = listOf(null to ErrorReason.ACCOUNT_REQUIRED, unconfirmed to ErrorReason.EMAIL_NOT_VERIFIED)
        for ((user, reason) in cases) {
            val social = social(user)

            val result = assertIs<ApiResult.Rejected>(social.refreshFriends())

            assertEquals(reason, result.reason)
            assertTrue(api.calls.isEmpty())
        }
    }

    @Test
    fun friendsLoadAsSoonAsTheAccountIsThere() = runTest {
        api.friends = FriendsResponse(friends = listOf(bo), blocked = listOf(eve))

        val social = social()

        assertEquals(api.friends, social.friends.value)
        assertEquals(setOf(eve.id), social.blockedIds.value)
        assertEquals(UserRelation.FRIEND, social.relationTo(bo.id))
    }

    @Test
    fun friendsLoadOnceTheEmailIsConfirmed() = runTest {
        val social = social(unconfirmed)
        api.friends = FriendsResponse(blocked = listOf(eve))

        account.verifyEmail("123456")
        runCurrent()

        assertEquals(listOf("friends $TEST_ACCOUNT_TOKEN"), api.calls)
        assertEquals(setOf(eve.id), social.blockedIds.value)
    }

    @Test
    fun friendCommandsUpdateTheFriends() = runTest {
        val social = social()
        api.friends =
            FriendsResponse(friends = listOf(bo), incoming = listOf(cy), outgoing = listOf(dee), blocked = listOf(eve))

        assertEquals(ApiResult.Success(Unit), social.refreshFriends())
        assertEquals(api.friends, social.friends.value)
        assertEquals(setOf(eve.id), social.blockedIds.value)

        assertEquals(ApiResult.Success(Unit), social.sendFriendRequest(" dee "))
        assertEquals(ApiResult.Success(Unit), social.sendFriendRequest(UserId("u6")))
        social.acceptFriendRequest(cy.id)
        social.declineFriendRequest(cy.id)
        social.removeFriend(bo.id)
        social.block(eve.id)
        social.unblock(eve.id)

        assertEquals(
            listOf(
                "friends $TEST_ACCOUNT_TOKEN",
                "sendFriendRequest $TEST_ACCOUNT_TOKEN dee null",
                "sendFriendRequest $TEST_ACCOUNT_TOKEN null u6",
                "acceptFriendRequest $TEST_ACCOUNT_TOKEN u3",
                "declineFriendRequest $TEST_ACCOUNT_TOKEN u3",
                "removeFriend $TEST_ACCOUNT_TOKEN u2",
                "block $TEST_ACCOUNT_TOKEN u5",
                "unblock $TEST_ACCOUNT_TOKEN u5",
            ),
            api.calls,
        )
    }

    @Test
    fun relationsComeFromTheFriends() = runTest {
        api.failWith = Offline()
        val social = social()
        assertEquals(UserRelation.NONE, social.relationTo(bo.id), "not loaded (no network at the start)")
        api.failWith = null
        api.friends =
            FriendsResponse(friends = listOf(bo), incoming = listOf(cy), outgoing = listOf(dee), blocked = listOf(eve))

        social.refreshFriends()

        assertEquals(UserRelation.SELF, social.relationTo(testUser.id))
        assertEquals(UserRelation.FRIEND, social.relationTo(bo.id))
        assertEquals(UserRelation.INCOMING, social.relationTo(cy.id))
        assertEquals(UserRelation.OUTGOING, social.relationTo(dee.id))
        assertEquals(UserRelation.BLOCKED, social.relationTo(eve.id))
        assertEquals(UserRelation.NONE, social.relationTo(UserId("u9")))
    }

    @Test
    fun answeringARequestUpdatesTheInboxToo() = runTest {
        val social = social()
        api.inbox = Inbox(invites = listOf(invite), friendRequests = listOf(cy))
        social.refreshInbox()

        api.friends = FriendsResponse(friends = listOf(cy))
        social.acceptFriendRequest(cy.id)
        assertEquals(Inbox(invites = listOf(invite)), social.inbox.value)

        api.friends = FriendsResponse(friends = listOf(cy), blocked = listOf(bo))
        social.block(bo.id)
        assertEquals(Inbox(), social.inbox.value, "invites of a blocked user are gone")
    }

    @Test
    fun groupCommandsUpdateTheGroups() = runTest {
        val social = social()
        val park = GroupView(GroupId("g1"), "Park", testUser.id, listOf(UserSummary(testUser.id, "anna"), bo), 5)
        val yard = GroupView(GroupId("g2"), "Yard", testUser.id, listOf(UserSummary(testUser.id, "anna")), 6)
        api.groups = GroupsResponse(listOf(park))
        social.refreshGroups()
        assertEquals(api.groups, social.groups.value)

        api.groups = GroupsResponse(listOf(park, yard))
        assertEquals(ApiResult.Success(yard), social.createGroup("  Yard  ", listOf(bo.id)))
        assertEquals(api.groups, social.groups.value)

        social.addGroupMembers(yard.id, listOf(cy.id))
        social.removeGroupMember(yard.id, cy.id)
        social.renameGroup(yard.id, " Big   yard ")
        social.leaveGroup(park.id)
        social.deleteGroup(yard.id)

        assertEquals(
            listOf(
                "groups $TEST_ACCOUNT_TOKEN",
                "createGroup $TEST_ACCOUNT_TOKEN Yard [u2]",
                "addGroupMembers $TEST_ACCOUNT_TOKEN g2 [u3]",
                "removeGroupMember $TEST_ACCOUNT_TOKEN g2 u3",
                "renameGroup $TEST_ACCOUNT_TOKEN g2 Big yard",
                "removeGroupMember $TEST_ACCOUNT_TOKEN g1 u1",
                "deleteGroup $TEST_ACCOUNT_TOKEN g2",
            ),
            api.calls,
        )
    }

    @Test
    fun theInboxIsPolledOnlyWhileSomeoneWatchesIt() = runTest {
        val social = social()
        api.inbox = Inbox(invites = listOf(invite))
        advanceTimeBy(60_000)
        assertEquals(0, api.inboxCalls, "nobody watching")

        val screen = backgroundScope.launch { social.inbox.collect {} }
        runCurrent()
        assertEquals(1, api.inboxCalls, "right away")
        assertEquals(Inbox(invites = listOf(invite)), social.inbox.value)
        advanceTimeBy(SocialManager.INBOX_INTERVAL_MILLIS + 1)
        assertEquals(2, api.inboxCalls)

        screen.cancel()
        runCurrent()
        advanceTimeBy(60_000)
        assertEquals(2, api.inboxCalls, "the screen is closed")
    }

    @Test
    fun theInboxIsNotPolledWithoutAConfirmedAccount() = runTest {
        val social = social(unconfirmed)

        backgroundScope.launch { social.inbox.collect {} }
        advanceTimeBy(60_000)

        assertEquals(0, api.inboxCalls)
    }

    @Test
    fun aDismissedInviteLeavesTheInbox() = runTest {
        val social = social()
        api.inbox = Inbox()

        assertEquals(ApiResult.Success(Unit), social.dismissInvite(invite.id))

        assertEquals(listOf("dismissInvite $TEST_ACCOUNT_TOKEN i1"), api.calls)
        assertEquals(Inbox(), social.inbox.value)
    }

    @Test
    fun friendsNotLoadedAtTheStartLoadWithTheInbox() = runTest {
        api.failWith = Offline()
        val social = social()
        assertNull(social.friends.value)
        api.failWith = null
        api.friends = FriendsResponse(blocked = listOf(eve))

        backgroundScope.launch { social.inbox.collect {} }
        runCurrent()

        assertEquals(listOf("inbox $TEST_ACCOUNT_TOKEN", "friends $TEST_ACCOUNT_TOKEN"), api.calls)
        assertEquals(setOf(eve.id), social.blockedIds.value)
    }

    @Test
    fun aNewFriendRequestInTheInboxReloadsTheFriends() = runTest {
        val social = social()
        social.refreshFriends()
        api.calls.clear()
        api.inbox = Inbox(friendRequests = listOf(cy))
        api.friends = FriendsResponse(incoming = listOf(cy))

        backgroundScope.launch { social.inbox.collect {} }
        runCurrent()

        assertEquals(listOf("inbox $TEST_ACCOUNT_TOKEN", "friends $TEST_ACCOUNT_TOKEN"), api.calls)
        assertEquals(UserRelation.INCOMING, social.relationTo(cy.id))
    }

    @Test
    fun loggingOutClearsEverything() = runTest {
        val social = social()
        api.friends = FriendsResponse(friends = listOf(bo), blocked = listOf(eve))
        api.groups = GroupsResponse(listOf(GroupView(GroupId("g1"), "Park", testUser.id, listOf(bo), 5)))
        api.inbox = Inbox(invites = listOf(invite))
        social.refreshFriends()
        social.refreshGroups()
        social.refreshInbox()

        account.logOut()
        runCurrent()

        assertNull(social.friends.value)
        assertNull(social.groups.value)
        assertEquals(Inbox(), social.inbox.value)
        assertEquals(emptySet(), social.blockedIds.value)
    }

    @Test
    fun aRejectedSessionLogsOut() = runTest {
        val social = social()
        api.failWith = sessionExpired()

        val result = assertIs<ApiResult.Rejected>(social.refreshFriends())

        assertEquals(ErrorReason.SESSION_EXPIRED, result.reason)
        assertTrue(account.state.value.sessionExpired)
        assertNull(account.accountToken)
    }

    @Test
    fun aRejectedSessionStopsThePolling() = runTest {
        val social = social()
        api.failWith = sessionExpired()

        backgroundScope.launch { social.inbox.collect {} }
        runCurrent()
        advanceTimeBy(60_000)

        assertEquals(1, api.inboxCalls)
        assertTrue(account.state.value.sessionExpired)
    }

    @Test
    fun relationWithoutFriendsLoadedIsNone() {
        assertEquals(UserRelation.SELF, userRelation(testUser.id, testUser.id, null))
        assertEquals(UserRelation.NONE, userRelation(bo.id, testUser.id, null))
        assertEquals(UserRelation.NONE, userRelation(bo.id, null, FriendsResponse()))
    }
}
