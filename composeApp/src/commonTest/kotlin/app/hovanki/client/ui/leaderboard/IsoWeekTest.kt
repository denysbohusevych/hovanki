package app.hovanki.client.ui.leaderboard

import kotlin.test.Test
import kotlin.test.assertEquals

class IsoWeekTest {
    @Test
    fun aMondayInKyivSummerIsItsWeek() {
        // Monday 2026-09-21 00:00 in Kyiv (UTC+3) is 2026-09-20T21:00Z.
        assertEquals(39, isoWeek(1_789_938_000_000L))
    }

    @Test
    fun theFirstWeekCanStartInDecember() {
        // Monday 2025-12-29 00:00 in Kyiv (UTC+2) is 2025-12-28T22:00Z: week 1 of 2026, its Thursday is January 1st.
        assertEquals(1, isoWeek(1_766_959_200_000L))
    }

    @Test
    fun aYearCanHaveFiftyThreeWeeks() {
        // Monday 2026-12-28 00:00 in Kyiv is 2026-12-27T22:00Z: 2026 starts on a Thursday, so it has 53 weeks.
        assertEquals(53, isoWeek(1_798_408_800_000L))
    }
}
