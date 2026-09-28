package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

@Serializable
data class ZoneCircle(val center: GeoPoint, val radiusMeters: Double)

/**
 * Timeline of the shrinking zone. It starts at [initial] when the SEEKING phase begins; every stage
 * keeps the current circle for [ZoneStage.holdSeconds] and then linearly shrinks (and moves) it to
 * [ZoneStage.target] over [ZoneStage.shrinkSeconds].
 *
 * Clients receive the whole schedule up front and render the zone locally; the server evaluates the
 * same schedule (see `app.hovanki.shared.rules.circleAt`) for rule checks.
 */
@Serializable
data class ZoneSchedule(val initial: ZoneCircle, val stages: List<ZoneStage> = emptyList())

@Serializable
data class ZoneStage(val holdSeconds: Int, val shrinkSeconds: Int, val target: ZoneCircle)

@Serializable
data class GameSettings(
    val zone: ZoneSchedule,
    val hidingSeconds: Int = 300,
    val seekingSeconds: Int = 1800,
    val rules: GameRules = GameRules(),
    /**
     * The glow (docs/adr/0009-game-setup-glow-streets.md): every this many seconds of the search the seekers see every
     * active hider for [glowForSeconds], and afterwards the spot where the glow left them. 0 (older apps): no glow.
     */
    val glowEverySeconds: Int = 0,
    val glowForSeconds: Int = 0,
    /**
     * The zone's shape. [ZoneShape.STREETS]: the server builds whole city blocks around each circle of [zone], as much
     * area as the circle, and serves them at `ApiRoutes.streetZone`; the circles still set the timing.
     */
    val zoneShape: ZoneShape = ZoneShape.CIRCLE,
)

/** The zone's shape (docs/adr/0009-game-setup-glow-streets.md). */
@Serializable
enum class ZoneShape {
    /** The circles of the schedule, shrinking smoothly. */
    CIRCLE,

    /**
     * City blocks with the streets around them: the border runs along streets (along the circle where there are none).
     * Shrinks block by block: the next zone is announced by the schedule's hold, then the zone switches.
     */
    STREETS,

    /**
     * Drawn by an admin for a big game (docs/adr/0010-big-games.md): the server serves the polygons at
     * `ApiRoutes.streetZone` like a zone by streets, the drawn figure shrinking towards its center stage by stage. Older
     * apps read it as [CIRCLE] (the property has a default) and still draw the polygons they are served.
     */
    DRAWN,
}

/**
 * Thresholds used by the rule checks on the server and for hints on the client.
 * Defaults follow docs/adr/0001-stack.md ("Точность GPS", "Честная игра").
 */
@Serializable
data class GameRules(
    /** Fixes with a worse accuracy are ignored by every rule check; the building rule has [buildingMaxAccuracyMeters]. */
    val maxUsableAccuracyMeters: Double = 20.0,
    /** Decisions are never made on a single fix: they need [minFixesForDecision] fixes within this window. */
    val decisionWindowSeconds: Int = 20,
    val minFixesForDecision: Int = 3,
    /** Extra tolerance at the zone border, on top of the fix accuracy. */
    val zoneBorderMarginMeters: Double = 10.0,
    /** Time to get back into the zone before the player is eliminated. */
    val outOfZoneGraceSeconds: Int = 60,
    /** A catch claim is rejected when GPS proves the players are farther apart than this. */
    val catchMaxDistanceMeters: Double = 40.0,
    /** The hider has this long to show the code; silence counts as caught. */
    val catchCodeTimeoutSeconds: Int = 60,
    val catchCodeMaxAttempts: Int = 5,
    val catchCodePeriodSeconds: Int = 30,
    val catchCodeDigits: Int = 4,
    val disputeVoteSeconds: Int = 60,
    /** Players without location updates for this long are revealed to seekers at their last point. */
    val staleLocationRevealSeconds: Int = 45,
    /** Fixes implying a faster movement are dropped (the game is played on foot). */
    val maxPlausibleSpeedMetersPerSecond: Double = 12.0,
    /** How often clients send their position and poll the state. */
    val syncIntervalSeconds: Int = 3,
    /** A hider confidently inside a building for this long is revealed to seekers; they are warned right away. */
    val insideBuildingRevealSeconds: Int = 60,
    /**
     * Unused since 2026-09-28 (the margin on top of the fix accuracy, which ordinary houses never allowed); older apps
     * still send it with their settings. The building rule uses [buildingDotMarginMeters].
     */
    val buildingWallMarginMeters: Double = 5.0,
    /** A fix counts as inside a building when its dot on the map is at least this far from every wall. */
    val buildingDotMarginMeters: Double = 3.0,
    /** The building rule also takes fixes up to this accuracy: indoors, phones rarely do better than 20–35 m. */
    val buildingMaxAccuracyMeters: Double = 40.0,
    /** A player counts as inside a building when at least this share of the recent fixes is inside. */
    val buildingInsideShare: Double = 0.8,
)
