package app.hovanki.shared.rules

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.LocationSample

/**
 * GPS is a hint, not a judge: a catch is only rejected when the fixes *prove* the players were too far apart.
 * The real proof of a meeting is the catch code shown on the hider's phone.
 */
object CatchRules {
    /** Smallest real distance consistent with both fixes and their accuracy radii. */
    fun minPossibleDistanceMeters(a: LocationSample, b: LocationSample): Double =
        (a.point.distanceTo(b.point) - a.accuracyMeters - b.accuracyMeters).coerceAtLeast(0.0)

    fun isWithinReach(seeker: LocationSample, hider: LocationSample, rules: GameRules): Boolean =
        minPossibleDistanceMeters(seeker, hider) <= rules.catchMaxDistanceMeters

    /**
     * Best case over all pairs of recent fixes (never a single point): a claim is rejected only when
     * even the closest pair is too far apart. Null when one side has no fixes.
     */
    fun closestPossibleDistanceMeters(seekerFixes: List<LocationSample>, hiderFixes: List<LocationSample>): Double? =
        seekerFixes.flatMap { s -> hiderFixes.map { h -> minPossibleDistanceMeters(s, h) } }.minOrNull()

    /** Most likely distance (closest pair of fixes, accuracy ignored); used by the dispute default rule. */
    fun estimatedDistanceMeters(seekerFixes: List<LocationSample>, hiderFixes: List<LocationSample>): Double? =
        seekerFixes.flatMap { s -> hiderFixes.map { h -> s.point.distanceTo(h.point) } }.minOrNull()
}
