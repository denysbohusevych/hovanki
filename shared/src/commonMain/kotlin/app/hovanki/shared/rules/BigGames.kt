package app.hovanki.shared.rules

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.AdminBigGameRequest
import app.hovanki.shared.protocol.BigGameSetup
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.protocol.ZoneShape
import kotlin.math.abs
import kotlin.math.roundToInt

/** A zone of polygons from the server (by streets, or drawn): the app fetches them, they switch stage by stage. */
val ZoneShape.hasPolygons: Boolean get() = this == ZoneShape.STREETS || this == ZoneShape.DRAWN

/** Square meters inside [ZonePolygon.outline] (a flat projection, fine for a few kilometers). */
fun ZonePolygon.areaSquareMeters(): Double {
    if (outline.size < 3) return 0.0
    val origin = outline.first()
    val points = outline.map { it.offsetFrom(origin) }
    var sum = 0.0
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        sum += a.eastMeters * b.northMeters - b.eastMeters * a.northMeters
    }
    return abs(sum) / 2
}

/**
 * [setup] as the settings of a big game around [center], whose drawn zone reaches [radiusMeters] from it: the circles
 * of the schedule set the timing and how far the figure has shrunk at each stage (the polygons come from the server),
 * the squeeze at the end included.
 */
fun BigGameSetup.settings(center: GeoPoint, radiusMeters: Double, rules: GameRules = GameRules()): GameSettings =
    GameSetup(
        radiusMeters = radiusMeters.roundToInt().coerceAtLeast(1),
        hidingMinutes = hidingMinutes,
        seekingMinutes = seekingMinutes,
        shrinks = shrinks,
        zoneShape = ZoneShape.DRAWN,
        glowEveryMinutes = glowEveryMinutes,
        glowForSeconds = glowForSeconds,
    ).settings(center, rules)

/**
 * What the server takes for a big game (docs/adr/0010-big-games.md), made by admins only: up to [MAX_PLAYERS] players
 * on a zone of up to [MAX_ZONE_SQUARE_METERS], scheduled up to [MAX_DAYS_AHEAD] ahead.
 */
object BigGameLimits {
    /** The most a big game takes; the real limit of the server comes from the load test (docs/ci-cd.md). */
    const val MAX_PLAYERS = 1_600
    const val MIN_PLAYERS = 2
    const val TITLE_MAX_LENGTH = 80
    const val MIN_CORNERS = 3
    const val MAX_CORNERS = 500

    /** A hectare: a big game in a smaller place is an ordinary game. */
    const val MIN_ZONE_SQUARE_METERS = 10_000.0

    /** 1 600 players on open ground need 16 km²; a little more than that. */
    const val MAX_ZONE_SQUARE_METERS = 25_000_000.0

    /** No corner farther than this from the zone's center. */
    const val MAX_ZONE_RADIUS_METERS = 5_000.0
    const val MAX_DAYS_AHEAD = 365
    val HIDING_MINUTES = 1..60
    val SEEKING_MINUTES = 10..240
    val GLOW_EVERY_MINUTES = 1..60
    val GLOW_FOR_SECONDS = 2..600

    /** The lobby opens this long before the start; the signed-up players are invited then. */
    const val LOBBY_OPENS_MINUTES = 30

    /** What is wrong with [request] (the zone's shape is checked on the server too); null when it is fine. */
    fun problem(request: AdminBigGameRequest): String? {
        val setup = request.setup
        val corners = request.zone.outline.let { if (it.size > 1 && it.first() == it.last()) it.dropLast(1) else it }
        val area = request.zone.areaSquareMeters()
        return when {
            request.title.trim().length !in 1..TITLE_MAX_LENGTH -> "The title has 1..$TITLE_MAX_LENGTH characters"

            corners.size !in MIN_CORNERS..MAX_CORNERS -> "The zone has $MIN_CORNERS..$MAX_CORNERS corners"

            corners.any { it.lat !in -85.0..85.0 || it.lon !in -180.0..180.0 } -> "A corner is off the map"

            area < MIN_ZONE_SQUARE_METERS || area > MAX_ZONE_SQUARE_METERS ->
                "The zone is ${(MIN_ZONE_SQUARE_METERS / 10_000).toInt()} ha to " +
                    "${(MAX_ZONE_SQUARE_METERS / 1_000_000).toInt()} km²"

            corners.any { a -> corners.any { b -> a.distanceTo(b) > 2 * MAX_ZONE_RADIUS_METERS } } ->
                "The zone is at most ${(2 * MAX_ZONE_RADIUS_METERS / 1000).toInt()} km across"

            setup.hidingMinutes !in HIDING_MINUTES -> "Hiding takes ${HIDING_MINUTES.first}..${HIDING_MINUTES.last} min"

            setup.seekingMinutes !in SEEKING_MINUTES ->
                "The search takes ${SEEKING_MINUTES.first}..${SEEKING_MINUTES.last} min"

            setup.glowEveryMinutes != 0 && setup.glowEveryMinutes !in GLOW_EVERY_MINUTES ->
                "The glow is every ${GLOW_EVERY_MINUTES.first}..${GLOW_EVERY_MINUTES.last} min, or off"

            setup.glowEveryMinutes != 0 &&
                (setup.glowForSeconds !in GLOW_FOR_SECONDS || setup.glowForSeconds >= setup.glowEveryMinutes * 60) ->
                "A glow lasts ${GLOW_FOR_SECONDS.first}..${GLOW_FOR_SECONDS.last} s and ends before the next one"

            request.playerLimit != null && request.playerLimit !in MIN_PLAYERS..MAX_PLAYERS ->
                "The limit is $MIN_PLAYERS..$MAX_PLAYERS players"

            setup.seekers < 1 -> "At least one seeker"

            request.playerLimit != null && setup.seekers >= request.playerLimit -> "Fewer seekers than players"

            request.norms != null && !Capacity.isValid(request.norms) ->
                "A norm is ${Capacity.MIN_NORM_SQUARE_METERS}..${Capacity.MAX_NORM_SQUARE_METERS} m² a player"

            else -> null
        }
    }
}
