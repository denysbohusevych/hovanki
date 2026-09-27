package app.hovanki.server.social

import app.hovanki.server.MutableClock
import app.hovanki.server.account.AccountSessionRepository
import app.hovanki.server.account.AccountTestConfig
import app.hovanki.server.account.PasswordHasher
import app.hovanki.server.account.UserRepository
import app.hovanki.server.account.uniqueName
import app.hovanki.server.game.GameException
import app.hovanki.server.game.IdGenerator
import app.hovanki.shared.protocol.CreateGroupRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.SendFriendRequest
import app.hovanki.shared.protocol.UserId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** [FriendService] on the test database; the same context as the account tests. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountTestConfig::class)
class FriendServiceTest(
    @Autowired private val service: FriendService,
    @Autowired private val groupService: GroupService,
    @Autowired private val groups: GroupRepository,
    @Autowired users: UserRepository,
    @Autowired sessions: AccountSessionRepository,
    @Autowired hasher: PasswordHasher,
    @Autowired ids: IdGenerator,
    @Autowired jdbc: JdbcClient,
    @Autowired clock: MutableClock,
) {
    private val testUsers = TestUsers(users, sessions, hasher, ids, jdbc, clock)

    @Test
    fun aRequestByNicknameAndAccepting() {
        val alice = testUsers.create()
        val bob = testUsers.create()

        val sent = service.sendRequest(alice.id, SendFriendRequest(nickname = " ${bob.nickname.uppercase()} "))
        assertEquals(FriendsResponse(outgoing = listOf(bob.summary)), sent)
        assertEquals(FriendsResponse(incoming = listOf(alice.summary)), service.friends(bob.id))
        // Asking again changes nothing.
        assertEquals(sent, service.sendRequest(alice.id, SendFriendRequest(userId = bob.id)))

        assertEquals(FriendsResponse(friends = listOf(alice.summary)), service.accept(bob.id, alice.id))
        assertEquals(FriendsResponse(friends = listOf(bob.summary)), service.friends(alice.id))
        // A second tap, and a request to a friend: nothing to do.
        assertEquals(FriendsResponse(friends = listOf(alice.summary)), service.accept(bob.id, alice.id))
        assertEquals(
            FriendsResponse(friends = listOf(bob.summary)),
            service.sendRequest(alice.id, SendFriendRequest(userId = bob.id)),
        )
    }

    @Test
    fun askingEachOtherMakesFriends() {
        val alice = testUsers.create()
        val bob = testUsers.create()
        service.sendRequest(alice.id, SendFriendRequest(userId = bob.id))

        assertEquals(
            FriendsResponse(friends = listOf(alice.summary)),
            service.sendRequest(bob.id, SendFriendRequest(nickname = alice.nickname)),
        )
        assertEquals(FriendsResponse(friends = listOf(bob.summary)), service.friends(alice.id))
    }

    @Test
    fun askingEachOtherAtTheSameMomentMakesFriends() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(5) {
                val alice = testUsers.create()
                val bob = testUsers.create()
                val barrier = CyclicBarrier(2)
                val requests = listOf(alice to bob, bob to alice).map { (from, to) ->
                    pool.submit {
                        barrier.await(5, TimeUnit.SECONDS)
                        service.sendRequest(from.id, SendFriendRequest(userId = to.id))
                    }
                }
                requests.forEach { it.get(10, TimeUnit.SECONDS) }
                assertEquals(FriendsResponse(friends = listOf(bob.summary)), service.friends(alice.id))
                assertEquals(FriendsResponse(friends = listOf(alice.summary)), service.friends(bob.id))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun declineAndWithdraw() {
        val alice = testUsers.create()
        val bob = testUsers.create()

        service.sendRequest(alice.id, SendFriendRequest(userId = bob.id))
        assertEquals(FriendsResponse(), service.decline(bob.id, alice.id))
        assertEquals(FriendsResponse(), service.friends(alice.id))

        service.sendRequest(alice.id, SendFriendRequest(userId = bob.id))
        assertEquals(FriendsResponse(), service.decline(alice.id, bob.id))
        assertEquals(FriendsResponse(), service.friends(bob.id))
        // Nothing to decline is fine.
        assertEquals(FriendsResponse(), service.decline(alice.id, bob.id))
    }

    @Test
    fun onlyIncomingRequestsCanBeAccepted() {
        val alice = testUsers.create()
        val bob = testUsers.create()
        service.sendRequest(alice.id, SendFriendRequest(userId = bob.id))

        assertError(ErrorCode.NOT_FOUND, null) { service.accept(alice.id, bob.id) }
        assertError(ErrorCode.NOT_FOUND, null) { service.accept(alice.id, UserId("nobody")) }
        assertEquals(FriendsResponse(outgoing = listOf(bob.summary)), service.friends(alice.id))
    }

    @Test
    fun requestsNeedExactlyOneKnownUser() {
        val alice = testUsers.create()
        val bob = testUsers.create()

        assertError(ErrorCode.BAD_REQUEST, null) { service.sendRequest(alice.id, SendFriendRequest()) }
        assertError(ErrorCode.BAD_REQUEST, null) {
            service.sendRequest(alice.id, SendFriendRequest(nickname = " ", userId = null))
        }
        assertError(ErrorCode.BAD_REQUEST, null) {
            service.sendRequest(alice.id, SendFriendRequest(nickname = bob.nickname, userId = bob.id))
        }
        assertError(ErrorCode.BAD_REQUEST, null) {
            service.sendRequest(alice.id, SendFriendRequest(nickname = alice.nickname))
        }
        for (request in listOf(
            SendFriendRequest(nickname = uniqueName()),
            // No prefix search.
            SendFriendRequest(nickname = bob.nickname.dropLast(1)),
            SendFriendRequest(userId = UserId("nobody")),
        )) {
            assertError(ErrorCode.NOT_FOUND, ErrorReason.USER_NOT_FOUND) { service.sendRequest(alice.id, request) }
        }
        assertEquals(FriendsResponse(), service.friends(alice.id))
    }

    @Test
    fun accountsWithoutAConfirmedEmailAreFound() {
        // Confirming the email is optional: it doesn't matter on either side.
        val alice = testUsers.create(verified = false)
        val (bob, carol) = List(2) { testUsers.create(verified = false) }

        service.sendRequest(alice.id, SendFriendRequest(nickname = bob.nickname.uppercase()))
        val sent = service.sendRequest(alice.id, SendFriendRequest(userId = carol.id))
        assertEquals(setOf(bob.id, carol.id), sent.outgoing.map { it.id }.toSet())
        assertEquals(FriendsResponse(friends = listOf(alice.summary)), service.accept(bob.id, alice.id))
    }

    @Test
    fun removeAFriend() {
        val (alice, bob) = friends()

        assertEquals(FriendsResponse(), service.remove(alice.id, bob.id))
        assertEquals(FriendsResponse(), service.friends(bob.id))
        assertEquals(FriendsResponse(), service.remove(alice.id, bob.id))
    }

    @Test
    fun blockingEndsTheFriendshipAndTheBlockersGroups() {
        val (alice, bob) = friends()
        val carol = testUsers.create()
        befriend(alice, carol)
        befriend(bob, carol)
        val alicesGroup = groupService.create(alice.id, CreateGroupRequest("Alice's", listOf(bob.id, carol.id)))
            .groups.single().id
        val bobsGroup = groupService.create(bob.id, CreateGroupRequest("Bob's", listOf(alice.id, carol.id)))
            .groups.single { it.ownerId == bob.id }.id

        val blocked = service.block(alice.id, bob.id)
        assertEquals(FriendsResponse(friends = listOf(carol.summary), blocked = listOf(bob.summary)), blocked)
        assertEquals(FriendsResponse(friends = listOf(carol.summary)), service.friends(bob.id))
        // Out of the groups Alice owns; Alice stays in Bob's (she may leave).
        assertEquals(listOf(alice.id, carol.id), groups.memberIds(alicesGroup))
        assertEquals(setOf(bob.id, alice.id, carol.id), groups.memberIds(bobsGroup).toSet())
        // Idempotent.
        assertEquals(blocked, service.block(alice.id, bob.id))
    }

    @Test
    fun blockingEndsRequestsBothWays() {
        val alice = testUsers.create()
        val bob = testUsers.create()
        val carol = testUsers.create()
        service.sendRequest(alice.id, SendFriendRequest(userId = bob.id))
        service.sendRequest(carol.id, SendFriendRequest(userId = alice.id))

        service.block(alice.id, bob.id)
        val blocked = service.block(alice.id, carol.id)
        assertEquals(FriendsResponse(blocked = listOf(bob.summary, carol.summary).sortedByNickname()), blocked)
        assertEquals(FriendsResponse(), service.friends(bob.id))
        assertEquals(FriendsResponse(), service.friends(carol.id))
    }

    @Test
    fun theBlockedUserDoesNotLearnAboutTheBlock() {
        val alice = testUsers.create()
        val bob = testUsers.create()
        service.block(alice.id, bob.id)

        // Alice can't ask Bob while she blocks him.
        assertError(ErrorCode.WRONG_STATE, ErrorReason.BLOCKED_BY_YOU) {
            service.sendRequest(alice.id, SendFriendRequest(userId = bob.id))
        }
        // Bob's request looks like any other to him, but Alice never sees it and can't accept it.
        assertEquals(
            FriendsResponse(outgoing = listOf(alice.summary)),
            service.sendRequest(bob.id, SendFriendRequest(nickname = alice.nickname)),
        )
        assertEquals(FriendsResponse(blocked = listOf(bob.summary)), service.friends(alice.id))
        assertError(ErrorCode.NOT_FOUND, null) { service.accept(alice.id, bob.id) }

        // Unblocking drops what Bob sent meanwhile.
        assertEquals(FriendsResponse(), service.unblock(alice.id, bob.id))
        assertEquals(FriendsResponse(), service.friends(bob.id))
        assertEquals(FriendsResponse(), service.unblock(alice.id, bob.id))
        // And they can be friends again.
        service.sendRequest(bob.id, SendFriendRequest(userId = alice.id))
        assertEquals(FriendsResponse(friends = listOf(bob.summary)), service.accept(alice.id, bob.id))
    }

    @Test
    fun blockNeedsAnotherKnownUser() {
        val alice = testUsers.create()
        val unconfirmed = testUsers.create(verified = false)

        assertError(ErrorCode.BAD_REQUEST, null) { service.block(alice.id, alice.id) }
        assertError(ErrorCode.NOT_FOUND, ErrorReason.USER_NOT_FOUND) { service.block(alice.id, UserId("nobody")) }
        // Unblocking someone who isn't blocked is fine.
        assertEquals(FriendsResponse(), service.unblock(alice.id, UserId("nobody")))
        // Whether their email is confirmed doesn't matter.
        assertEquals(FriendsResponse(blocked = listOf(unconfirmed.summary)), service.block(alice.id, unconfirmed.id))
    }

    @Test
    fun listsAreSortedByNicknameIgnoringCase() {
        val alice = testUsers.create()
        val friends = listOf("b", "A", "c").map { testUsers.create(prefix = it) }
        friends.forEach { befriend(alice, it) }
        val requests = listOf("B", "a").map { testUsers.create(prefix = it) }
        requests.forEach { service.sendRequest(it.id, SendFriendRequest(userId = alice.id)) }

        val list = service.friends(alice.id)
        assertEquals(listOf("A", "b", "c"), list.friends.map { it.nickname.take(1) })
        assertEquals(listOf("a", "B"), list.incoming.map { it.nickname.take(1) })
    }

    @Test
    fun friendLimit() {
        val alice = testUsers.create()
        val bob = testUsers.create()
        val carol = testUsers.create()
        service.sendRequest(alice.id, SendFriendRequest(userId = carol.id))
        testUsers.addFriends(alice, SocialLimits.MAX_FRIENDS)

        assertError(ErrorCode.WRONG_STATE, ErrorReason.LIMIT_REACHED) {
            service.sendRequest(alice.id, SendFriendRequest(userId = bob.id))
        }
        // Bob may ask, but Alice can't accept.
        service.sendRequest(bob.id, SendFriendRequest(userId = alice.id))
        assertError(ErrorCode.WRONG_STATE, ErrorReason.LIMIT_REACHED) { service.accept(alice.id, bob.id) }
        assertError(ErrorCode.WRONG_STATE, ErrorReason.LIMIT_REACHED) {
            service.sendRequest(alice.id, SendFriendRequest(userId = bob.id))
        }
        // Nor can Carol accept what Alice sent before: Alice has no room.
        assertError(ErrorCode.WRONG_STATE, ErrorReason.LIMIT_REACHED) { service.accept(carol.id, alice.id) }
        assertEquals(FriendsResponse(outgoing = listOf(alice.summary)), service.friends(bob.id))

        // One friend less: room for one more.
        service.remove(alice.id, service.friends(alice.id).friends.first().id)
        assertContains(service.accept(alice.id, bob.id).friends, bob.summary)
        assertError(ErrorCode.WRONG_STATE, ErrorReason.LIMIT_REACHED) { service.accept(carol.id, alice.id) }
    }

    @Test
    fun outgoingRequestLimit() {
        val alice = testUsers.create()
        val bob = testUsers.create()
        testUsers.addOutgoingRequests(alice, SocialLimits.MAX_OUTGOING_REQUESTS)

        assertError(ErrorCode.WRONG_STATE, ErrorReason.LIMIT_REACHED) {
            service.sendRequest(alice.id, SendFriendRequest(userId = bob.id))
        }
        // Accepting a request needs no room for another one.
        service.sendRequest(bob.id, SendFriendRequest(userId = alice.id))
        assertEquals(listOf(bob.summary), service.accept(alice.id, bob.id).friends)
    }

    private fun friends(): Pair<TestUser, TestUser> {
        val alice = testUsers.create()
        val bob = testUsers.create()
        befriend(alice, bob)
        return alice to bob
    }

    private fun befriend(a: TestUser, b: TestUser) {
        service.sendRequest(a.id, SendFriendRequest(userId = b.id))
        service.accept(b.id, a.id)
    }

    private fun assertError(code: ErrorCode, reason: ErrorReason?, block: () -> Unit) {
        val error = assertFailsWith<GameException> { block() }
        assertEquals(code, error.code, error.message)
        if (reason == null) assertNull(error.reason, error.message) else assertEquals(reason, error.reason)
    }
}
