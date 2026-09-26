package app.hovanki.server.buildings

import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ZoneCircle
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BuildingLoaderTest {
    private val area = ZoneCircle(GeoPoint(50.0, 30.0), 550.0)

    /** Inline (the fake source type) and without pauses: the result is there when load returns. */
    private fun loader(attempts: Int, source: BuildingSource) = BuildingLoader(
        source,
        BuildingProperties(source = BuildingProperties.Source.FAKE, attempts = attempts, retryDelay = Duration.ZERO),
    )

    private fun load(loader: BuildingLoader): Buildings? {
        var result: Buildings? = null
        loader.load("g", area) { result = it }
        return result
    }

    @Test
    fun triesAgainAfterAFailure() {
        var calls = 0
        val flaky = BuildingSource {
            if (++calls == 1) throw BuildingsUnavailableException("busy") else Buildings(emptyList())
        }

        assertNotNull(load(loader(attempts = 2, flaky)))
        assertEquals(2, calls)
    }

    @Test
    fun givesUpAfterTheLastAttempt() {
        var calls = 0
        val down = BuildingSource {
            calls++
            throw BuildingsUnavailableException("down")
        }

        assertNull(load(loader(attempts = 3, down)))
        assertEquals(3, calls)
    }

    @Test
    fun doesNotRetryWhatCannotChange() {
        var calls = 0
        val tooMany = BuildingSource {
            calls++
            throw BuildingsUnavailableException("More than 10000 buildings", retry = false)
        }

        assertNull(load(loader(attempts = 3, tooMany)))
        assertEquals(1, calls)
    }
}
