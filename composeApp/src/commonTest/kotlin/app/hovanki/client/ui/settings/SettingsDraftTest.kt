package app.hovanki.client.ui.settings

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.BoardItem
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ItemId
import app.hovanki.shared.protocol.ItemKind
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.rules.GameSetup
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsDraftTest {
    private val center = GeoPoint(50.4501, 30.5234)
    private val setup = GameSetup()
    private val current = setup.settings(center)

    private fun kinds(features: GameFeatures) = buildList {
        if (features.quests) add(ItemKind.QUEST_POINT)
        if (features.checkpoints) add(ItemKind.CHECKPOINT_GEO)
    }

    private fun item(kind: ItemKind, east: Double) = BoardItem(ItemId("i$east"), kind, center.moveBy(east, 0.0))

    @Test
    fun theChangedPartsNameWhatTheHostTouched() {
        assertEquals(emptyList(), changedParts(setup, center, setup, center))
        val draft = setup.copy(radiusMeters = 700, zoneShape = ZoneShape.STREETS, hidingMinutes = 8, openGame = true)

        assertEquals(
            listOf(ChangedPart.SIZE, ChangedPart.SHAPE, ChangedPart.PLACE, ChangedPart.TIME, ChangedPart.SPECTATORS),
            changedParts(setup, center, draft, center.moveBy(300.0, 0.0)),
        )
    }

    @Test
    fun timesAndTheGlowNeedNoLookBeforeSaving() {
        val draft = setup.copy(hidingMinutes = 8, glowEveryMinutes = 3).settings(center)

        assertEquals(emptyList(), settingsChanges(current, draft, emptyList(), ::kinds))
    }

    @Test
    fun aNewZoneSaysWhatItTouches() {
        val opened = current.copy(openBuildings = listOf(center.moveBy(100.0, 0.0), center.moveBy(0.0, 480.0)))
        val items = listOf(item(ItemKind.QUEST_POINT, 50.0), item(ItemKind.QUEST_POINT, 450.0))
        val features = GameFeatures(quests = true)
        val draft = setup.copy(radiusMeters = 300, zoneShape = ZoneShape.STREETS, features = features).settings(center)

        assertEquals(
            listOf(
                SettingsChange.ZoneSize(500, 300),
                SettingsChange.ZoneShapeTo(ZoneShape.STREETS),
                SettingsChange.MapReloads,
                SettingsChange.OpenBuildingsKept(1),
                SettingsChange.OpenBuildingsLost(1),
                SettingsChange.BoardOutside(1),
                SettingsChange.EverybodySees,
            ),
            settingsChanges(opened.copy(features = features), draft, items, ::kinds),
        )
    }

    @Test
    fun movingAndTurningOffSayTheirsToo() {
        val items = listOf(item(ItemKind.CHECKPOINT_GEO, 10.0))
        val withCheckpoints = current.copy(features = GameFeatures(checkpoints = true))
        val moved = setup.copy(shrinks = false).settings(center.moveBy(0.0, 1_200.0))

        assertEquals(
            listOf(
                SettingsChange.ZoneMoved(center.distanceTo(center.moveBy(0.0, 1_200.0)).roundToInt()),
                SettingsChange.Shrinks(on = false),
                SettingsChange.MapReloads,
                SettingsChange.BoardRemoved(1),
                SettingsChange.EverybodySees,
            ),
            settingsChanges(withCheckpoints, moved, items, ::kinds),
        )
    }
}
