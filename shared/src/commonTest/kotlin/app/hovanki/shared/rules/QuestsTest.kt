package app.hovanki.shared.rules

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.Audience
import app.hovanki.shared.protocol.DistanceBand
import app.hovanki.shared.protocol.FeatureMode
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.QuestKind
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.ServerFeature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuestsTest {
    private val center = GeoPoint(50.4501, 30.5234)

    @Test
    fun everyKindHasASpecAndTheCatalogIsPickableWithoutCustom() {
        for (kind in QuestKind.entries) assertNotNull(QuestCatalog.spec(kind))
        assertTrue(QuestKind.CUSTOM !in QuestCatalog.pickable)
        for (perk in PerkKind.entries) assertNotNull(PerkCatalog.spec(perk))
        assertEquals(4, PerkCatalog.forRole(Role.HIDER).size)
        assertEquals(4, PerkCatalog.forRole(Role.SEEKER).size)
    }

    @Test
    fun questsNeedWhatTheyNeed() {
        val plain = GameSetup(glowEveryMinutes = 0).settings(center)
        assertNotNull(QuestCatalog.problem(QuestKind.SPY, plain))
        assertNotNull(QuestCatalog.problem(QuestKind.AFTER_GLOW, plain))
        assertNull(QuestCatalog.problem(QuestKind.RELOCATE, plain))
        val full = GameSetup(features = GameFeatures(radar = FeatureMode.OPTIONAL, quests = true)).settings(center)
        assertNull(QuestCatalog.problem(QuestKind.SPY, full))
        assertNull(QuestCatalog.problem(QuestKind.AFTER_GLOW, full))
        assertNotNull(QuestCatalog.problem(QuestKind.CUSTOM, full))
    }

    @Test
    fun theSetupDropsWhatItCantJudge() {
        val setup = GameSetup(
            glowEveryMinutes = 0,
            features = GameFeatures(hiderSense = true, proximityCatch = true, quests = true),
            quests = listOf(
                QuestKind.SPY,
                QuestKind.AFTER_GLOW,
                QuestKind.RELOCATE,
                QuestKind.RELOCATE,
                QuestKind.CUSTOM,
            ),
        ).coerced()
        assertEquals(listOf(QuestKind.RELOCATE), setup.quests)
        assertEquals(GameFeatures(quests = true), setup.features)
        assertEquals(setup, GameSetup.of(setup.settings(center)))
        assertNull(SettingsLimits.problem(setup.settings(center)))
    }

    @Test
    fun theServerRefusesWhatTheScreenNeverMakes() {
        val settings = GameSetup().settings(center)
        assertNotNull(SettingsLimits.problem(settings.copy(features = GameFeatures(hiderSense = true))))
        assertNotNull(SettingsLimits.problem(settings.copy(quests = listOf(QuestKind.RELOCATE))))
        assertNotNull(
            SettingsLimits.problem(
                settings.copy(features = GameFeatures(quests = true), quests = listOf(QuestKind.SPY)),
            ),
        )
        assertNull(
            SettingsLimits.problem(
                settings.copy(features = GameFeatures(quests = true), quests = listOf(QuestKind.AFTER_GLOW)),
            ),
        )
    }

    @Test
    fun featuresLimitedToWhatTheServerHasOn() {
        val all = GameFeatures(
            radar = FeatureMode.REQUIRED,
            hiderSense = true,
            proximityCatch = true,
            quests = true,
            perks = true,
            pickups = true,
        )
        assertEquals(
            all.uses(),
            setOf(
                ServerFeature.RADAR,
                ServerFeature.HIDER_SENSE,
                ServerFeature.PROXIMITY_CATCH,
                ServerFeature.QUESTS,
                ServerFeature.PERKS,
                ServerFeature.PICKUPS,
            ),
        )
        val limited = all.limitedTo(setOf(ServerFeature.QUESTS, ServerFeature.HIDER_SENSE))
        assertEquals(GameFeatures(quests = true), limited)
        assertEquals(all, all.limitedTo(ServerFeature.entries.toSet()))
    }

    @Test
    fun sectorsAndBands() {
        assertEquals(0, Sectors.sectorOf(center.moveBy(eastMeters = 10.0, northMeters = 100.0), center))
        assertEquals(1, Sectors.sectorOf(center.moveBy(eastMeters = 100.0, northMeters = 10.0), center))
        assertEquals(5, Sectors.sectorOf(center.moveBy(eastMeters = -100.0, northMeters = 100.0), center))
        assertEquals(0, Sectors.compassSector(10.0))
        assertEquals(0, Sectors.compassSector(350.0))
        assertEquals(2, Sectors.compassSector(95.0))
        assertEquals(7, Sectors.compassSector(300.0))
        assertEquals(DistanceBand.NEAR, Sectors.bandFor(20.0))
        assertEquals(DistanceBand.CLOSE, Sectors.bandFor(120.0))
        assertEquals(DistanceBand.FAR, Sectors.bandFor(400.0))
    }

    @Test
    fun checkpointCodesTravelInAQrCode() {
        val payload = CheckpointPayload(GameId("g1"), "ABCD2345")
        assertEquals(payload, CheckpointPayload.decode(payload.encode()))
        assertNull(CheckpointPayload.decode("hovanki:1:g1:p1:1234"))
        assertNull(CheckpointPayload.decode("hovanki:cp:g1:"))
        assertEquals(Audience.ALL, QuestCatalog.spec(QuestKind.SPRINT).audience)
        assertTrue(BoardRules.isFor(Audience.HIDERS, Role.HIDER))
        assertTrue(!BoardRules.isFor(Audience.HIDERS, Role.SEEKER))
        assertEquals(2, BoardRules.laterSparks(5))
        assertEquals(1, BoardRules.laterSparks(1))
    }
}
