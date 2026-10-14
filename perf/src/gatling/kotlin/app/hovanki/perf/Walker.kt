package app.hovanki.perf

import app.hovanki.shared.geo.bearingTo
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A player walking about the zone: a fix at every sync, as the app sends one GPS fix per sync interval. Turns a little
 * at every step and back towards [center] near the edge of [radiusMeters], so the player stays inside the zone and the
 * game's rules (the zone, the buildings, the speed check) see an ordinary walk.
 */
class Walker(private val center: GeoPoint, private val radiusMeters: Double, seed: Int, start: GeoPoint = center) {
    private val random = Random(seed)
    private var position = start
    private var heading = random.nextDouble(0.0, 2 * PI)
    private var lastMillis = 0L

    /** Where the player is at [nowMillis] (server time: the lab's server is on this machine). */
    fun fix(nowMillis: Long): LocationSample {
        if (lastMillis != 0L) {
            val meters = WALKING_METERS_PER_SECOND * (nowMillis - lastMillis) / 1000.0
            heading = if (position.distanceTo(center) > radiusMeters * EDGE) {
                Math.toRadians(position.bearingTo(center))
            } else {
                heading + random.nextDouble(-TURN, TURN)
            }
            position = position.moveBy(eastMeters = meters * sin(heading), northMeters = meters * cos(heading))
        }
        lastMillis = nowMillis
        return LocationSample(position, accuracyMeters = random.nextDouble(4.0, 12.0), timestampMillis = nowMillis)
    }

    private companion object {
        const val WALKING_METERS_PER_SECOND = 1.4
        const val TURN = 0.6
        const val EDGE = 0.8
    }
}
