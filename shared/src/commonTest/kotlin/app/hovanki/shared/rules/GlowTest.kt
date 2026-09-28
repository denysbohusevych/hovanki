package app.hovanki.shared.rules

import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlowTest {
    private val start = 1_000_000L
    private val settings = GameSettings(
        zone = shrinkingZone(GeoPoint(50.45, 30.52), steps = 0),
        seekingSeconds = 600,
        glowEverySeconds = 120,
        glowForSeconds = 5,
    )

    @Test
    fun theFirstGlowAFullIntervalIntoTheSearch() {
        assertNull(Glow.lastStarted(settings, start, start + 119_999))
        assertEquals(
            GlowWindow(1, start + 120_000, start + 125_000),
            Glow.lastStarted(settings, start, start + 120_000),
        )
        assertEquals(GlowWindow(1, start + 120_000, start + 125_000), Glow.next(settings, start, start))
    }

    @Test
    fun openOnlyForItsLength() {
        assertTrue(GlowWindow(1, 10, 15).isOpenAt(10))
        assertTrue(GlowWindow(1, 10, 15).isOpenAt(14))
        assertFalse(GlowWindow(1, 10, 15).isOpenAt(15))
        assertEquals(1, Glow.openAt(settings, start, start + 124_000)?.index)
        assertNull(Glow.openAt(settings, start, start + 125_000))
        // Between glows the last one is still known: its marks stay.
        assertEquals(1, Glow.lastStarted(settings, start, start + 200_000)?.index)
    }

    @Test
    fun theNextOneWithinTheSearchOnly() {
        assertEquals(3, Glow.next(settings, start, start + 250_000)?.index)
        // 600 s of search: the glow at 600 s would be after the end.
        assertEquals(4, Glow.next(settings, start, start + 479_000)?.index)
        assertNull(Glow.next(settings, start, start + 480_000))
    }

    @Test
    fun noGlowWhenOff() {
        val off = settings.copy(glowEverySeconds = 0, glowForSeconds = 0)

        assertFalse(Glow.isOn(off))
        assertNull(Glow.lastStarted(off, start, start + 1_000_000))
        assertNull(Glow.next(off, start, start))
    }
}
