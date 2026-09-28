package app.hovanki.server.game

import app.hovanki.shared.protocol.AreaNorms
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `hovanki.capacity.*`: how much ground one player needs, by kind of ground (docs/adr/0010-big-games.md). Every new game
 * takes these; an admin sets a big game's own. Starting values from search-and-rescue detection ranges, airsoft fields
 * and manhunt rules, to be corrected after the first games outdoors.
 */
@ConfigurationProperties("hovanki.capacity")
data class CapacityProperties(
    /** Built-up blocks: streets, yards, arches; a hider is out of sight around the nearest corner. */
    val denseSquareMeters: Int = 1_000,
    /** Woods: a person is seen 17–64 m away in summer, up to 140 m without leaves. */
    val forestSquareMeters: Int = 1_500,
    /** Parks and mixed ground: trees among lawns. */
    val mixedSquareMeters: Int = 2_500,
    /** Fields, steppe, beaches, large squares: seen hundreds of meters away. */
    val openSquareMeters: Int = 10_000,
) {
    fun norms(): AreaNorms = AreaNorms(denseSquareMeters, forestSquareMeters, mixedSquareMeters, openSquareMeters)
}
