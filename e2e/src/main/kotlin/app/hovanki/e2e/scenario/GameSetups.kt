package app.hovanki.e2e.scenario

import app.hovanki.shared.protocol.FeatureMode
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.QuestKind
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.rules.shrinkingZone

/** Game settings for tests: the same rules as in production, with timers short enough for a game in a minute. */
object GameSetups {
    /** Where the test games take place: Mariinskyi Park, Kyiv. */
    val PARK = GeoPoint(50.4476, 30.5396)

    val FAST_RULES = GameRules(
        decisionWindowSeconds = 10,
        outOfZoneGraceSeconds = 10,
        catchCodeTimeoutSeconds = 10,
        disputeVoteSeconds = 8,
        staleLocationRevealSeconds = 15,
        syncIntervalSeconds = 1,
        insideBuildingRevealSeconds = 20,
        radarOffRevealSeconds = 10,
    )

    /**
     * 10 s to hide, then a 300 m zone that shrinks to 200 m within 30 s: players within 150 m of [center]
     * stay inside the whole game.
     */
    fun fast(center: GeoPoint = PARK, rules: GameRules = FAST_RULES): GameSettings = GameSettings(
        zone = shrinkingZone(
            center,
            initialRadiusMeters = 300.0,
            finalRadiusMeters = 200.0,
            steps = 2,
            holdSeconds = 5,
            shrinkSeconds = 10,
        ),
        hidingSeconds = 10,
        seekingSeconds = 600,
        rules = rules,
    )

    /** A fixed zone of [radiusMeters] around [center], for zone-border scenarios. */
    fun fixedZone(radiusMeters: Double, center: GeoPoint = PARK, rules: GameRules = FAST_RULES): GameSettings =
        fast(center, rules).copy(zone = shrinkingZone(center, initialRadiusMeters = radiusMeters, steps = 0))

    /**
     * A fixed zone of 500 m with the glow (docs/adr/0009-game-setup-glow-streets.md): every [everySeconds] of the
     * search the seekers see the hiders for [forSeconds].
     */
    fun glowing(everySeconds: Int = 20, forSeconds: Int = 4, center: GeoPoint = PARK): GameSettings =
        fixedZone(500.0, center).copy(glowEverySeconds = everySeconds, glowForSeconds = forSeconds)

    /**
     * A fixed zone of 500 m with the radar (docs/adr/0012-nearby-radar.md): the hiders' sense on, a claim only up close
     * with [proximityCatch], the pocket stealth with [pocketStealth]. Needs the server features on.
     */
    fun radar(
        mode: FeatureMode = FeatureMode.OPTIONAL,
        proximityCatch: Boolean = false,
        pocketStealth: Boolean = false,
        center: GeoPoint = PARK,
    ): GameSettings = fixedZone(500.0, center).copy(
        features = GameFeatures(
            radar = mode,
            hiderSense = true,
            proximityCatch = proximityCatch,
            pocketStealth = pocketStealth,
        ),
    )

    /**
     * The board (docs/adr/0013-quests-sparks-and-sensors.md): quests, perks, checkpoints and pickups on a fixed zone
     * of 500 m with the glow (some perks need it). Needs the server features on.
     */
    fun board(center: GeoPoint = PARK): GameSettings = glowing(everySeconds = 20, forSeconds = 4, center).copy(
        features = GameFeatures(quests = true, perks = true, checkpoints = true, pickups = true),
        quests = listOf(QuestKind.SPRINT, QuestKind.FIRST_CATCH),
    )

    /** A fixed zone by streets of [radiusMeters] on the test grid the fake street source lays around [center]. */
    fun streets(radiusMeters: Double = 300.0, center: GeoPoint = PARK): GameSettings =
        fixedZone(radiusMeters, center).copy(zoneShape = ZoneShape.STREETS)
}
