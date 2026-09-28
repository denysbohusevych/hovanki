package app.hovanki.server.bigGames

import app.hovanki.server.MutableClock
import app.hovanki.server.account.AccountSessionRepository
import app.hovanki.server.account.AccountTestConfig
import app.hovanki.server.account.PasswordHasher
import app.hovanki.server.account.UserRepository
import app.hovanki.server.admin.AuditLog
import app.hovanki.server.admin.Staff
import app.hovanki.server.db.toTimestamptz
import app.hovanki.server.game.Game
import app.hovanki.server.game.GameException
import app.hovanki.server.game.GameRegistry
import app.hovanki.server.game.GameService
import app.hovanki.server.game.IdGenerator
import app.hovanki.server.game.PlayerRef
import app.hovanki.server.social.InviteService
import app.hovanki.server.social.TestUser
import app.hovanki.server.social.TestUsers
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.AdminAction
import app.hovanki.shared.protocol.AdminBigGame
import app.hovanki.shared.protocol.AdminBigGameRequest
import app.hovanki.shared.protocol.AdminZoneEstimateRequest
import app.hovanki.shared.protocol.BigGameSetup
import app.hovanki.shared.protocol.BigGameStatus
import app.hovanki.shared.protocol.CapacityState
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinBigGameRequest
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.UserRole
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.protocol.ZoneShape
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Big games (docs/adr/0010-big-games.md): the admin's schedule, the sign-ups, the lobby, the start, the end. */
@SpringBootTest
@Import(AccountTestConfig::class)
class BigGameServiceTest(
    @Autowired private val bigGames: BigGameService,
    @Autowired private val games: GameService,
    @Autowired private val registry: GameRegistry,
    @Autowired private val invites: InviteService,
    @Autowired private val audit: AuditLog,
    @Autowired private val clock: MutableClock,
    @Autowired users: UserRepository,
    @Autowired sessions: AccountSessionRepository,
    @Autowired hasher: PasswordHasher,
    @Autowired ids: IdGenerator,
    @Autowired private val jdbc: JdbcClient,
) {
    private val testUsers = TestUsers(users, sessions, hasher, ids, jdbc, clock)
    private val adminUser = testUsers.create("admin")
    private val admin = staff(adminUser, UserRole.ADMIN)
    private val park = GeoPoint(50.4501, 30.5234)

    /** 200 × 200 m: 40 000 m² of built-up ground (the test source), 40 players. */
    private val square = ZonePolygon(
        listOf(
            park.moveBy(-100.0, -100.0),
            park.moveBy(100.0, -100.0),
            park.moveBy(100.0, 100.0),
            park.moveBy(-100.0, 100.0),
        ),
    )

    private fun staff(user: TestUser, role: UserRole) =
        Staff(user.id, user.nickname, role, "hash", clock.instant().plus(Duration.ofHours(1)))

    private fun request(
        startsIn: Duration = Duration.ofHours(2),
        playerLimit: Int? = null,
        setup: BigGameSetup = BigGameSetup(hidingMinutes = 1, seekingMinutes = 10, seekers = 2),
        zone: ZonePolygon = square,
        title: String = "Saturday in the park",
    ) = AdminBigGameRequest(
        title = title,
        startsAtMillis = clock.instant().plus(startsIn).toEpochMilli(),
        timeZone = "Europe/Kyiv",
        zone = zone,
        setup = setup,
        playerLimit = playerLimit,
        reason = "a test",
    )

    private fun later(duration: Duration) {
        clock.advance(duration)
        bigGames.tick()
    }

    private fun status(game: AdminBigGame): BigGameStatus =
        bigGames.list(admin).games.single { it.id == game.id }.status

    private fun admin(game: AdminBigGame): AdminBigGame = bigGames.list(admin).games.single { it.id == game.id }

    @Test
    fun anAdminSchedulesItAndPlayersSignUp() {
        val game = bigGames.create(admin, request())

        assertEquals(BigGameStatus.SCHEDULED, game.status)
        assertEquals(40, game.capacity)
        assertEquals(40, game.playerLimit, "as many as the zone fits")
        assertEquals(40_000.0, game.areaSquareMeters.toDouble(), 10.0)
        val anna = testUsers.create()
        val card = bigGames.cards(anna.auth).games.single { it.id == game.id }
        assertFalse(card.signedUpByMe)

        val signed = bigGames.signUp(anna.auth, game.id)

        assertTrue(signed.signedUpByMe)
        assertEquals(1, signed.signedUp)
        assertFalse(signed.canJoin, "the lobby is not open yet")
        assertEquals(1, bigGames.signUp(anna.auth, game.id).signedUp, "again changes nothing")
        assertEquals(0, bigGames.cancelSignup(anna.auth, game.id).signedUp)
        val host = assertFailsWith<GameException> { bigGames.signUp(adminUser.auth, game.id) }
        assertEquals(ErrorCode.FORBIDDEN, host.code, "the admin running it can't play")
        assertTrue(audit.page(null, 5).any { it.action == AdminAction.BIG_GAME_CREATE && it.reason == "a test" })
    }

    @Test
    fun friendsWhoSignedUpAreShown() {
        val game = bigGames.create(admin, request())
        val anna = testUsers.create()
        val boris = testUsers.create()
        friends(anna, boris)

        bigGames.signUp(boris.auth, game.id)

        assertEquals(listOf(boris.summary), bigGames.cards(anna.auth).games.single { it.id == game.id }.friends)
    }

    @Test
    fun theLobbyOpensHalfAnHourBeforeAndTheRoundStartsOnTime() {
        val game = bigGames.create(admin, request())
        val players = List(4) { testUsers.create() }
        players.forEach { bigGames.signUp(it.auth, game.id) }
        val stranger = testUsers.create()

        later(Duration.ofMinutes(89))
        assertEquals(BigGameStatus.SCHEDULED, status(game))
        later(Duration.ofMinutes(2))
        assertEquals(BigGameStatus.LOBBY, status(game))

        // The signed-up see the invitation; the lobby is the server's.
        val inbox = invites.inbox(players[0].id)
        assertEquals(listOf(game.id), inbox.bigGames.map { it.id })
        assertTrue(inbox.bigGames.single().canJoin)
        val sessions = players.map { bigGames.join(it.auth, game.id, JoinBigGameRequest()) }
        val snapshot = sessions.last().snapshot
        assertEquals(Game.SERVER_HOST, snapshot.hostId)
        assertEquals(game.id, snapshot.bigGame?.id)
        assertEquals(4, snapshot.bigGame?.signedUp)
        assertEquals(ZoneShape.DRAWN, snapshot.settings.zoneShape)
        assertEquals(StreetZoneState.READY, snapshot.streetZone, "the drawn zone is there at once")
        val refused = assertFailsWith<GameException> { bigGames.join(stranger.auth, game.id, JoinBigGameRequest()) }
        assertEquals(ErrorReason.BIG_GAME_SIGNUP_REQUIRED, refused.reason)
        val byCode = assertFailsWith<GameException> { games.join(JoinGameRequest(snapshot.joinCode, "Me")) }
        assertEquals(ErrorCode.NOT_FOUND, byCode.code, "no way in by the code")

        later(Duration.ofMinutes(30))

        assertEquals(BigGameStatus.RUNNING, status(game))
        val round = sync(sessions.first())
        assertEquals(GamePhase.HIDING, round.phase)
        assertEquals(2, round.players.count { it.role == Role.SEEKER }, "the server drew the seekers")
        // Hiding 1 min, search 10 min: over.
        later(Duration.ofMinutes(12))
        assertEquals(GamePhase.FINISHED, sync(sessions.first()).phase)
        later(Duration.ofSeconds(1))
        assertEquals(BigGameStatus.FINISHED, status(game))
        assertTrue(bigGames.cards(players[0].auth).games.none { it.id == game.id }, "no longer in the list")
    }

    @Test
    fun anAdminStartsItEarlierAndLeavingDoesNotCloseTheLobby() {
        val game = bigGames.create(admin, request(startsIn = Duration.ofMinutes(10)))
        assertEquals(BigGameStatus.LOBBY, game.status, "due already: the lobby opens at once")
        val (anna, boris) = List(2) { testUsers.create() }
        listOf(anna, boris).forEach { bigGames.signUp(it.auth, game.id) }
        val annaIn = bigGames.join(anna.auth, game.id, JoinBigGameRequest())
        val tooFew = assertFailsWith<GameException> { bigGames.start(admin, game.id, "now") }
        assertEquals(ErrorCode.WRONG_STATE, tooFew.code)

        // The last player leaves: the lobby waits on, empty.
        games.leave(annaIn.session.let { PlayerRef(it.gameId, it.playerId) }, annaIn.session.gameId)
        assertNotNull(registry.get(annaIn.session.gameId))
        bigGames.join(anna.auth, game.id, JoinBigGameRequest())
        bigGames.join(boris.auth, game.id, JoinBigGameRequest())

        val started = bigGames.start(admin, game.id, "everybody is here")

        assertEquals(BigGameStatus.RUNNING, started.status)
        assertEquals(2, started.players)
        assertTrue(audit.page(null, 5).any { it.action == AdminAction.BIG_GAME_START })
    }

    @Test
    fun tooFewPlayersAnHourAfterTheStartCancelIt() {
        val game = bigGames.create(admin, request(startsIn = Duration.ofMinutes(20)))
        later(Duration.ofMinutes(21))
        assertEquals(BigGameStatus.LOBBY, status(game), "waits for players")

        later(Duration.ofMinutes(60))

        assertEquals(BigGameStatus.CANCELLED, status(game))
    }

    @Test
    fun aRestartLosesTheLobbyAndItOpensAgain() {
        val game = bigGames.create(admin, request(startsIn = Duration.ofMinutes(20)))
        val first = checkNotNull(admin(game).gameId)

        registry.removeIf { it.id == first }
        later(Duration.ofSeconds(10))

        val again = admin(game)
        assertEquals(BigGameStatus.LOBBY, again.status)
        assertNotEquals(first, again.gameId)
    }

    @Test
    fun aRoundLostWithARestartIsInterruptedAndCanBeScheduledAgain() {
        val game = bigGames.create(admin, request(startsIn = Duration.ofMinutes(5)))
        val players = List(2) { testUsers.create() }
        players.forEach {
            bigGames.signUp(it.auth, game.id)
            bigGames.join(it.auth, game.id, JoinBigGameRequest())
        }
        later(Duration.ofMinutes(6))
        val round = checkNotNull(admin(game).gameId)

        registry.removeIf { it.id == round }
        later(Duration.ofSeconds(10))
        assertEquals(BigGameStatus.INTERRUPTED, status(game))

        val again = bigGames.update(admin, game.id, request(startsIn = Duration.ofDays(1)))
        assertEquals(BigGameStatus.SCHEDULED, again.status)
    }

    @Test
    fun theLimitHoldsAndAboveTheEstimateGoesToTheLog() {
        val game = bigGames.create(admin, request(playerLimit = 3))
        List(3) { testUsers.create() }.forEach { bigGames.signUp(it.auth, game.id) }

        val full = assertFailsWith<GameException> { bigGames.signUp(testUsers.create().auth, game.id) }
        assertEquals(ErrorReason.LIMIT_REACHED, full.reason)

        bigGames.update(admin, game.id, request(playerLimit = 100))
        val entry = audit.page(null, 5).first { it.action == AdminAction.BIG_GAME_UPDATE }
        assertContains(checkNotNull(entry.target), "above the estimate")
        val fewer = request(playerLimit = 2, setup = BigGameSetup(hidingMinutes = 1, seekingMinutes = 10, seekers = 1))
        val lower = assertFailsWith<GameException> { bigGames.update(admin, game.id, fewer) }
        assertEquals(ErrorCode.WRONG_STATE, lower.code, "3 signed up already")
    }

    @Test
    fun theLobbySeesTheAdminsChanges() {
        val game = bigGames.create(admin, request(startsIn = Duration.ofMinutes(20)))
        val anna = testUsers.create()
        bigGames.signUp(anna.auth, game.id)
        val session = bigGames.join(anna.auth, game.id, JoinBigGameRequest())
        val moved = ZonePolygon(square.outline.map { GeoPoint(it.lat + 0.001, it.lon) })

        bigGames.update(admin, game.id, request(startsIn = Duration.ofMinutes(40), zone = moved, title = "Moved"))

        val snapshot = sync(session)
        assertEquals("Moved", snapshot.bigGame?.title)
        assertEquals(1, snapshot.mapRevision, "a new zone")
        assertEquals(StreetZoneState.READY, snapshot.streetZone)
        val zone = games.streetZone(PlayerRef(session.session.gameId, session.session.playerId), session.session.gameId)
        assertEquals(moved.outline.first(), zone.stages.first().outline.first())
    }

    @Test
    fun cancelledTheLobbyCloses() {
        val game = bigGames.create(admin, request(startsIn = Duration.ofMinutes(20)))
        val lobby = checkNotNull(admin(game).gameId)

        val cancelled = bigGames.cancel(admin, game.id, "rain")

        assertEquals(BigGameStatus.CANCELLED, cancelled.status)
        assertNull(registry.get(lobby))
        assertTrue(bigGames.cards(testUsers.create().auth).games.none { it.id == game.id })
    }

    @Test
    fun onlyAdmins() {
        val moderator = staff(testUsers.create("mod"), UserRole.MODERATOR)

        val error = assertFailsWith<GameException> { bigGames.create(moderator, request()) }
        assertEquals(ErrorCode.FORBIDDEN, error.code)
        assertFailsWith<GameException> { bigGames.list(moderator) }
        assertFailsWith<GameException> { bigGames.estimate(moderator, AdminZoneEstimateRequest(square)) }
    }

    @Test
    fun whatTheServerDoesNotTake() {
        val bowtie = ZonePolygon(
            listOf(
                park.moveBy(-100.0, -100.0),
                park.moveBy(100.0, 100.0),
                park.moveBy(100.0, -100.0),
                park.moveBy(-100.0, 100.0),
            ),
        )
        for (bad in listOf(
            request(zone = bowtie),
            request(startsIn = Duration.ofMinutes(-1)),
            request(title = " "),
            request(zone = ZonePolygon(square.outline.map { park.moveBy(0.0, 0.0) })),
            request(setup = BigGameSetup(seekers = 50), playerLimit = 40),
            request().copy(timeZone = "Mars/Olympus"),
            request().copy(reason = ""),
        )) {
            val error = assertFailsWith<GameException> { bigGames.create(admin, bad) }
            assertEquals(ErrorCode.BAD_REQUEST, error.code, bad.toString())
        }
    }

    @Test
    fun theStartInThePlacesTime() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val tomorrow = clock.instant().plus(Duration.ofDays(1)).atZone(tokyo).toLocalDate()
        val game = bigGames.create(admin, request().copy(timeZone = "Asia/Tokyo", startsAtLocal = "${tomorrow}T12:00"))

        assertEquals("${tomorrow}T12:00", game.startsAtLocal)
        assertEquals(
            tomorrow.atTime(12, 0).atZone(tokyo).toInstant(),
            Instant.ofEpochMilli(game.startsAtMillis),
        )
    }

    @Test
    fun theEstimateOfAZoneBeingDrawn() {
        val estimate = bigGames.estimate(admin, AdminZoneEstimateRequest(square))

        assertEquals(CapacityState.READY, estimate.state)
        assertEquals(40, estimate.capacity)
        assertEquals(40_000.0, estimate.areaSquareMeters.toDouble(), 10.0)
    }

    private fun sync(session: SessionResponse) =
        games.sync(PlayerRef(session.session.gameId, session.session.playerId), session.session.gameId, SyncRequest())

    private fun friends(a: TestUser, b: TestUser) {
        for ((x, y) in listOf(a to b, b to a)) {
            jdbc.sql("INSERT INTO friendships VALUES (:a, :b, :t)")
                .param("a", x.id.value)
                .param("b", y.id.value)
                .param("t", clock.instant().toTimestamptz())
                .update()
        }
    }
}
