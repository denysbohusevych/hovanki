package app.hovanki.server.game

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.SettingsPreviewResponse
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.rules.shrinkingZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The zone by streets of the host's draft, built before it is saved
 * (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 2.3): the map shows the blocks while the host
 * chooses, and saving the draft takes them.
 */
class GameSettingsPreviewTest {
    private val center = GeoPoint(50.4501, 30.5234)
    private val circle = GameSettings(zone = shrinkingZone(center, initialRadiusMeters = 300.0, steps = 0))
    private val streets = circle.copy(zoneShape = ZoneShape.STREETS)
    private val host = PlayerId("host")
    private val anna = PlayerId("anna")
    private var now = 1_700_000_000_000L
    private val game = Game(GameId("g"), "ABC234", host, circle, now).apply {
        addPlayer(host, "Host", now)
        addPlayer(anna, "Anna", now)
    }

    private val block = ZonePolygon(
        listOf(
            center.moveBy(-250.0, -250.0),
            center.moveBy(250.0, -250.0),
            center.moveBy(250.0, 250.0),
            center.moveBy(-250.0, 250.0),
            center.moveBy(-250.0, -250.0),
        ),
    )

    private fun GameSettings.radius(meters: Double) =
        copy(zone = shrinkingZone(center, initialRadiusMeters = meters, steps = 0))

    @Test
    fun aDraftByStreetsIsBuiltBeforeSaving() {
        assertNull(game.settingsPreview(host, streets, now), "not asked yet: the service builds it")

        game.startDraftZone(streets.zone, now)
        assertEquals(SettingsPreviewResponse(StreetZoneState.LOADING), game.settingsPreview(host, streets, now))
        assertTrue(game.wantsDraftZone(streets.zone))
        game.onDraftZoneBuilt(streets.zone, listOf(block))

        assertEquals(
            SettingsPreviewResponse(StreetZoneState.READY, listOf(block)),
            game.settingsPreview(host, streets, now),
        )
        assertFalse(game.wantsDraftZone(streets.zone))
        assertNull(game.settingsPreview(host, streets.radius(400.0), now), "another draft is built again")
    }

    @Test
    fun savingTheDraftTakesItsZone() {
        game.startDraftZone(streets.zone, now)
        game.onDraftZoneBuilt(streets.zone, listOf(block))

        assertTrue(game.updateSettings(host, streets, now))

        assertEquals(StreetZoneState.READY, game.streetZoneState, "nothing to build: the blocks are there")
        assertEquals(listOf(block), game.streetZoneFor(anna).stages)
        assertEquals(1, game.streetZoneFor(anna).mapRevision)
    }

    @Test
    fun savingAnotherZoneBuildsItAsBefore() {
        game.startDraftZone(streets.zone, now)
        game.onDraftZoneBuilt(streets.zone, listOf(block))

        game.updateSettings(host, streets.radius(400.0), now)

        assertEquals(StreetZoneState.LOADING, game.streetZoneState)
        assertEquals(emptyList(), game.streetZoneFor(anna).stages)
    }

    @Test
    fun onlyTheLastDraftCounts() {
        val larger = streets.radius(400.0)
        game.startDraftZone(streets.zone, now)
        game.startDraftZone(larger.zone, now)
        assertFalse(game.wantsDraftZone(streets.zone), "the host moved on")

        game.onDraftZoneBuilt(streets.zone, listOf(block))
        assertEquals(StreetZoneState.LOADING, game.settingsPreview(host, larger, now)?.streetZone)

        game.onDraftZoneBuilt(larger.zone, null)
        assertEquals(SettingsPreviewResponse(StreetZoneState.UNAVAILABLE), game.settingsPreview(host, larger, now))
    }

    @Test
    fun aDraftThatNeverComesIsGivenUpOn() {
        game.startDraftZone(streets.zone, now)

        now += Game.DRAFT_ZONE_PATIENCE_MILLIS

        assertEquals(StreetZoneState.UNAVAILABLE, game.settingsPreview(host, streets, now)?.streetZone)
        assertFalse(game.wantsDraftZone(streets.zone))
    }

    @Test
    fun circlesAndTheSavedZoneNeedNothingBuilt() {
        assertEquals(SettingsPreviewResponse(), game.settingsPreview(host, circle.radius(500.0), now))

        game.updateSettings(host, streets, now)
        game.onStreetZoneBuilt(listOf(block))

        assertEquals(
            SettingsPreviewResponse(StreetZoneState.READY, listOf(block)),
            game.settingsPreview(host, streets, now),
        )
    }

    @Test
    fun onlyTheHostInTheLobbyNearWhereTheGameWasMade() {
        val forbidden = assertFailsWith<GameException> { game.settingsPreview(anna, streets, now) }
        assertEquals(ErrorCode.FORBIDDEN, forbidden.code)

        val far = streets.copy(
            zone = shrinkingZone(center.moveBy(10_000.0, 0.0), initialRadiusMeters = 300.0, steps = 0),
        )
        val tooFar = assertFailsWith<GameException> { game.settingsPreview(host, far, now) }
        assertEquals(ErrorReason.ZONE_TOO_FAR, tooFar.reason)

        game.start(host, setOf(host), { "secret" }, now)
        val started = assertFailsWith<GameException> { game.settingsPreview(host, streets, now) }
        assertEquals(ErrorCode.WRONG_STATE, started.code)
    }
}
