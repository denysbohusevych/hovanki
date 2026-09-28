package app.hovanki.server.api

import app.hovanki.server.MutableClock
import app.hovanki.server.account.AccountSessionRepository
import app.hovanki.server.account.AccountTestConfig
import app.hovanki.server.account.PasswordHasher
import app.hovanki.server.account.UserRepository
import app.hovanki.server.game.GameJanitor
import app.hovanki.server.game.IdGenerator
import app.hovanki.server.social.FriendService
import app.hovanki.server.social.GroupService
import app.hovanki.server.social.Invite
import app.hovanki.server.social.InviteRegistry
import app.hovanki.server.social.SocialLimits
import app.hovanki.server.social.TestUser
import app.hovanki.server.social.TestUsers
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.CreateGroupRequest
import app.hovanki.shared.protocol.DeleteAccountRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameInvite
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.Inbox
import app.hovanki.shared.protocol.InviteId
import app.hovanki.shared.protocol.InviteRequest
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.SendFriendRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.shrinkingZone
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Game invitations and the inbox over HTTP, as the app uses them; the same context as the account tests. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountTestConfig::class)
class InviteApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val friendService: FriendService,
    @Autowired private val groupService: GroupService,
    @Autowired private val invites: InviteRegistry,
    @Autowired private val janitor: GameJanitor,
    @Autowired users: UserRepository,
    @Autowired sessions: AccountSessionRepository,
    @Autowired hasher: PasswordHasher,
    @Autowired ids: IdGenerator,
    @Autowired jdbc: JdbcClient,
    @Autowired private val clock: MutableClock,
) {
    private val testUsers = TestUsers(users, sessions, hasher, ids, jdbc, clock)
    private val settings = GameSettings(zone = shrinkingZone(GeoPoint(50.4501, 30.5234)), hidingSeconds = 0)

    @Test
    fun inviteAFriendWhoJoins() {
        val host = testUsers.create()
        val friend = friendOf(host)
        val someone = testUsers.create()
        friendService.sendRequest(someone.id, SendFriendRequest(userId = friend.id))
        val game = createGame(host)

        val snapshot = invite(game.session, InviteRequest(listOf(friend.id))).ok<GameSnapshot>()
        assertEquals(game.snapshot.players, snapshot.players)

        val inbox = inbox(friend).ok<Inbox>()
        val expected = GameInvite(
            id = inbox.invites.single().id,
            gameId = game.session.gameId,
            joinCode = game.snapshot.joinCode,
            from = host.summary,
            createdAtMillis = clock.millis(),
            expiresAtMillis = clock.millis() + SocialLimits.INVITE_TTL.toMillis(),
        )
        assertEquals(Inbox(listOf(expected), friendRequests = listOf(someone.summary)), inbox)

        // Accepting is joining with the code while logged in: the invitation is answered.
        val joined = join(expected.joinCode, friend).ok<SessionResponse>()
        assertEquals(friend.id, joined.snapshot.players.single { it.id == joined.session.playerId }.userId)
        assertEquals(Inbox(friendRequests = listOf(someone.summary)), inbox(friend).ok())
        // Also when a player comes back (another phone).
        invites.add(invite(game.session.gameId, from = host, to = friend))
        join(expected.joinCode, friend).ok<SessionResponse>()
        assertEquals(emptyList(), invites.of(friend.id, clock.millis()))
    }

    @Test
    fun aGroupInvitationReachesEveryMember() {
        val host = testUsers.create()
        val owner = friendOf(host)
        val (playing, stranger) = List(2) { friendOf(owner) }
        val friend = friendOf(host)
        val group = groupService.create(owner.id, CreateGroupRequest("Crew", listOf(host.id, playing.id, stranger.id)))
            .groups.single()
        val game = createGame(host)
        join(game.snapshot.joinCode, playing).ok<SessionResponse>()

        invite(game.session, InviteRequest(listOf(friend.id, owner.id), group.id)).ok<GameSnapshot>()

        // Everyone but the inviter and those in the game already; the group's members are told it's the group.
        for (member in listOf(owner, stranger)) {
            val received = inbox(member).ok<Inbox>().invites.single()
            assertEquals(host.summary, received.from)
            assertEquals(group.id to "Crew", received.groupId to received.groupName)
        }
        val direct = inbox(friend).ok<Inbox>().invites.single()
        assertEquals(null to null, direct.groupId to direct.groupName)
        for (user in listOf(host, playing)) assertEquals(emptyList(), invites.of(user.id, clock.millis()))
    }

    @Test
    fun groupMembersWithABlockAreSkippedSilently() {
        val host = testUsers.create()
        val owner = friendOf(host)
        val (blocksHost, blockedByHost, member) = List(3) { friendOf(owner) }
        val group = groupService.create(
            owner.id,
            CreateGroupRequest("Crew", listOf(host.id, blocksHost.id, blockedByHost.id, member.id)),
        ).groups.single()
        friendService.block(blocksHost.id, host.id)
        friendService.block(host.id, blockedByHost.id)
        val game = createGame(host)

        invite(game.session, InviteRequest(groupId = group.id)).ok<GameSnapshot>()

        assertEquals(1, inbox(member).ok<Inbox>().invites.size)
        // Not even stored (the inbox would hide them too).
        for (user in listOf(blocksHost, blockedByHost)) assertEquals(emptyList(), invites.of(user.id, clock.millis()))
    }

    @Test
    fun invitationsFromUsersBlockedLaterAreHidden() {
        val host = testUsers.create()
        val friend = friendOf(host)
        val game = createGame(host)
        invite(game.session, InviteRequest(listOf(friend.id))).ok<GameSnapshot>()

        post(ApiRoutes.userBlock(host.id), null, friend.token).expect(200)
        assertEquals(Inbox(), inbox(friend).ok())
    }

    @Test
    fun whoMayInviteWhom() {
        val host = testUsers.create()
        val friend = friendOf(host)
        val stranger = testUsers.create()
        val game = createGame(host)

        // Guests can't invite: neither a guest host nor a guest who joined.
        val guestGame = createGame(user = null)
        invite(guestGame.session, InviteRequest(listOf(friend.id)))
            .error(403, ErrorCode.FORBIDDEN, ErrorReason.ACCOUNT_REQUIRED)
        val guest = join(game.snapshot.joinCode, user = null).ok<SessionResponse>()
        invite(guest.session, InviteRequest(listOf(friend.id)))
            .error(403, ErrorCode.FORBIDDEN, ErrorReason.ACCOUNT_REQUIRED)

        invite(game.session, InviteRequest()).error(400, ErrorCode.BAD_REQUEST)
        // All or nothing: one stranger fails the whole request.
        invite(game.session, InviteRequest(listOf(friend.id, stranger.id)))
            .error(403, ErrorCode.FORBIDDEN, ErrorReason.NOT_FRIENDS)
        invite(game.session, InviteRequest(listOf(UserId("nobody"))))
            .error(403, ErrorCode.FORBIDDEN, ErrorReason.NOT_FRIENDS)
        assertEquals(Inbox(), inbox(friend).ok())
        // Groups the inviter is not in don't exist for them.
        val othersGroup = groupService.create(friend.id, CreateGroupRequest("Theirs")).groups.single()
        invite(game.session, InviteRequest(groupId = othersGroup.id)).error(404, ErrorCode.NOT_FOUND)
        invite(game.session, InviteRequest(groupId = GroupId("nothing"))).error(404, ErrorCode.NOT_FOUND)
        // Inviting yourself is skipped.
        invite(game.session, InviteRequest(listOf(host.id))).ok<GameSnapshot>()
        assertEquals(Inbox(), inbox(host).ok())
        // Another game's token.
        invite(guestGame.session.copy(gameId = game.session.gameId), InviteRequest(listOf(friend.id)))
            .error(403, ErrorCode.FORBIDDEN)

        val player = join(game.snapshot.joinCode, testUsers.create()).ok<SessionResponse>()
        start(game.session, seeker = player.session)
        invite(game.session, InviteRequest(listOf(friend.id))).error(409, ErrorCode.WRONG_STATE)
    }

    @Test
    fun startingTheGameEndsTheInvitations() {
        val host = testUsers.create()
        val (polling, notPolling) = List(2) { friendOf(host) }
        val game = createGame(host)
        val player = join(game.snapshot.joinCode, testUsers.create()).ok<SessionResponse>()
        invite(game.session, InviteRequest(listOf(polling.id, notPolling.id))).ok<GameSnapshot>()
        assertEquals(1, inbox(polling).ok<Inbox>().invites.size)

        start(game.session, seeker = player.session)
        // The inbox checks the game...
        assertEquals(Inbox(), inbox(polling).ok())
        assertEquals(emptyList(), invites.of(polling.id, clock.millis()))
        // ... and the janitor drops the rest.
        assertEquals(1, invites.of(notPolling.id, clock.millis()).size)
        janitor.removeExpiredGames()
        assertEquals(emptyList(), invites.of(notPolling.id, clock.millis()))
    }

    @Test
    fun invitationsExpire() {
        val host = testUsers.create()
        val friend = friendOf(host)
        val game = createGame(host)
        invite(game.session, InviteRequest(listOf(friend.id))).ok<GameSnapshot>()

        clock.advance(SocialLimits.INVITE_TTL.minusMillis(1))
        assertEquals(1, inbox(friend).ok<Inbox>().invites.size)
        clock.advance(Duration.ofMillis(1))
        assertEquals(Inbox(), inbox(friend).ok())
        // Inviting again works.
        invite(game.session, InviteRequest(listOf(friend.id))).ok<GameSnapshot>()
        assertEquals(1, inbox(friend).ok<Inbox>().invites.size)
    }

    @Test
    fun invitingAgainReplacesTheInvitation() {
        val host = testUsers.create()
        val cohost = friendOf(host)
        val friend = friendOf(host)
        friendService.sendRequest(cohost.id, SendFriendRequest(userId = friend.id))
        friendService.accept(friend.id, cohost.id)
        val game = createGame(host)
        val cohostSession = join(game.snapshot.joinCode, cohost).ok<SessionResponse>().session

        invite(game.session, InviteRequest(listOf(friend.id))).ok<GameSnapshot>()
        clock.advance(Duration.ofMinutes(1))
        invite(cohostSession, InviteRequest(listOf(friend.id))).ok<GameSnapshot>()

        val received = inbox(friend).ok<Inbox>().invites.single()
        assertEquals(cohost.summary, received.from)
        assertEquals(clock.millis(), received.createdAtMillis)
    }

    @Test
    fun dismissAnInvitation() {
        val host = testUsers.create()
        val (friend, other) = List(2) { friendOf(host) }
        // Another host: an account plays in one lobby at a time.
        val secondHost = testUsers.create()
        friendService.sendRequest(secondHost.id, SendFriendRequest(userId = friend.id))
        friendService.accept(friend.id, secondHost.id)
        val game = createGame(host)
        val secondGame = createGame(secondHost)
        invite(game.session, InviteRequest(listOf(friend.id, other.id))).ok<GameSnapshot>()
        clock.advance(Duration.ofSeconds(1))
        invite(secondGame.session, InviteRequest(listOf(friend.id))).ok<GameSnapshot>()
        val (newest, oldest) = inbox(friend).ok<Inbox>().invites
        assertEquals(secondGame.session.gameId to game.session.gameId, newest.gameId to oldest.gameId)

        // Someone else's or an unknown invitation: nothing changes.
        post(ApiRoutes.inviteDismiss(oldest.id), null, other.token).ok<Inbox>()
        assertEquals(listOf(newest, oldest), dismiss(friend, InviteId("unknown")).ok<Inbox>().invites)
        assertEquals(Inbox(listOf(newest)), dismiss(friend, oldest.id).ok())
        assertEquals(1, inbox(other).ok<Inbox>().invites.size)
    }

    @Test
    fun deletedAccountsTakeTheirInvitations() {
        val host = testUsers.create()
        val (leaving, staying) = List(2) { friendOf(host) }
        val game = createGame(host)
        invite(game.session, InviteRequest(listOf(leaving.id, staying.id))).ok<GameSnapshot>()

        post(ApiRoutes.ME_DELETE, DeleteAccountRequest(TestUsers.PASSWORD).toJson(), leaving.token).expect(204)
        assertEquals(emptyList(), invites.of(leaving.id, clock.millis()))
        assertEquals(1, inbox(staying).ok<Inbox>().invites.size)

        post(ApiRoutes.ME_DELETE, DeleteAccountRequest(TestUsers.PASSWORD).toJson(), host.token).expect(204)
        assertEquals(Inbox(), inbox(staying).ok())
        assertEquals(emptyList(), invites.of(staying.id, clock.millis()))
    }

    @Test
    fun theInboxNeedsAnAccount() {
        for (call in listOf<(String?) -> Response>(
            { get(ApiRoutes.INBOX, it) },
            { post(ApiRoutes.inviteDismiss(InviteId("invite")), null, it) },
        )) {
            call(null).error(401, ErrorCode.UNAUTHORIZED)
            call("nope").error(401, ErrorCode.UNAUTHORIZED, ErrorReason.SESSION_EXPIRED)
        }
        // Inviting takes a game token.
        post(ApiRoutes.gameInvites(GameId("game")), InviteRequest(listOf(UserId("someone"))).toJson(), null)
            .error(401, ErrorCode.UNAUTHORIZED)
    }

    @Test
    fun accountsWithoutAConfirmedEmailInviteAndAreInvited() {
        // Confirming the email is optional: neither did.
        val host = testUsers.create(verified = false)
        val friend = friendOf(host, verified = false)
        val game = createGame(host)
        assertEquals(host.id, game.snapshot.players.single().userId)

        invite(game.session, InviteRequest(listOf(friend.id))).ok<GameSnapshot>()
        val invitation = inbox(friend).ok<Inbox>().invites.single()
        assertEquals(host.summary, invitation.from)
        val joined = join(invitation.joinCode, friend).ok<SessionResponse>()
        assertEquals(friend.id, joined.snapshot.players.single { it.id == joined.session.playerId }.userId)
        assertEquals(Inbox(), inbox(friend).ok())
    }

    private fun friendOf(user: TestUser, verified: Boolean = true): TestUser {
        val friend = testUsers.create(verified = verified)
        friendService.sendRequest(user.id, SendFriendRequest(userId = friend.id))
        friendService.accept(friend.id, user.id)
        return friend
    }

    /** A game hosted by [user]'s player, or by a guest. */
    private fun createGame(user: TestUser?): SessionResponse =
        post(ApiRoutes.GAMES, CreateGameRequest("Guest host", settings).toJson(), user?.token).ok()

    private fun join(joinCode: String, user: TestUser?): Response =
        post(ApiRoutes.JOIN, JoinGameRequest(joinCode, "Guest").toJson(), user?.token)

    private fun start(host: PlayerSession, seeker: PlayerSession) =
        post(ApiRoutes.start(host.gameId), StartGameRequest(listOf(seeker.playerId)).toJson(), host.token).expect(200)

    private fun invite(session: PlayerSession, request: InviteRequest) =
        post(ApiRoutes.gameInvites(session.gameId), request.toJson(), session.token)

    private fun inbox(user: TestUser) = get(ApiRoutes.INBOX, user.token)

    private fun dismiss(user: TestUser, id: InviteId) = post(ApiRoutes.inviteDismiss(id), null, user.token)

    private fun invite(gameId: GameId, from: TestUser, to: TestUser) = Invite(
        id = InviteId("manual-${to.id.value}"),
        gameId = gameId,
        joinCode = "ABCDEF",
        inviterId = from.id,
        inviteeId = to.id,
        createdAtMillis = clock.millis(),
        expiresAtMillis = clock.millis() + SocialLimits.INVITE_TTL.toMillis(),
    )

    private data class Response(val status: Int, val body: String) {
        fun expect(status: Int) = also { assertEquals(status, this.status, body) }

        inline fun <reified T> ok(): T = protocolJson.decodeFromString(expect(200).body)

        /** An [ApiError] with [code] and exactly [reason] (null: none). */
        fun error(status: Int, code: ErrorCode, reason: ErrorReason? = null) {
            val error = protocolJson.decodeFromString<ApiError>(expect(status).body)
            assertEquals(code, error.code, body)
            if (reason == null) assertNull(error.reason, body) else assertEquals(reason, error.reason, body)
        }
    }

    private inline fun <reified T> T.toJson(): String = protocolJson.encodeToString(this)

    private fun post(path: String, json: String?, token: String?): Response {
        val response = mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            if (json != null) content = json
            if (token != null) header("Authorization", "${ApiRoutes.AUTH_SCHEME} $token")
        }.andReturn().response
        return Response(response.status, response.getContentAsString(Charsets.UTF_8))
    }

    private fun get(path: String, token: String?): Response {
        val response = mvc.get(path) {
            if (token != null) header("Authorization", "${ApiRoutes.AUTH_SCHEME} $token")
        }.andReturn().response
        return Response(response.status, response.getContentAsString(Charsets.UTF_8))
    }
}
