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
)

/**
 * Thresholds used by the rule checks on the server and for hints on the client.
 * Defaults follow docs/adr/0001-stack.md ("Точность GPS", "Честная игра").
 */
@Serializable
data class GameRules(
    /** Fixes with a worse accuracy are ignored by every rule check. */
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
)
