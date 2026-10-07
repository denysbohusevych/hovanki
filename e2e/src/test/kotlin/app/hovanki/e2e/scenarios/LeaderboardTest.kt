package app.hovanki.e2e.scenarios

import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.LeaderboardResponse
import app.hovanki.shared.protocol.LeaderboardScope
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.rules.LeaderboardRules
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The leaderboard, «Рейтинг» (docs/adr/0020-leaderboard.md), after a whole game: every player with an account gets
 * the points of [LeaderboardRules] for it, in the world's week, among their friends, in the city they picked
 * (docs/adr/0022-city-leaderboard.md) and in the last game. The world is
 * shared with the other scenarios on the same server, so only the players' own lines are checked there.
 */
class LeaderboardTest {
    @Test
    fun pointsOfAFinishedGameInEveryScope() = scenario("The leaderboard after a game") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -20.0))
        val guest = player("Guest", at = PARK.offset(northMeters = 10.0))
        sam.signsUp()
        anna.signsUp()
        boris.signsUp()
        val samId = checkNotNull(sam.userId)
        val annaId = checkNotNull(anna.userId)
        val borisId = checkNotNull(boris.userId)

        requireOk(anna.openLeaderboard(LeaderboardScope.LAST_GAME), "Anna opens the leaderboard before any game")
        check(anna.leaderboard?.entries.orEmpty().isEmpty(), "no last game yet")

        sam.createsGame(GameSetups.fast())
        join(anna, boris, guest)
        sam.startsGame(seekers = listOf(sam))
        anna.walksTo(PARK.offset(eastMeters = 60.0), speed = 4.0)
        boris.walksTo(PARK.offset(eastMeters = -60.0), speed = 4.0)
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        anna.arrives()
        boris.arrives()
        sam.catches(guest)
        sam.catches(anna)
        sam.catches(boris)
        awaitPhase(GamePhase.FINISHED)

        // What each account's game is worth by the rule, from their own history.
        val expected = mutableMapOf<UserId, Int>()
        for ((bot, id) in listOf(sam to samId, anna to annaId, boris to borisId)) {
            val game = eventually("${bot.name} finds the game in their history", within = 10.seconds) {
                bot.refreshHistory()
                bot.history.games.firstOrNull { it.gameId == gameId }
            }
            expected[id] = LeaderboardRules.points(game.role, game.status, game.won, game.catches, game.survivedSeconds)
        }
        check(
            expected[samId] == 370,
            "Sam found all three: 20 + 3 × 100 + 50 (the guest without an account counts too)",
        )

        // The world: everybody's week; the scenarios before this one played on the same server.
        for ((bot, id) in listOf(sam to samId, anna to annaId, boris to borisId)) {
            requireOk(bot.openLeaderboard(LeaderboardScope.WORLD), "${bot.name} opens the world")
            val world = checkNotNull(bot.leaderboard)
            val me = checkNotNull(world.me) { "${bot.name} is ranked" }
            check(me.userId == id && me.isMe && me.points == expected[id], "${bot.name}: ${me.points} points")
            check(world.weekStartMillis < world.weekEndMillis, "a week")
            check(world.entries.size <= LeaderboardRules.WORLD_TOP, "the top only")
            check(world.entries.zipWithNext().all { (a, b) -> a.points >= b.points }, "best first")
            world.nextAbove?.let { check(it.rank == me.rank - 1 && it.points >= me.points, "the one right above") }
            check(world.rankChange == null, "a new account was not ranked last week")
        }

        // Friends: Anna and Sam become friends; Boris has none.
        requireOk(anna.sendFriendRequest(samId), "Anna asks Sam to be friends")
        requireOk(sam.acceptFriendRequest(annaId), "Sam accepts")
        requireOk(anna.openLeaderboard(LeaderboardScope.FRIENDS), "Anna opens her friends' leaderboard")
        val friends = checkNotNull(anna.leaderboard)
        check(friends.entries.map { it.userId } == listOf(samId, annaId), "Sam above Anna, nobody else")
        check(friends.points() == expected.filterKeys { it != borisId }, "the rule's points")
        check(friends.me?.userId == annaId && friends.nextAbove?.userId == samId, "Anna sees Sam right above")
        requireOk(boris.openLeaderboard(LeaderboardScope.FRIENDS), "Boris opens his friends' leaderboard")
        check(boris.leaderboard?.entries?.map { it.userId } == listOf(borisId), "only Boris himself")

        // The city (docs/adr/0022-city-leaderboard.md): the players' own pick; only this scenario picks cities.
        requireOk(boris.openLeaderboard(LeaderboardScope.CITY), "Boris opens the city before picking one")
        check(boris.leaderboard?.city == null && boris.leaderboard?.entries.orEmpty().isEmpty(), "no city, nobody")
        requireOk(sam.setCity("uzhhorod"), "Sam picks Uzhhorod")
        requireOk(anna.setCity("uzhhorod"), "Anna picks Uzhhorod")
        requireOk(boris.setCity("lviv"), "Boris picks Lviv")
        requireOk(anna.openLeaderboard(LeaderboardScope.CITY), "Anna opens her city")
        val uzhhorod = checkNotNull(anna.leaderboard)
        check(uzhhorod.city == "uzhhorod", "Anna's city")
        check(uzhhorod.entries.map { it.userId } == listOf(samId, annaId), "Sam above Anna, Boris is in Lviv")
        check(uzhhorod.points() == expected.filterKeys { it != borisId }, "the rule's points")
        requireOk(boris.openLeaderboard(LeaderboardScope.CITY), "Boris opens his city")
        check(boris.leaderboard?.entries?.map { it.userId } == listOf(borisId), "only Boris in Lviv")
        requireOk(anna.setCity(null), "Anna forgets her city")
        requireOk(sam.openLeaderboard(LeaderboardScope.CITY), "Sam opens his city")
        check(sam.leaderboard?.entries?.map { it.userId } == listOf(samId), "Anna left Uzhhorod's leaderboard")

        // The last game: its account players, the guest left out.
        requireOk(boris.openLeaderboard(LeaderboardScope.LAST_GAME), "Boris opens the last game")
        val last = checkNotNull(boris.leaderboard)
        check(last.scope == LeaderboardScope.LAST_GAME, "the last game")
        check(last.points() == expected, "the three accounts with the rule's points: ${last.points()}")
        check(last.entries.first().userId == samId, "Sam is first")
        check(last.entries.map { it.rank } == listOf(1, 2, 3), "places 1 to 3")
        check(last.me?.userId == borisId && last.rankChange == null, "Boris' own line, no change")
    }

    private fun LeaderboardResponse.points(): Map<UserId, Int> = entries.associate { it.userId to it.points }
}
