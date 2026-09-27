package app.hovanki.shared.protocol

import app.hovanki.shared.rules.shrinkingZone
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolJsonTest {
    private val me = PlayerId("p1")
    private val snapshot = GameSnapshot(
        gameId = GameId("g1"),
        joinCode = "ABC123",
        hostId = me,
        phase = GamePhase.LOBBY,
        settings = GameSettings(zone = shrinkingZone(GeoPoint(50.45, 30.52))),
        serverTimeMillis = 1_700_000_000_000,
        players = listOf(PlayerView(me, "Denys", Role.HIDER, PlayerStatus.ACTIVE)),
        me = MyState(me, Role.HIDER, PlayerStatus.ACTIVE),
    )

    @Test
    fun roundTrip() {
        val json = protocolJson.encodeToString(snapshot)
        assertEquals(snapshot, protocolJson.decodeFromString<GameSnapshot>(json))
    }

    @Test
    fun idsAreEncodedAsPlainStringsAndNullsAreOmitted() {
        val json = protocolJson.encodeToString(snapshot)
        assertTrue(""""gameId":"g1"""" in json, json)
        assertTrue("phaseEndsAtMillis" !in json, json)
    }

    @Test
    fun olderClientsIgnoreNewFields() {
        val json = """{"code":"TOO_FAR","message":"Too far","hint":"walk closer"}"""
        assertEquals(ApiError(ErrorCode.TOO_FAR, "Too far"), protocolJson.decodeFromString<ApiError>(json))
    }

    /** What the first app versions know of a revealed player: `reason` without a default, no `cause`. */
    @Serializable
    private data class FirstVersionVisibleLocation(
        val point: GeoPoint,
        val accuracyMeters: Double,
        val atMillis: Long,
        val reason: VisibilityReason,
    )

    @Test
    fun aBuildingRevealStaysReadableForTheFirstAppVersions() {
        val revealed = VisibleLocation(
            GeoPoint(50.45, 30.52),
            8.0,
            1_700_000_000_000,
            reason = VisibilityReason.OUT_OF_ZONE,
            cause = VisibilityReason.INSIDE_BUILDING,
        )
        val json = protocolJson.encodeToString(revealed)

        assertEquals(
            VisibilityReason.OUT_OF_ZONE,
            protocolJson.decodeFromString<FirstVersionVisibleLocation>(json).reason,
        )
        assertEquals(revealed, protocolJson.decodeFromString<VisibleLocation>(json))
    }

    @Test
    fun laterValuesOfDefaultedEnumsFallBackToNull() {
        val location = """{"point":{"lat":50.45,"lon":30.52},"accuracyMeters":8.0,"atMillis":1,""" +
            """"reason":"STALE_SIGNAL","cause":"SOMETHING_NEWER"}"""
        assertEquals(null, protocolJson.decodeFromString<VisibleLocation>(location).cause)

        val withNewState = protocolJson.encodeToString(snapshot).dropLast(1) + ""","buildings":"SOMETHING_NEWER"}"""
        assertEquals(null, protocolJson.decodeFromString<GameSnapshot>(withNewState).buildings)
    }

    @Test
    fun anUnknownErrorReasonIsReadAsNone() {
        val json = """{"code":"WRONG_STATE","message":"Later","reason":"SOMETHING_NEWER"}"""
        assertEquals(ApiError(ErrorCode.WRONG_STATE, "Later"), protocolJson.decodeFromString<ApiError>(json))

        val known = ApiError(ErrorCode.FORBIDDEN, "Wrong login or password", ErrorReason.WRONG_CREDENTIALS)
        assertEquals(known, protocolJson.decodeFromString<ApiError>(protocolJson.encodeToString(known)))
    }

    /** What the first app versions know of an error: no `reason`. */
    @Serializable
    private data class FirstVersionApiError(val code: ErrorCode, val message: String)

    @Test
    fun theFirstAppVersionsReadErrorsWithAReason() {
        val error = ApiError(ErrorCode.UNAUTHORIZED, "Log in again", ErrorReason.SESSION_EXPIRED)
        val json = protocolJson.encodeToString(error)
        assertEquals(
            FirstVersionApiError(ErrorCode.UNAUTHORIZED, "Log in again"),
            protocolJson.decodeFromString<FirstVersionApiError>(json),
        )
    }

    @Test
    fun aSyncOfTheFirstAppVersionsAsksForNoChat() {
        val request = protocolJson.decodeFromString<SyncRequest>("""{"samples":[]}""")
        assertEquals(SyncRequest(samples = emptyList(), chatAfter = null), request)
        assertEquals(null, request.chatAfter)
    }

    @Test
    fun chatAndAccountsDefaultForTheFirstServers() {
        // A snapshot without chat and a player without an account, as servers without accounts send them.
        val json = protocolJson.encodeToString(snapshot)
        val decoded = protocolJson.decodeFromString<GameSnapshot>(json)
        assertEquals(emptyList(), decoded.chat)
        assertEquals(null, decoded.players.single().userId)
    }

    @Test
    fun anUnknownChatChannelIsReadAsEveryone() {
        val json = """{"seq":3,"playerId":"p1","text":"hi","sentAtMillis":1,"channel":"SOMETHING_NEWER"}"""
        assertEquals(
            ChatMessage(3, PlayerId("p1"), "hi", 1, ChatChannel.ALL),
            protocolJson.decodeFromString<ChatMessage>(json),
        )
    }

    @Test
    fun chatAndUserIdsRoundTrip() {
        val withChat = snapshot.copy(
            players = listOf(PlayerView(me, "Denys", Role.HIDER, PlayerStatus.ACTIVE, userId = UserId("u1"))),
            chat = listOf(ChatMessage(1, me, "Here", 1_700_000_000_000, ChatChannel.HIDERS)),
        )
        val json = protocolJson.encodeToString(withChat)
        assertTrue(""""userId":"u1"""" in json, json)
        assertEquals(withChat, protocolJson.decodeFromString<GameSnapshot>(json))
    }

    @Test
    fun socialResponsesRoundTrip() {
        val friend = UserSummary(UserId("u2"), "olena")
        val inbox = Inbox(
            invites = listOf(
                GameInvite(
                    id = InviteId("i1"),
                    gameId = GameId("g1"),
                    joinCode = "ABC123",
                    from = friend,
                    groupId = GroupId("gr1"),
                    groupName = "Park crew",
                    createdAtMillis = 1,
                    expiresAtMillis = 2,
                ),
            ),
            friendRequests = listOf(friend),
        )
        assertEquals(inbox, protocolJson.decodeFromString<Inbox>(protocolJson.encodeToString(inbox)))
        assertEquals(FriendsResponse(), protocolJson.decodeFromString<FriendsResponse>("{}"))
        val session = AccountSession("t", UserProfile(UserId("u1"), "denys", "d@example.com", false, 1))
        assertEquals(session, protocolJson.decodeFromString<AccountSession>(protocolJson.encodeToString(session)))
    }

    @Test
    fun routesAreFilled() {
        assertEquals("/api/v1/games/g1/catches/c1/confirm", ApiRoutes.catchConfirm(GameId("g1"), CatchId("c1")))
        assertEquals("/api/v1/games/g1/buildings", ApiRoutes.buildings(GameId("g1")))
        assertEquals("/api/v1/games/g1/chat/42/report", ApiRoutes.chatReport(GameId("g1"), 42))
        assertEquals("/api/v1/games/g1/invites", ApiRoutes.gameInvites(GameId("g1")))
        assertEquals("/api/v1/me/invites/i1/dismiss", ApiRoutes.inviteDismiss(InviteId("i1")))
        assertEquals("/api/v1/friends/requests/u1/accept", ApiRoutes.friendRequestAccept(UserId("u1")))
        assertEquals(
            "/api/v1/groups/gr1/members/u1/remove",
            ApiRoutes.groupMemberRemove(GroupId("gr1"), UserId("u1")),
        )
        assertEquals("/api/v1/users/u1/block", ApiRoutes.userBlock(UserId("u1")))
    }
}
