package app.hovanki.server.leaderboard

import app.hovanki.server.MutableClock
import app.hovanki.server.account.AccountSessionRepository
import app.hovanki.server.account.AccountTestConfig
import app.hovanki.server.account.PasswordHasher
import app.hovanki.server.account.UserRepository
import app.hovanki.server.account.uniqueName
import app.hovanki.server.db.toTimestamptz
import app.hovanki.server.game.IdGenerator
import app.hovanki.server.social.TestUser
import app.hovanki.server.social.TestUsers
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.CityRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.LeaderboardResponse
import app.hovanki.shared.protocol.LeaderboardScope
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.LeaderboardRules
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LeaderboardService] on the test database (docs/adr/0020-leaderboard.md). The results are written straight into
 * `game_results`, each test's world in a week of its own far in the future, so the other tests' games never mix in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountTestConfig::class)
class LeaderboardServiceTest(
    @Autowired private val repository: LeaderboardRepository,
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: MutableClock,
    @Autowired users: UserRepository,
    @Autowired sessions: AccountSessionRepository,
    @Autowired hasher: PasswordHasher,
    @Autowired ids: IdGenerator,
) {
    private val testUsers = TestUsers(users, sessions, hasher, ids, jdbc, clock)

    @Test
    fun theWeekRunsFromMondayToMondayInKyiv() {
        // Kyiv is UTC+2 in winter, UTC+3 in summer.
        val winter = LeaderboardService.weekOf(Instant.parse("2041-01-09T12:00:00Z"))
        assertEquals(Instant.parse("2041-01-06T22:00:00Z"), winter.start)
        assertEquals(Instant.parse("2041-01-13T22:00:00Z"), winter.end)
        assertEquals(winter.start, LeaderboardService.weekOf(Instant.parse("2041-01-06T22:00:00Z")).start)
        assertEquals(winter.start, LeaderboardService.weekOf(Instant.parse("2041-01-13T21:59:59Z")).start)
        val summer = LeaderboardService.weekOf(Instant.parse("2041-07-03T12:00:00Z"))
        assertEquals(Instant.parse("2041-06-30T21:00:00Z"), summer.start)
    }

    @Test
    fun theWorldRanksThisWeeksPointsAndTellsWhoIsAboveAndHowTheCallerMoved() {
        val monday = Instant.parse("2041-01-06T22:00:00Z")
        val now = monday.plus(Duration.ofDays(3))
        val anna = testUsers.create("anna")
        val bob = testUsers.create("bob")
        val carl = testUsers.create("carl")
        val dan = testUsers.create("dan")

        // Last week: Anna first, Carl second.
        game(monday.minusSeconds(1), seeker(anna, catches = 2, won = true), hider(carl, PlayerStatus.CAUGHT, 60))
        // This week: Bob 20 + 3 × 10 + 100 = 150 (won as a hider), Carl 20 + 100 = 120 (one catch), Anna 20 + 0 = 20.
        game(monday, seeker(carl, catches = 1, won = false), hider(bob, PlayerStatus.ACTIVE, 200))
        game(monday.plusSeconds(60), seeker(anna, catches = 0, won = false))
        // Dan reaches 20 too, later than Anna: below her.
        game(monday.plusSeconds(120), hider(dan, PlayerStatus.CAUGHT, 30))
        // Next week does not count.
        game(monday.plus(Duration.ofDays(7)), seeker(dan, catches = 5, won = true))

        val world = service(now).leaderboard(anna.auth, LeaderboardScope.WORLD)
        assertEquals(monday.toEpochMilli(), world.weekStartMillis)
        assertEquals(monday.plus(Duration.ofDays(7)).toEpochMilli(), world.weekEndMillis)
        assertEquals(listOf(bob.id, carl.id, anna.id, dan.id), world.entries.map { it.userId })
        assertEquals(listOf(150, 120, 20, 20), world.entries.map { it.points })
        assertEquals(listOf(1, 2, 3, 4), world.entries.map { it.rank })
        assertEquals(anna.nickname, world.me?.nickname)
        assertEquals(3, world.me?.rank)
        assertTrue(world.me!!.isMe)
        assertEquals(carl.id, world.nextAbove?.userId)
        assertEquals(1 - 3, world.rankChange, "Anna was first last week, third now")

        val carlsWorld = service(now).leaderboard(carl.auth, LeaderboardScope.WORLD)
        assertEquals(2 - 2, carlsWorld.rankChange)
        val bobsWorld = service(now).leaderboard(bob.auth, LeaderboardScope.WORLD)
        assertNull(bobsWorld.nextAbove, "nobody above the first")
        assertNull(bobsWorld.rankChange, "Bob played no game last week")

        // A banned player is left out.
        jdbc.sql(
            "INSERT INTO sanctions (user_id, kind, reason, created_by, created_at) VALUES (:u, 'BAN', 'x', 'mod', :t)",
        ).param("u", bob.id.value).param("t", now.toTimestamptz()).update()
        val afterBan = service(now).leaderboard(anna.auth, LeaderboardScope.WORLD)
        assertEquals(listOf(carl.id, anna.id, dan.id), afterBan.entries.map { it.userId })
        assertEquals(2, afterBan.me?.rank)
    }

    @Test
    fun theWorldListsTheTopAndTheCallerBelowIt() {
        val monday = Instant.parse("2042-01-05T22:00:00Z")
        val top = (1..LeaderboardRules.WORLD_TOP + 1).map { testUsers.create("top") }
        top.forEachIndexed { i, user -> game(monday.plusSeconds(i.toLong()), seeker(user, catches = 3, won = false)) }
        val me = testUsers.create("me")
        game(monday.plusSeconds(100), hider(me, PlayerStatus.CAUGHT, 0))

        val world = service(monday.plusSeconds(3600)).leaderboard(me.auth, LeaderboardScope.WORLD)
        assertEquals(LeaderboardRules.WORLD_TOP, world.entries.size)
        assertTrue(world.entries.none { it.isMe })
        assertEquals(LeaderboardRules.WORLD_TOP + 2, world.me?.rank)
        assertEquals(20, world.me?.points)
        assertEquals(top.last().id, world.nextAbove?.userId, "the one right above, outside the top too")
        assertEquals(320, world.nextAbove?.points)

        val nobody = testUsers.create("nobody")
        val empty = service(monday.plusSeconds(3600)).leaderboard(nobody.auth, LeaderboardScope.WORLD)
        assertNull(empty.me, "no games this week")
        assertNull(empty.nextAbove)
        assertNull(empty.rankChange)
    }

    @Test
    fun friendsAreTheCallerAndTheirFriendsWithoutTheBlocked() {
        val monday = Instant.parse("2043-01-04T22:00:00Z")
        val anna = testUsers.create("anna")
        val bob = testUsers.create("bob")
        val carl = testUsers.create("carl")
        val stranger = testUsers.create("stranger")
        befriend(anna, bob)
        befriend(anna, carl)
        game(monday.minusSeconds(60), seeker(bob, catches = 1, won = false), hider(anna, PlayerStatus.CAUGHT, 0))
        game(monday.plusSeconds(60), seeker(anna, catches = 1, won = true), hider(bob, PlayerStatus.CAUGHT, 125))
        game(monday.plusSeconds(120), seeker(stranger, catches = 9, won = true), hider(carl, PlayerStatus.ACTIVE, 600))

        val friends = service(monday.plusSeconds(3600)).leaderboard(anna.auth, LeaderboardScope.FRIENDS)
        // Carl 20 + 100 + 100 = 220, Anna 20 + 100 + 50 = 170, Bob 20 + 20 = 40; the stranger is not a friend.
        assertEquals(listOf(carl.id, anna.id, bob.id), friends.entries.map { it.userId })
        assertEquals(listOf(220, 170, 40), friends.entries.map { it.points })
        assertEquals(carl.id, friends.nextAbove?.userId)
        assertEquals(0, friends.rankChange, "second last week (Bob 120, Anna 20), second now")

        jdbc.sql("INSERT INTO blocks VALUES (:a, :b, :t)")
            .param("a", carl.id.value).param("b", anna.id.value).param("t", monday.toTimestamptz()).update()
        val afterBlock = service(monday.plusSeconds(3600)).leaderboard(anna.auth, LeaderboardScope.FRIENDS)
        assertEquals(listOf(anna.id, bob.id), afterBlock.entries.map { it.userId })
        assertNull(afterBlock.nextAbove)
    }

    @Test
    fun theCityRanksThePlayersWhoPickedTheCallersCity() {
        val monday = Instant.parse("2045-01-01T22:00:00Z")
        val anna = testUsers.create("anna")
        val bob = testUsers.create("bob")
        val carl = testUsers.create("carl")
        val nowhere = testUsers.create("nowhere")
        city(anna, "kyiv")
        city(bob, "kyiv")
        city(carl, "lviv")
        game(monday.minusSeconds(60), seeker(anna, catches = 2, won = false), hider(bob, PlayerStatus.CAUGHT, 0))
        game(
            monday.plusSeconds(60),
            seeker(carl, catches = 2, won = true),
            hider(bob, PlayerStatus.ACTIVE, 300),
            hider(anna, PlayerStatus.CAUGHT, 60),
            hider(nowhere, PlayerStatus.CAUGHT, 600),
        )
        val now = monday.plusSeconds(3600)

        val kyiv = service(now).leaderboard(anna.auth, LeaderboardScope.CITY)
        assertEquals("kyiv", kyiv.city)
        // Bob 20 + 50 + 100 = 170, Anna 20 + 10 = 30; Carl plays in Lviv, the player without a city nowhere.
        assertEquals(listOf(bob.id, anna.id), kyiv.entries.map { it.userId })
        assertEquals(listOf(170, 30), kyiv.entries.map { it.points })
        assertEquals(bob.id, kyiv.nextAbove?.userId)
        assertEquals(-1, kyiv.rankChange, "first last week (Anna 220, Bob 20), second now")

        val lviv = service(now).leaderboard(carl.auth, LeaderboardScope.CITY)
        assertEquals(listOf(carl.id), lviv.entries.map { it.userId })

        val none = service(now).leaderboard(nowhere.auth, LeaderboardScope.CITY)
        assertNull(none.city)
        assertEquals(emptyList(), none.entries)
        assertNull(none.me)
        assertEquals(LeaderboardService.weekOf(now).startMillis, none.weekStartMillis)

        city(bob, null)
        val afterBobLeft = service(now).leaderboard(anna.auth, LeaderboardScope.CITY)
        assertEquals(listOf(anna.id), afterBobLeft.entries.map { it.userId }, "today's city counts")
    }

    @Test
    fun thePlayerPicksAKnownCityOrNone() {
        val anna = testUsers.create("anna")
        val kyiv = post(ApiRoutes.ME_CITY, anna.token, CityRequest("kyiv"))
        assertEquals(200, kyiv.first, kyiv.second)
        assertEquals("kyiv", protocolJson.decodeFromString<UserProfile>(kyiv.second).city)
        val me = mvc.get(ApiRoutes.ME) { header("Authorization", "Bearer ${anna.token}") }.andReturn().response
        assertEquals("kyiv", protocolJson.decodeFromString<UserProfile>(me.contentAsString).city)

        val unknown = post(ApiRoutes.ME_CITY, anna.token, CityRequest("atlantis"))
        assertEquals(400, unknown.first)
        assertEquals(ErrorCode.BAD_REQUEST, protocolJson.decodeFromString<ApiError>(unknown.second).code)

        val cleared = post(ApiRoutes.ME_CITY, anna.token, CityRequest(null))
        assertEquals(200, cleared.first, cleared.second)
        assertNull(protocolJson.decodeFromString<UserProfile>(cleared.second).city)
        assertEquals(401, post(ApiRoutes.ME_CITY, token = null, CityRequest("kyiv")).first)
    }

    @Test
    fun theLastGameRanksItsAccountPlayersByThatGameOnly() {
        val anna = testUsers.create("anna")
        val bob = testUsers.create("bob")
        val carl = testUsers.create("carl")
        val blocked = testUsers.create("blocked")
        val now = Instant.parse("2044-03-10T12:00:00Z")
        assertEquals(emptyList(), service(now).leaderboard(anna.auth, LeaderboardScope.LAST_GAME).entries)

        game(
            now.minus(Duration.ofDays(20)),
            seeker(bob, catches = 9, won = true),
            hider(anna, PlayerStatus.ACTIVE, 900),
        )
        val lastEnd = now.minus(Duration.ofDays(10))
        game(
            lastEnd,
            seeker(anna, catches = 1, won = false),
            hider(bob, PlayerStatus.CAUGHT, 60),
            hider(carl, PlayerStatus.ACTIVE, 300),
            hider(blocked, PlayerStatus.ACTIVE, 300),
        )
        jdbc.sql("INSERT INTO blocks VALUES (:a, :b, :t)")
            .param("a", anna.id.value).param("b", blocked.id.value).param("t", now.toTimestamptz()).update()

        val last = service(now).leaderboard(anna.auth, LeaderboardScope.LAST_GAME)
        assertEquals(LeaderboardScope.LAST_GAME, last.scope)
        assertEquals(LeaderboardService.weekOf(lastEnd).startMillis, last.weekStartMillis)
        // Carl 20 + 50 + 100 = 170, Anna 20 + 100 = 120, Bob 20 + 10 = 30; the older game does not count.
        assertEquals(listOf(carl.id, anna.id, bob.id), last.entries.map { it.userId })
        assertEquals(listOf(170, 120, 30), last.entries.map { it.points })
        assertEquals(carl.id, last.nextAbove?.userId)
        assertNull(last.rankChange)
    }

    @Test
    fun theRouteNeedsAnAccountAndAKnownScope() {
        val anna = testUsers.create("anna")
        game(clock.instant(), seeker(anna, catches = 2, won = true))

        val world = get(ApiRoutes.ME_LEADERBOARD, anna.token)
        assertEquals(200, world.first, world.second)
        val response = protocolJson.decodeFromString<LeaderboardResponse>(world.second)
        assertEquals(LeaderboardScope.WORLD, response.scope)
        assertEquals(270, response.me?.points)

        val last = get(ApiRoutes.meLeaderboard(LeaderboardScope.LAST_GAME), anna.token)
        val lastEntries = protocolJson.decodeFromString<LeaderboardResponse>(last.second).entries
        assertEquals(listOf(anna.id), lastEntries.map { it.userId })

        val unknown = get("${ApiRoutes.ME_LEADERBOARD}?scope=TOWN", anna.token)
        assertEquals(400, unknown.first)
        assertEquals(ErrorCode.BAD_REQUEST, protocolJson.decodeFromString<ApiError>(unknown.second).code)
        assertEquals(401, get(ApiRoutes.ME_LEADERBOARD, token = null).first)
    }

    private fun service(now: Instant) = LeaderboardService(repository, Clock.fixed(now, ZoneOffset.UTC))

    private fun get(path: String, token: String?): Pair<Int, String> {
        val result = mvc.get(path) { token?.let { header("Authorization", "Bearer $it") } }.andReturn().response
        return result.status to result.contentAsString
    }

    private fun post(path: String, token: String?, body: CityRequest): Pair<Int, String> {
        val result = mvc.post(path) {
            token?.let { header("Authorization", "Bearer $it") }
            contentType = MediaType.APPLICATION_JSON
            content = protocolJson.encodeToString(body)
        }.andReturn().response
        return result.status to result.contentAsString
    }

    private fun city(user: TestUser, city: String?) {
        jdbc.sql("UPDATE users SET city = :c WHERE id = :u").param("c", city).param("u", user.id.value).update()
    }

    private fun befriend(a: TestUser, b: TestUser) {
        jdbc.sql("INSERT INTO friendships VALUES (:a, :b, :t), (:b, :a, :t)")
            .param("a", a.id.value).param("b", b.id.value).param("t", clock.instant().toTimestamptz()).update()
    }

    private data class Result(
        val user: TestUser,
        val role: Role,
        val status: PlayerStatus,
        val won: Boolean,
        val catches: Int,
        val survivedSeconds: Int?,
    )

    private fun seeker(user: TestUser, catches: Int, won: Boolean) =
        Result(user, Role.SEEKER, PlayerStatus.ACTIVE, won, catches, null)

    private fun hider(user: TestUser, status: PlayerStatus, survivedSeconds: Int) =
        Result(user, Role.HIDER, status, status == PlayerStatus.ACTIVE, 0, survivedSeconds)

    /** A game that ended at [finishedAt] with these account players. */
    private fun game(finishedAt: Instant, vararg results: Result) {
        val gameId = uniqueName("game")
        val started = finishedAt.minus(Duration.ofMinutes(20)).toTimestamptz()
        jdbc.sql(
            """
            INSERT INTO played_games (id, created_at, started_at, finished_at, players, guests, seekers, hiders_caught,
                                      hiders_eliminated, catch_claims, catches, disputes, chat_messages, buildings,
                                      zone_radius_meters, zone_stages, hiding_seconds, seeking_seconds)
            VALUES (:id, :s, :s, :f, :n, 0, 1, 0, 0, 0, 0, 0, 0, 'ALL', 300, 1, 60, 600)
            """.trimIndent(),
        ).param("id", gameId).param("s", started).param("f", finishedAt.toTimestamptz()).param("n", results.size)
            .update()
        for (r in results) {
            jdbc.sql(
                """
                INSERT INTO game_results (user_id, game_id, started_at, finished_at, role, status, won, players,
                                          seekers, catch_claims, catches, survived_seconds, zone_warnings,
                                          building_warnings, fixes, distance_meters, moving_seconds)
                VALUES (:u, :g, :s, :f, :role, :status, :won, :n, 1, :c, :c, :survived, 0, 0, 0, 0, 0)
                """.trimIndent(),
            )
                .param("u", r.user.id.value)
                .param("g", gameId)
                .param("s", started)
                .param("f", finishedAt.toTimestamptz())
                .param("role", r.role.name)
                .param("status", r.status.name)
                .param("won", r.won)
                .param("n", results.size)
                .param("c", r.catches)
                .param("survived", r.survivedSeconds)
                .update()
        }
    }
}
