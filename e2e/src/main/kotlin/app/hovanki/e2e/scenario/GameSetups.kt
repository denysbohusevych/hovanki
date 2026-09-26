package app.hovanki.e2e.scenario

import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
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
}
