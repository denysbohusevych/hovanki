package app.hovanki.client.ui.common

import app.hovanki.client.account.AccountState
import app.hovanki.client.social.UserRelation
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.UserSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerAccountTest {
    private val me = PlayerId("p-me")
    private val myUser = UserId("u-me")
    private val loggedIn = AccountState(user = profile(myUser, verified = true), isRestored = true)
    private val friends = FriendsResponse(
        friends = listOf(UserSummary(UserId("u-friend"), "Friend")),
        outgoing = listOf(UserSummary(UserId("u-asked"), "Asked")),
        incoming = listOf(UserSummary(UserId("u-asking"), "Asking")),
        blocked = listOf(UserSummary(UserId("u-blocked"), "Blocked")),
    )

    @Test
    fun guestsAreGuestsWhoeverLooks() {
        val account = playerAccount(player("p-guest", userId = null), me, loggedIn, friends)

        assertTrue(account.isGuest)
        assertNull(account.relation)
    }

    @Test
    fun otherAccountsShowWhatTheyAreToTheViewer() {
        fun relationOf(userId: String) =
            playerAccount(player("p-$userId", UserId(userId)), me, loggedIn, friends).relation

        assertEquals(UserRelation.FRIEND, relationOf("u-friend"))
        assertEquals(UserRelation.OUTGOING, relationOf("u-asked"))
        assertEquals(UserRelation.INCOMING, relationOf("u-asking"))
        assertEquals(UserRelation.BLOCKED, relationOf("u-blocked"))
        assertEquals(UserRelation.NONE, relationOf("u-stranger"))
    }

    @Test
    fun nothingToDoAboutThemselvesOrAsAGuest() {
        val stranger = player("p-stranger", UserId("u-stranger"))

        assertNull(playerAccount(player(me.value, myUser), me, loggedIn, friends).relation)
        assertNull(playerAccount(stranger, me, AccountState(isRestored = true), friends).relation)
    }

    @Test
    fun anUnconfirmedEmailMakesNoDifference() {
        val unconfirmed = AccountState(user = profile(myUser, verified = false), isRestored = true)

        val account = playerAccount(player("p-stranger", UserId("u-stranger")), me, unconfirmed, friends)

        assertEquals(UserRelation.NONE, account.relation, "can be added as a friend")
    }

    private fun player(id: String, userId: UserId?) =
        PlayerView(PlayerId(id), id, Role.HIDER, PlayerStatus.ACTIVE, userId = userId)

    private fun profile(id: UserId, verified: Boolean) =
        UserProfile(id, "Me", "me@example.org", emailVerified = verified, createdAtMillis = 0)
}
