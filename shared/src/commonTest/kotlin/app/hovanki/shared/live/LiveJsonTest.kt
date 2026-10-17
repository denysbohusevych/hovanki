package app.hovanki.shared.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class LiveJsonTest {
    @Test
    fun theSessionsOfAGameShareTheBytesOfItsSettings() {
        val game = LiveJson.Game()
        val settings = LiveFixtures.settings
        val first = LiveJson(game).settings.encode(settings)
        // Another session of the game, an equal value: the same bytes, not encoded again.
        assertSame(first, LiveJson(game).settings.encode(settings.copy()))
        assertNotSame(first, LiveJson().settings.encode(settings), "another game keeps its own")
        assertEquals(settings, LiveJson().settings.decode(first))
    }

    @Test
    fun aChangedValueIsEncodedAgain() {
        val codec = LiveJson().settings
        val before = codec.encode(LiveFixtures.settings)
        val changed = LiveFixtures.settings.copy(hidingSeconds = LiveFixtures.settings.hidingSeconds + 60)
        val after = codec.encode(changed)
        assertEquals(changed, LiveJson().settings.decode(after))
        assertEquals(LiveFixtures.settings, LiveJson().settings.decode(before))
    }
}
