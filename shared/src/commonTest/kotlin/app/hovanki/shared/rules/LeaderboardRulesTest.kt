package app.hovanki.shared.rules

import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.rules.LeaderboardRules.Score
import kotlin.test.Test
import kotlin.test.assertEquals

class LeaderboardRulesTest {
    @Test
    fun aHiderGetsMinutesAndTheBonusWhenNeverFound() {
        // 20 for the game + 7 full minutes × 10 + 100 never found.
        assertEquals(190, LeaderboardRules.points(Role.HIDER, PlayerStatus.ACTIVE, true, 0, 7 * 60 + 59))
        // Found after 3 minutes and a half: no bonus.
        assertEquals(50, LeaderboardRules.points(Role.HIDER, PlayerStatus.CAUGHT, false, 0, 210))
        assertEquals(20, LeaderboardRules.points(Role.HIDER, PlayerStatus.ELIMINATED, false, 0, 59))
        assertEquals(20, LeaderboardRules.points(Role.HIDER, PlayerStatus.CAUGHT, false, 0, null))
        // Still active at the end counts as never found even if `won` were false.
        assertEquals(120, LeaderboardRules.points(Role.HIDER, PlayerStatus.ACTIVE, false, 0, 0))
    }

    @Test
    fun aSeekerGetsCatchesAndTheWin() {
        assertEquals(370, LeaderboardRules.points(Role.SEEKER, PlayerStatus.ACTIVE, true, 3, null))
        assertEquals(120, LeaderboardRules.points(Role.SEEKER, PlayerStatus.ACTIVE, false, 1, null))
        assertEquals(20, LeaderboardRules.points(Role.SEEKER, PlayerStatus.ACTIVE, false, 0, 600))
    }

    @Test
    fun rankByPointsThenTheEarlierLastGameThenTheNickname() {
        val scores = listOf(
            Score(UserId("u1"), "zoe", 100, lastGameAtMillis = 5),
            Score(UserId("u2"), "Bob", 300, lastGameAtMillis = 9),
            Score(UserId("u3"), "anna", 100, lastGameAtMillis = 5),
            Score(UserId("u4"), "Carl", 100, lastGameAtMillis = 1),
        )
        val ranked = LeaderboardRules.rank(scores, me = UserId("u1"))
        assertEquals(listOf("Bob", "Carl", "anna", "zoe"), ranked.map { it.nickname })
        assertEquals(listOf(1, 2, 3, 4), ranked.map { it.rank })
        assertEquals(listOf(false, false, false, true), ranked.map { it.isMe })
    }
}
