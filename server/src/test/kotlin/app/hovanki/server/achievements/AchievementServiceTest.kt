package app.hovanki.server.achievements

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
import app.hovanki.shared.protocol.AchievementsResponse
import app.hovanki.shared.protocol.AchievementsSeenRequest
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.AchievementRules
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AchievementService] on the test database (docs/adr/0021-achievements.md); the results are written straight into
 * `game_results`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountTestConfig::class)
class AchievementServiceTest(
    @Autowired private val repository: AchievementRepository,
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
    fun countsOnlyTheCallersGamesAndTellsWhatIsNew() {
        val anna = testUsers.create("anna")
        val bob = testUsers.create("bob")
        val monday = Instant.parse("2041-01-06T22:00:00Z")
        game(anna, monday.plus(Duration.ofDays(1)), Role.SEEKER, PlayerStatus.ACTIVE, won = true, catches = 3)
        game(anna, monday.plus(Duration.ofDays(2)), Role.HIDER, PlayerStatus.ACTIVE, won = true, survived = 1300)
        game(bob, monday.plus(Duration.ofDays(2)), Role.SEEKER, PlayerStatus.ACTIVE, won = false, catches = 9)

        val now = monday.plus(Duration.ofDays(3))
        val first = service(now).achievements(anna.auth)
        assertNull(first.seenAtMillis)
        val byId = first.achievements.associateBy { it.id }
        assertEquals(AchievementRules.ALL.map { it.id }, first.achievements.map { it.id })
        assertEquals(2, byId.getValue(AchievementRules.GAMES).value)
        assertEquals(3, byId.getValue(AchievementRules.CATCHES).value, "Bob's catches are not Anna's")
        assertEquals(1, byId.getValue(AchievementRules.ROUNDUP).level)
        assertEquals(1, byId.getValue(AchievementRules.PATIENCE).level)
        assertEquals(1, byId.getValue(AchievementRules.WEEKLY).value)
        assertTrue(byId.getValue(AchievementRules.ROUNDUP).isNew)

        // Seen up to the first game: the patience of the second one is still new.
        val seen = service(now).markSeen(anna.auth, monday.plus(Duration.ofDays(1)).toEpochMilli())
        val afterSeen = seen.achievements.associateBy { it.id }
        assertFalse(afterSeen.getValue(AchievementRules.ROUNDUP).isNew)
        assertTrue(afterSeen.getValue(AchievementRules.PATIENCE).isNew)
        // Never back, never ahead of the server's clock.
        service(now).markSeen(anna.auth, 0)
        assertEquals(monday.plus(Duration.ofDays(1)), repository.seenAt(anna.id))
        service(now).markSeen(anna.auth, Long.MAX_VALUE)
        assertEquals(now, repository.seenAt(anna.id))
        assertTrue(service(now).achievements(anna.auth).achievements.none { it.isNew })
    }

    @Test
    fun weeksInARowByKyivsMondays() {
        val anna = testUsers.create("anna")
        // Sunday 23:30 and Monday 00:30 in Kyiv (UTC+2): two weeks in a row, then the next two.
        val sunday = Instant.parse("2041-01-13T21:30:00Z")
        listOf(0L, 1L, 8 * 24L, 15 * 24L).forEach { hours ->
            game(anna, sunday.plus(Duration.ofHours(hours)), Role.SEEKER, PlayerStatus.ACTIVE, won = false)
        }
        val weekly = service(sunday.plus(Duration.ofDays(30))).achievements(anna.auth).achievements
            .single { it.id == AchievementRules.WEEKLY }
        assertEquals(4, weekly.value)
        assertEquals(1, weekly.level)
    }

    @Test
    fun theRoutesNeedAnAccount() {
        val anna = testUsers.create("anna")
        game(anna, clock.instant().minusSeconds(60), Role.SEEKER, PlayerStatus.ACTIVE, won = false, catches = 1)

        val got = mvc.get(ApiRoutes.ME_ACHIEVEMENTS) { header("Authorization", "Bearer ${anna.token}") }
            .andReturn().response
        assertEquals(200, got.status, got.contentAsString)
        val response = protocolJson.decodeFromString<AchievementsResponse>(got.contentAsString)
        assertTrue(response.achievements.single { it.id == AchievementRules.CATCHES }.isNew)

        val seen = mvc.post(ApiRoutes.ME_ACHIEVEMENTS_SEEN) {
            header("Authorization", "Bearer ${anna.token}")
            contentType = MediaType.APPLICATION_JSON
            content = protocolJson.encodeToString(AchievementsSeenRequest(clock.instant().toEpochMilli()))
        }.andReturn().response
        assertEquals(200, seen.status, seen.contentAsString)
        val afterSeen = protocolJson.decodeFromString<AchievementsResponse>(seen.contentAsString)
        assertTrue(afterSeen.achievements.none { it.isNew })

        assertEquals(401, mvc.get(ApiRoutes.ME_ACHIEVEMENTS).andReturn().response.status)
    }

    private fun service(now: Instant) = AchievementService(repository, Clock.fixed(now, ZoneOffset.UTC))

    /** A game of [user] alone that ended at [finishedAt]. */
    private fun game(
        user: TestUser,
        finishedAt: Instant,
        role: Role,
        status: PlayerStatus,
        won: Boolean,
        catches: Int = 0,
        survived: Int? = if (role == Role.HIDER) 60 else null,
    ) {
        val gameId = uniqueName("game")
        val started = finishedAt.minus(Duration.ofMinutes(25)).toTimestamptz()
        jdbc.sql(
            """
            INSERT INTO played_games (id, created_at, started_at, finished_at, players, guests, seekers, hiders_caught,
                                      hiders_eliminated, catch_claims, catches, disputes, chat_messages, buildings,
                                      zone_radius_meters, zone_stages, hiding_seconds, seeking_seconds)
            VALUES (:id, :s, :s, :f, 1, 0, 1, 0, 0, 0, 0, 0, 0, 'ALL', 300, 1, 60, 600)
            """.trimIndent(),
        ).param("id", gameId).param("s", started).param("f", finishedAt.toTimestamptz()).update()
        jdbc.sql(
            """
            INSERT INTO game_results (user_id, game_id, started_at, finished_at, role, status, won, players,
                                      seekers, catch_claims, catches, survived_seconds, zone_warnings,
                                      building_warnings, fixes, distance_meters, moving_seconds)
            VALUES (:u, :g, :s, :f, :role, :status, :won, 1, 1, :c, :c, :survived, 0, 0, 0, 1500, 0)
            """.trimIndent(),
        )
            .param("u", user.id.value)
            .param("g", gameId)
            .param("s", started)
            .param("f", finishedAt.toTimestamptz())
            .param("role", role.name)
            .param("status", status.name)
            .param("won", won)
            .param("c", catches)
            .param("survived", survived)
            .update()
    }
}
