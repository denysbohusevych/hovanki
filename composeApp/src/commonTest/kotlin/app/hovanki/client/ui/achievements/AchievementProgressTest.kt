package app.hovanki.client.ui.achievements

import app.hovanki.shared.protocol.AchievementProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AchievementProgressTest {
    private fun progress(value: Long, level: Int, unlockedAt: Long? = null) =
        AchievementProgress("catches", listOf(1, 10, 50), value, level, unlockedAt)

    @Test
    fun theBarRunsFromTheLastLevelToTheNext() {
        assertEquals(0f, fraction(progress(value = 0, level = 0)))
        assertEquals(0f, fraction(progress(value = 1, level = 1)))
        assertEquals(0.5f, fraction(progress(value = 30, level = 2)))
        assertEquals(1f, fraction(progress(value = 70, level = 3)))
    }

    @Test
    fun seenUpToTheNewestShown() {
        assertNull(newestUnlock(listOf(progress(0, 0))))
        assertEquals(9, newestUnlock(listOf(progress(1, 1, unlockedAt = 4), progress(10, 2, unlockedAt = 9))))
    }
}
