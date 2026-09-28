package app.hovanki.shared.rules

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.ZoneCircle
import app.hovanki.shared.protocol.ZoneSchedule
import app.hovanki.shared.protocol.ZoneStage
import kotlin.math.pow

/** Where the zone is at a given moment and what happens next. */
data class ZoneState(
    val current: ZoneCircle,
    /** Circle the zone is shrinking (or will shrink) to; null after the last stage. */
    val next: ZoneCircle?,
    val isShrinking: Boolean,
    /** Time until the current hold or shrink ends; null after the last stage. */
    val millisUntilChange: Long?,
    /**
     * How many stages are over: the index of the zone by streets in force (`StreetZone.stages`); [next] is the one
     * after it. A zone by streets switches when a stage is over, it does not shrink smoothly.
     */
    val stage: Int = 0,
)

/** Zone state [elapsedMillis] after the zone schedule started. */
fun ZoneSchedule.stateAt(elapsedMillis: Long): ZoneState {
    var current = initial
    var remaining = elapsedMillis.coerceAtLeast(0)
    for ((index, stage) in stages.withIndex()) {
        val holdMillis = stage.holdSeconds * 1000L
        if (remaining < holdMillis) {
            return ZoneState(current, stage.target, isShrinking = false, holdMillis - remaining, stage = index)
        }
        remaining -= holdMillis
        val shrinkMillis = stage.shrinkSeconds * 1000L
        if (remaining < shrinkMillis) {
            val circle = interpolate(current, stage.target, remaining.toDouble() / shrinkMillis)
            return ZoneState(circle, stage.target, isShrinking = true, shrinkMillis - remaining, stage = index)
        }
        remaining -= shrinkMillis
        current = stage.target
    }
    return ZoneState(current, next = null, isShrinking = false, millisUntilChange = null, stage = stages.size)
}

fun ZoneSchedule.circleAt(elapsedMillis: Long): ZoneCircle = stateAt(elapsedMillis).current

/** A circle around the initial center that holds the zone during its whole schedule, plus [marginMeters]. */
fun ZoneSchedule.boundingCircle(marginMeters: Double = 0.0): ZoneCircle {
    val center = initial.center
    val radius = (listOf(initial) + stages.map { it.target }).maxOf { it.center.distanceTo(center) + it.radiusMeters }
    return ZoneCircle(center, radius + marginMeters)
}

private fun interpolate(from: ZoneCircle, to: ZoneCircle, fraction: Double): ZoneCircle = ZoneCircle(
    center = GeoPoint(
        lat = from.center.lat + (to.center.lat - from.center.lat) * fraction,
        lon = from.center.lon + (to.center.lon - from.center.lon) * fraction,
    ),
    radiusMeters = from.radiusMeters + (to.radiusMeters - from.radiusMeters) * fraction,
)

/**
 * Default schedule: a circle around [center] that shrinks [steps] times down to [finalRadiusMeters].
 * Good enough for a park; the game-creation UI can offer something smarter later.
 */
fun shrinkingZone(
    center: GeoPoint,
    initialRadiusMeters: Double = 500.0,
    finalRadiusMeters: Double = 100.0,
    steps: Int = 3,
    holdSeconds: Int = 300,
    shrinkSeconds: Int = 120,
): ZoneSchedule {
    require(steps >= 0)
    val radiusStep = if (steps == 0) 0.0 else (initialRadiusMeters - finalRadiusMeters) / steps
    return ZoneSchedule(
        initial = ZoneCircle(center, initialRadiusMeters),
        stages = (1..steps).map { step ->
            ZoneStage(holdSeconds, shrinkSeconds, ZoneCircle(center, initialRadiusMeters - radiusStep * step))
        },
    )
}

/**
 * The squeeze at the end of the search: the zone keeps shrinking after [this] schedule's stages instead of standing still
 * for the rest of the round. [Endgame.STEPS] more stages from the end of the last one to [Endgame.END_SHARE] of
 * [seekingSeconds], each a third hold and two thirds shrink, down to [Endgame.finalRadiusMeters]; the last minutes are
 * played there. A schedule that does not shrink stays as it is (the host chose so), as does one that already reaches
 * the end or the final size.
 */
fun ZoneSchedule.withEndgame(seekingSeconds: Int): ZoneSchedule {
    if (stages.isEmpty()) return this
    val start = stages.sumOf { it.holdSeconds + it.shrinkSeconds }
    val end = (seekingSeconds * Endgame.END_SHARE).toInt()
    val last = stages.last().target
    val finalRadius = Endgame.finalRadiusMeters(initial.radiusMeters)
    if (end - start < Endgame.STEPS * Endgame.MIN_STAGE_SECONDS || last.radiusMeters <= finalRadius * 1.2) return this
    val stageSeconds = (end - start) / Endgame.STEPS
    val holdSeconds = stageSeconds / 3
    val factor = (finalRadius / last.radiusMeters).pow(1.0 / Endgame.STEPS)
    val endgame = (1..Endgame.STEPS).map { step ->
        val radius = if (step == Endgame.STEPS) finalRadius else last.radiusMeters * factor.pow(step)
        ZoneStage(holdSeconds, stageSeconds - holdSeconds, ZoneCircle(last.center, radius))
    }
    return copy(stages = stages + endgame)
}

/** The numbers of [withEndgame]. */
object Endgame {
    /** Stages of the squeeze. */
    const val STEPS = 3

    /** The squeeze ends at this share of the search; the rest is played at the final size. */
    const val END_SHARE = 0.95

    /** The zone at the very end: about the reach of a catch, a hider can't keep away from a seeker there. */
    const val FINAL_RADIUS_METERS = 30.0

    /** ...or this share of the start for a large zone (a big game), whichever is more. */
    const val FINAL_RADIUS_SHARE = 0.05

    /** A squeeze stage shorter than this is no squeeze: a very short search keeps its schedule. */
    const val MIN_STAGE_SECONDS = 20

    fun finalRadiusMeters(initialRadiusMeters: Double): Double =
        maxOf(FINAL_RADIUS_METERS, initialRadiusMeters * FINAL_RADIUS_SHARE)
}

/**
 * Zone checks with a margin for GPS error: when in doubt, the player is inside. The same for a circle and for the
 * zone by streets ([ZoneArea]): what counts is how far outside the border the whole accuracy circle is.
 */
object ZoneRules {
    fun isClearlyOutside(fix: LocationSample, zone: ZoneArea, rules: GameRules): Boolean =
        zone.signedDistanceMeters(fix.point) - fix.accuracyMeters > rules.zoneBorderMarginMeters

    fun isClearlyOutside(fix: LocationSample, zone: ZoneCircle, rules: GameRules): Boolean =
        isClearlyOutside(fix, ZoneArea.Circle(zone), rules)

    /** True only when there are enough recent usable fixes and all of them are clearly outside. */
    fun isConfidentlyOutside(recentUsableFixes: List<LocationSample>, zone: ZoneArea, rules: GameRules): Boolean =
        recentUsableFixes.size >= rules.minFixesForDecision &&
            recentUsableFixes.all { isClearlyOutside(it, zone, rules) }

    fun isConfidentlyOutside(recentUsableFixes: List<LocationSample>, zone: ZoneCircle, rules: GameRules): Boolean =
        isConfidentlyOutside(recentUsableFixes, ZoneArea.Circle(zone), rules)

    /**
     * Back after an out-of-zone warning: the latest [GameRules.minFixesForDecision] usable fixes are all not clearly
     * outside. The counterpart of [isConfidentlyOutside]: one fix that jumps inside lifts no warning either.
     */
    fun isConfidentlyBack(recentUsableFixes: List<LocationSample>, zone: ZoneArea, rules: GameRules): Boolean =
        recentUsableFixes.size >= rules.minFixesForDecision &&
            recentUsableFixes.takeLast(rules.minFixesForDecision).none { isClearlyOutside(it, zone, rules) }

    fun isConfidentlyBack(recentUsableFixes: List<LocationSample>, zone: ZoneCircle, rules: GameRules): Boolean =
        isConfidentlyBack(recentUsableFixes, ZoneArea.Circle(zone), rules)
}
