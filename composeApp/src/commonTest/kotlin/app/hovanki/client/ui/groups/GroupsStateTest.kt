package app.hovanki.client.ui.groups

import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.GroupView
import app.hovanki.shared.protocol.GroupsResponse
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupsStateTest {
    private val me = UserSummary(UserId("u-me"), "me")
    private val ann = UserSummary(UserId("u-ann"), "ann")
    private val bob = UserSummary(UserId("u-bob"), "bob")
    private val group =
        GroupView(GroupId("g1"), "Yard", ownerId = me.id, members = listOf(me, ann), createdAtMillis = 0)

    @Test
    fun theOpenGroupIsShownOnlyOnceLoaded() {
        val opened = GroupsUiState(openGroupId = group.id)
        assertNull(opened.openGroup)
        assertEquals(group, opened.copy(groups = GroupsResponse(listOf(group))).openGroup)
        assertNull(GroupsUiState(groups = GroupsResponse(listOf(group))).openGroup)
    }

    @Test
    fun theOwnerIsTheLoggedInAccount() {
        assertTrue(GroupsUiState(myId = me.id).isOwner(group))
        assertFalse(GroupsUiState(myId = ann.id).isOwner(group))
        assertFalse(GroupsUiState().isOwner(group))
    }

    @Test
    fun onlyFriendsOutsideTheGroupCanBeAdded() {
        val state = GroupsUiState(friends = listOf(ann, bob))
        assertEquals(listOf(bob), state.candidates(group))
    }
}
