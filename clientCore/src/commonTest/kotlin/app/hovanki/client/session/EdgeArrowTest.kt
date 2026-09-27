package app.hovanki.client.session

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EdgeArrowTest {
    // A 400 × 800 screen; the HUD keeps 200 at the top, the controls 100 at the bottom, 20 on the sides.
    private fun arrow(angle: Float) =
        edgeArrow(left = 20f, top = 200f, right = 380f, bottom = 700f, angleDegrees = angle)

    private fun assertAt(x: Float, y: Float, arrow: EdgeArrow) {
        assertTrue(abs(arrow.x - x) < 0.01f && abs(arrow.y - y) < 0.01f, "expected ($x, $y), was $arrow")
    }

    @Test
    fun straightUpDownAndSideways() {
        assertAt(200f, 200f, arrow(0f))
        assertAt(380f, 450f, arrow(90f))
        assertAt(200f, 700f, arrow(180f))
        assertAt(20f, 450f, arrow(270f))
        assertAt(20f, 450f, arrow(-90f))
        assertEquals(270f, arrow(-90f).angleDegrees)
    }

    @Test
    fun diagonalsLeaveThroughTheNearerSide() {
        // 45°: the ray from (200, 450) meets x = 380 after 180, long before the top (250 away).
        assertAt(380f, 270f, arrow(45f))
        // Nearly straight down: the bottom comes first.
        val down = arrow(170f)
        assertEquals(700f, down.y, 0.01f)
        assertTrue(down.x in 200f..380f)
    }
}
