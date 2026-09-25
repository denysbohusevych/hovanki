package app.hovanki.shared.rules

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.ZoneCircle
import app.hovanki.shared.protocol.ZoneSchedule
import app.hovanki.shared.protocol.ZoneStage

/** Where the zone is at a given moment and what happens next. */
data class ZoneState(
    val current: ZoneCircle,
    /** Circle the zone is shrinking (or will shrink) to; null after the last stage. */
    val next: ZoneCircle?,
    val isShrinking: Boolean,
    /** Time until the current hold or shrink ends; null after the last stage. */
    val millisUntilChange: Long?,
)

/** Zone state [elapsedMillis] after the zone schedule started. */
fun ZoneSchedule.stateAt(elapsedMillis: Long): ZoneState {
    var current = initial
    var remaining = elapsedMillis.coerceAtLeast(0)
    for (stage in stages) {
        val holdMillis = stage.holdSeconds * 1000L
        if (remaining < holdMillis) {
            return ZoneState(current, stage.target, isShrinking = false, millisUntilChange = holdMillis - remaining)
        }
        remaining -= holdMillis
        val shrinkMillis = stage.shrinkSeconds * 1000L
        if (remaining < shrinkMillis) {
            val circle = interpolate(current, stage.target, remaining.toDouble() / shrinkMillis)
            return ZoneState(circle, stage.target, isShrinking = true, millisUntilChange = shrinkMillis - remaining)
        }
        remaining -= shrinkMillis
        current = stage.target
    }
    return ZoneState(current, next = null, isShrinking = false, millisUntilChange = null)
}

fun ZoneSchedule.circleAt(elapsedMillis: Long): ZoneCircle = stateAt(elapsedMillis).current

private fun interpolate(from: ZoneCircle, to: ZoneCircle, fraction: Double): ZoneCircle =
    ZoneCircle(
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

/** Zone checks with a margin for GPS error: when in doubt, the player is inside. */
object ZoneRules {
    fun isClearlyOutside(fix: LocationSample, zone: ZoneCircle, rules: GameRules): Boolean =
        fix.point.distanceTo(zone.center) - fix.accuracyMeters > zone.radiusMeters + rules.zoneBorderMarginMeters

    /** True only when there are enough recent usable fixes and all of them are clearly outside. */
    fun isConfidentlyOutside(recentUsableFixes: List<LocationSample>, zone: ZoneCircle, rules: GameRules): Boolean =
        recentUsableFixes.size >= rules.minFixesForDecision &&
            recentUsableFixes.all { isClearlyOutside(it, zone, rules) }
}
