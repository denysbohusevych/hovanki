package app.hovanki.server.social

import app.hovanki.server.MutableClock
import app.hovanki.server.account.AccountService
import app.hovanki.server.account.AccountSessionRepository
import app.hovanki.server.account.AccountTestConfig
import app.hovanki.server.account.PasswordHasher
import app.hovanki.server.account.UserRepository
import app.hovanki.server.game.GameException
import app.hovanki.server.game.IdGenerator
import app.hovanki.shared.protocol.AddGroupMembersRequest
import app.hovanki.shared.protocol.CreateGroupRequest
import app.hovanki.shared.protocol.DeleteAccountRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.GroupView
import app.hovanki.shared.protocol.GroupsResponse
import app.hovanki.shared.protocol.RenameGroupRequest
import app.hovanki.shared.protocol.SendFriendRequest
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.rules.GroupRules
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** [GroupService] on the test database; the same context as the account tests. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountTestConfig::class)
class GroupServiceTest(
    @Autowired private val service: GroupService,
    @Autowired private val friendService: FriendService,
    @Autowired private val groups: GroupRepository,
    @Autowired private val accounts: AccountService,
    @Autowired users: UserRepository,
    @Autowired sessions: AccountSessionRepository,
    @Autowired hasher: PasswordHasher,
    @Autowired ids: IdGenerator,
    @Autowired jdbc: JdbcClient,
    @Autowired private val clock: MutableClock,
) {
    private val testUsers = TestUsers(users, sessions, hasher, ids, jdbc, clock)

    @Test
    fun createAGroupOfFriends() {
        val owner = testUsers.create()
        // Joined at the same moment: the owner first, then by nickname.
        val (amy, zed) = listOf("amy", "zed").map { friendOf(owner, prefix = it) }

        val created = service.create(
            owner.id,
            CreateGroupRequest("  Weekend \n  crew ", listOf(zed.id, amy.id, zed.id)),
        )
        val group = created.groups.single()
        assertEquals("Weekend crew", group.name)
        assertEquals(owner.id, group.ownerId)
        assertEquals(listOf(owner.summary, amy.summary, zed.summary), group.members)
        assertEquals(clock.millis(), group.createdAtMillis)
        // Every member sees it.
        assertEquals(created, service.groups(amy.id))
        assertEquals(created, service.groups(zed.id))
    }

    @Test
    fun groupsAreSortedByName() {
        val owner = testUsers.create()
        val friend = friendOf(owner)
        for (name in listOf("b", "C", "a")) service.create(owner.id, CreateGroupRequest(name, listOf(friend.id)))
        service.create(friend.id, CreateGroupRequest("B2", listOf(owner.id)))

        assertEquals(listOf("a", "b", "B2", "C"), service.groups(owner.id).groups.map { it.name })
    }

    @Test
    fun createIsValidated() {
        val owner = testUsers.create()
        val friend = friendOf(owner)
        val stranger = testUsers.create()

        for (name in listOf("", "  \n ", "x".repeat(GroupRules.NAME_MAX_LENGTH + 1), "a\u0007b")) {
            assertError(ErrorCode.BAD_REQUEST, ErrorReason.INVALID_GROUP_NAME) {
                service.create(owner.id, CreateGroupRequest(name, listOf(friend.id)))
            }
        }
        for (members in listOf(listOf(stranger.id), listOf(friend.id, UserId("nobody")))) {
            assertError(ErrorCode.FORBIDDEN, ErrorReason.NOT_FRIENDS) {
                service.create(owner.id, CreateGroupRequest("Group", members))
            }
        }
        assertEquals(GroupsResponse(), service.groups(owner.id))
        // Alone is fine, and naming yourself changes nothing.
        val alone = service.create(owner.id, CreateGroupRequest("Just me", listOf(owner.id))).groups.single()
        assertEquals(listOf(owner.summary), alone.members)
    }

    @Test
    fun memberLimit() {
        val owner = testUsers.create()
        val friends = List(GroupRules.MAX_MEMBERS) { friendOf(owner) }

        assertError(ErrorCode.WRONG_STATE, ErrorReason.LIMIT_REACHED) {
            service.create(owner.id, CreateGroupRequest("Too many", friends.map { it.id }))
        }
        val group = service.create(owner.id, CreateGroupRequest("Full", friends.drop(1).map { it.id })).groups.single()
        assertEquals(GroupRules.MAX_MEMBERS, group.members.size)
        assertError(ErrorCode.WRONG_STATE, ErrorReason.LIMIT_REACHED) {
            service.addMembers(owner.id, group.id, AddGroupMembersRequest(listOf(friends.first().id)))
        }
        // Adding those who are in already still works.
        service.addMembers(owner.id, group.id, AddGroupMembersRequest(listOf(friends.last().id)))
    }

    @Test
    fun ownedGroupLimit() {
        val owner = testUsers.create()
        repeat(GroupRules.MAX_OWNED_GROUPS) { service.create(owner.id, CreateGroupRequest("Group $it")) }

        assertError(ErrorCode.WRONG_STATE, ErrorReason.LIMIT_REACHED) {
            service.create(owner.id, CreateGroupRequest("One more"))
        }
        // Groups of others don't count.
        val friend = friendOf(owner)
        service.create(friend.id, CreateGroupRequest("Friend's", listOf(owner.id)))
        assertEquals(GroupRules.MAX_OWNED_GROUPS + 1, service.groups(owner.id).groups.size)
    }

    @Test
    fun theOwnerAddsFriends() {
        val owner = testUsers.create()
        val (amy, bob) = List(2) { friendOf(owner) }
        val stranger = testUsers.create()
        val group = service.create(owner.id, CreateGroupRequest("Group", listOf(amy.id))).groups.single()

        clock.advance(Duration.ofMinutes(1))
        val added = service.addMembers(owner.id, group.id, AddGroupMembersRequest(listOf(bob.id, amy.id)))
        assertEquals(listOf(owner.summary, amy.summary, bob.summary), added.groups.single().members)
        assertEquals(added, service.groups(bob.id))

        assertError(ErrorCode.FORBIDDEN, ErrorReason.NOT_FRIENDS) {
            service.addMembers(owner.id, group.id, AddGroupMembersRequest(listOf(stranger.id)))
        }
        // Amy's friend, but only the owner adds members.
        val amysFriend = friendOf(amy)
        assertError(ErrorCode.FORBIDDEN, ErrorReason.NOT_GROUP_OWNER) {
            service.addMembers(amy.id, group.id, AddGroupMembersRequest(listOf(amysFriend.id)))
        }
        // Outsiders can't tell the group exists.
        assertError(ErrorCode.NOT_FOUND, null) {
            service.addMembers(stranger.id, group.id, AddGroupMembersRequest(emptyList()))
        }
        assertError(ErrorCode.NOT_FOUND, null) {
            service.addMembers(owner.id, GroupId("nothing"), AddGroupMembersRequest(listOf(amy.id)))
        }
    }

    @Test
    fun removingMembers() {
        val owner = testUsers.create()
        val (amy, bob, carol) = List(3) { friendOf(owner) }
        val stranger = testUsers.create()
        val group = service.create(owner.id, CreateGroupRequest("Group", listOf(amy.id, bob.id, carol.id)))
            .groups.single().id

        assertEquals(setOf(owner.id, bob.id, carol.id), memberIds(service.removeMember(owner.id, group, amy.id)))
        assertEquals(GroupsResponse(), service.groups(amy.id))
        assertError(ErrorCode.FORBIDDEN, ErrorReason.NOT_GROUP_MEMBER) {
            service.removeMember(owner.id, group, amy.id)
        }
        assertError(ErrorCode.FORBIDDEN, ErrorReason.NOT_GROUP_OWNER) {
            service.removeMember(bob.id, group, carol.id)
        }
        // A member leaves.
        assertEquals(GroupsResponse(), service.removeMember(bob.id, group, bob.id))
        assertEquals(setOf(owner.id, carol.id), groups.memberIds(group).toSet())
        // Not a member (any more): no such group.
        for (outsider in listOf(bob, stranger)) {
            assertError(ErrorCode.NOT_FOUND, null) { service.removeMember(outsider.id, group, outsider.id) }
            assertError(ErrorCode.NOT_FOUND, null) { service.removeMember(outsider.id, group, carol.id) }
        }
    }

    @Test
    fun whenTheOwnerLeavesTheLongestStandingMemberTakesOver() {
        val owner = testUsers.create()
        val (first, second) = List(2) { friendOf(owner) }
        val group = service.create(owner.id, CreateGroupRequest("Group", listOf(first.id))).groups.single().id
        clock.advance(Duration.ofMinutes(1))
        service.addMembers(owner.id, group, AddGroupMembersRequest(listOf(second.id)))

        assertEquals(GroupsResponse(), service.removeMember(owner.id, group, owner.id))
        val handedOver = service.groups(first.id).groups.single()
        assertEquals(first.id, handedOver.ownerId)
        assertEquals(listOf(first.summary, second.summary), handedOver.members)

        service.removeMember(first.id, group, first.id)
        assertEquals(listOf(second.summary), service.groups(second.id).groups.single().members)
        assertEquals(second.id, groups.find(group)?.ownerId)

        // The last one out: the group is gone.
        assertEquals(GroupsResponse(), service.removeMember(second.id, group, second.id))
        assertNull(groups.find(group))
    }

    @Test
    fun renameAndDelete() {
        val owner = testUsers.create()
        val friend = friendOf(owner)
        val group = service.create(owner.id, CreateGroupRequest("Old", listOf(friend.id))).groups.single().id

        assertEquals("New name", service.rename(owner.id, group, RenameGroupRequest(" New  name ")).single().name)
        assertError(ErrorCode.BAD_REQUEST, ErrorReason.INVALID_GROUP_NAME) {
            service.rename(owner.id, group, RenameGroupRequest(" "))
        }
        assertError(ErrorCode.FORBIDDEN, ErrorReason.NOT_GROUP_OWNER) {
            service.rename(friend.id, group, RenameGroupRequest("Mine"))
        }
        assertError(ErrorCode.FORBIDDEN, ErrorReason.NOT_GROUP_OWNER) { service.delete(friend.id, group) }
        assertEquals("New name", service.groups(friend.id).single().name)

        assertEquals(GroupsResponse(), service.delete(owner.id, group))
        assertEquals(GroupsResponse(), service.groups(friend.id))
        assertError(ErrorCode.NOT_FOUND, null) { service.delete(owner.id, group) }
    }

    @Test
    fun deletingTheAccountHandsTheGroupsOver() {
        val owner = testUsers.create()
        val (first, second) = List(2) { friendOf(owner) }
        val shared = service.create(owner.id, CreateGroupRequest("Shared", listOf(first.id))).groups.single().id
        clock.advance(Duration.ofMinutes(1))
        service.addMembers(owner.id, shared, AddGroupMembersRequest(listOf(second.id)))
        val alone = service.create(owner.id, CreateGroupRequest("Alone")).groups.single { it.name == "Alone" }.id
        val firsts = service.create(first.id, CreateGroupRequest("First's", listOf(owner.id))).groups
            .single { it.name == "First's" }.id

        accounts.delete(owner.auth, DeleteAccountRequest(TestUsers.PASSWORD))

        assertEquals(first.id, groups.find(shared)?.ownerId)
        assertEquals(listOf(first.id, second.id), groups.memberIds(shared))
        assertNull(groups.find(alone))
        assertEquals(listOf(first.id), groups.memberIds(firsts))
    }

    private fun friendOf(user: TestUser, prefix: String = "user"): TestUser {
        val friend = testUsers.create(prefix)
        friendService.sendRequest(user.id, SendFriendRequest(userId = friend.id))
        friendService.accept(friend.id, user.id)
        return friend
    }

    private fun memberIds(response: GroupsResponse): Set<UserId> = response.single().members.map { it.id }.toSet()

    private fun GroupsResponse.single(): GroupView = groups.single()

    private fun assertError(code: ErrorCode, reason: ErrorReason?, block: () -> Unit) {
        val error = assertFailsWith<GameException> { block() }
        assertEquals(code, error.code, error.message)
        if (reason == null) assertNull(error.reason, error.message) else assertEquals(reason, error.reason)
    }
}
