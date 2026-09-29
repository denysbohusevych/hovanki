package app.hovanki.client.session

import app.hovanki.client.network.ApiResult
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.SettingsPreviewResponse
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.rules.shrinkingZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The zone by streets of the host's draft (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 2.3): asked
 * while the host chooses, so the map shows the blocks before «Save».
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftZoneTest {
    private val center = GeoPoint(50.45, 30.52)
    private val circle = GameSettings(zone = shrinkingZone(center, initialRadiusMeters = 300.0, steps = 0))
    private val streets = circle.copy(zoneShape = ZoneShape.STREETS)
    private val block = ZonePolygon(
        listOf(
            GeoPoint(50.448, 30.518),
            GeoPoint(50.448, 30.522),
            GeoPoint(50.452, 30.522),
            GeoPoint(50.452, 30.518),
            GeoPoint(50.448, 30.518),
        ),
    )
    private val ready = SettingsPreviewResponse(StreetZoneState.READY, listOf(block))

    private val asked = mutableListOf<GameSettings>()

    private fun TestScope.preview(vararg answers: ApiResult<SettingsPreviewResponse>): DraftZonePreview {
        val queue = ArrayDeque(answers.toList())
        return DraftZonePreview(backgroundScope) { draft ->
            asked += draft
            queue.removeFirstOrNull() ?: ApiResult.Success(ready)
        }
    }

    private fun GameSettings.radius(meters: Double) =
        copy(zone = shrinkingZone(center, initialRadiusMeters = meters, steps = 0))

    @Test
    fun aTapOnByStreetsShowsTheBlocksRightAway() = runTest {
        val preview = preview()
        preview.show(circle, saved = circle)
        assertNull(preview.zone.value)

        preview.show(streets, saved = circle)
        assertEquals(DraftZoneState.LOADING, preview.zone.value?.state)
        runCurrent()

        val zone = assertNotNull(preview.zone.value)
        assertEquals(DraftZoneState.READY, zone.state)
        assertEquals(streets.zone, zone.zone)
        assertEquals(listOf(block), zone.streets?.stages)
        assertEquals(listOf(streets), asked)
    }

    @Test
    fun aSliderIsAskedOnceTheHostStops() = runTest {
        val preview = preview()
        preview.show(streets, saved = streets)
        for (meters in listOf(350.0, 400.0, 450.0, 500.0)) {
            preview.show(streets.radius(meters), saved = streets)
            advanceTimeBy(DraftZonePreview.SETTLE_MILLIS / 2)
        }
        assertEquals(emptyList(), asked)
        assertEquals(DraftZoneState.LOADING, preview.zone.value?.state)

        advanceTimeBy(DraftZonePreview.SETTLE_MILLIS)

        assertEquals(listOf(streets.radius(500.0)), asked)
        assertEquals(DraftZoneState.READY, preview.zone.value?.state)
        assertEquals(streets.radius(500.0).zone, preview.zone.value?.zone)
    }

    @Test
    fun whileTheServerBuildsItIsAskedAgain() = runTest {
        val loading = ApiResult.Success(SettingsPreviewResponse(StreetZoneState.LOADING))
        val preview = preview(loading, ApiResult.Network("offline"), loading)
        preview.show(circle, saved = circle)
        preview.show(streets, saved = circle)
        runCurrent()
        assertEquals(DraftZoneState.LOADING, preview.zone.value?.state)

        advanceTimeBy(DraftZonePreview.POLL_MILLIS * 2 + 1)
        assertEquals(DraftZoneState.LOADING, preview.zone.value?.state)
        advanceTimeBy(DraftZonePreview.POLL_MILLIS)

        assertEquals(DraftZoneState.READY, preview.zone.value?.state)
        assertEquals(4, asked.size)
    }

    @Test
    fun noStreetsHereOrARefusalIsSaidOnce() = runTest {
        val preview = preview(
            ApiResult.Success(SettingsPreviewResponse(StreetZoneState.UNAVAILABLE)),
            ApiResult.Rejected(
                ErrorCode.BAD_REQUEST,
                ErrorReason.TOO_MANY_REQUESTS,
                "Too many",
                retryAfterSeconds = 60,
            ),
        )
        preview.show(circle, saved = circle)
        preview.show(streets, saved = circle)
        runCurrent()
        assertEquals(DraftZoneState.NO_STREETS, preview.zone.value?.state)

        preview.show(streets.radius(400.0), saved = circle)
        advanceTimeBy(DraftZonePreview.SETTLE_MILLIS * 10)

        assertEquals(DraftZoneState.UNKNOWN, preview.zone.value?.state)
        assertEquals(2, asked.size)
    }

    @Test
    fun theGamesOwnZoneAndCirclesAskNothing() = runTest {
        val preview = preview()
        preview.show(streets, saved = streets)
        preview.show(circle.radius(600.0), saved = streets)
        advanceTimeBy(DraftZonePreview.SETTLE_MILLIS * 10)

        assertNull(preview.zone.value)
        assertEquals(emptyList(), asked)
    }

    @Test
    fun backToABuiltZoneItShowsAtOnce() = runTest {
        val preview = preview()
        preview.show(circle, saved = circle)
        preview.show(streets, saved = circle)
        runCurrent()
        preview.show(circle, saved = circle)
        assertNull(preview.zone.value)

        preview.show(streets, saved = circle)

        assertEquals(DraftZoneState.READY, preview.zone.value?.state)
        assertEquals(1, asked.size)
        preview.show(null, saved = circle)
        assertNull(preview.zone.value)
    }
}
