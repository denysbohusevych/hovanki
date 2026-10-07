package app.hovanki.shared.rules

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.GeoPoint

/**
 * The cities of the city leaderboard (docs/adr/0022-city-leaderboard.md). The phone finds the player's city from where
 * it is now ([at]) and sends only the city's id: the position itself never leaves the phone for this. Ids only; the app
 * names them in its language. New cities may be added; never rename or remove an id (accounts keep them).
 */
object Cities {
    /** A city: its centre and how far from it still counts as the city (the suburbs of a big one too). */
    data class City(val id: String, val center: GeoPoint, val radiusMeters: Double = DEFAULT_RADIUS_METERS)

    /** The oblast centres outside the occupation and a few other big cities, roughly by size. */
    val ALL: List<City> = listOf(
        City("kyiv", GeoPoint(50.4501, 30.5234), radiusMeters = 30_000.0),
        City("kharkiv", GeoPoint(49.9935, 36.2304), radiusMeters = 25_000.0),
        City("odesa", GeoPoint(46.4825, 30.7233), radiusMeters = 25_000.0),
        City("dnipro", GeoPoint(48.4647, 35.0462), radiusMeters = 25_000.0),
        City("lviv", GeoPoint(49.8397, 24.0297)),
        City("zaporizhzhia", GeoPoint(47.8388, 35.1396)),
        City("kryvyi_rih", GeoPoint(47.9105, 33.3918), radiusMeters = 30_000.0),
        City("mykolaiv", GeoPoint(46.9750, 31.9946)),
        City("vinnytsia", GeoPoint(49.2331, 28.4682)),
        City("poltava", GeoPoint(49.5883, 34.5514)),
        City("chernihiv", GeoPoint(51.4982, 31.2893)),
        City("cherkasy", GeoPoint(49.4444, 32.0598)),
        City("khmelnytskyi", GeoPoint(49.4229, 26.9871)),
        City("zhytomyr", GeoPoint(50.2547, 28.6587)),
        City("sumy", GeoPoint(50.9077, 34.7981)),
        City("rivne", GeoPoint(50.6199, 26.2516)),
        City("ivano_frankivsk", GeoPoint(48.9226, 24.7111)),
        City("kropyvnytskyi", GeoPoint(48.5079, 32.2623)),
        City("ternopil", GeoPoint(49.5535, 25.5948)),
        City("lutsk", GeoPoint(50.7472, 25.3254)),
        City("kherson", GeoPoint(46.6354, 32.6169)),
        City("bila_tserkva", GeoPoint(49.7988, 30.1153)),
        City("kremenchuk", GeoPoint(49.0670, 33.4204)),
        City("uzhhorod", GeoPoint(48.6208, 22.2879)),
        City("chernivtsi", GeoPoint(48.2915, 25.9403)),
    )

    val IDS: List<String> = ALL.map { it.id }

    private val known = IDS.toSet()

    fun isKnown(id: String): Boolean = id in known

    /** The city [point] is in: the nearest centre within its radius; null outside every city of the list. */
    fun at(point: GeoPoint): String? = ALL
        .map { it to point.distanceTo(it.center) }
        .filter { (city, distance) -> distance <= city.radiusMeters }
        .minByOrNull { (_, distance) -> distance }
        ?.first
        ?.id

    /** Most cities: the centre and the edges of the town. */
    const val DEFAULT_RADIUS_METERS = 20_000.0
}
