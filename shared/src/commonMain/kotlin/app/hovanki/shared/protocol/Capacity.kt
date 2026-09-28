package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

// How many players a zone fits (docs/adr/0010-big-games.md): the server reads the ground under the zone from the map
// tiles (houses, water, woods, parks, fields), counts the square meters of each kind and divides them by how much of
// that ground one player needs. A recommendation, never a limit for an ordinary game.

/** Whether the server knows the ground under the zone yet. */
@Serializable
enum class CapacityState {
    /** The map data is on its way. */
    LOADING,

    /** [ZoneCapacity.players] and [ZoneCapacity.areas] are there. */
    READY,

    /** No map data (tiles unreachable, the zone too large for them): no estimate, no warning. */
    UNAVAILABLE,
}

/**
 * Square meters of a zone by kind of ground. [blockedSquareMeters]: houses and water, where nobody may hide; they are
 * not part of the playing area.
 */
@Serializable
data class TerrainAreas(
    /** Built-up blocks: streets, yards, arches. A hider is out of sight around the nearest corner. */
    val denseSquareMeters: Long = 0,
    /** Woods and thickets: a person is seen 17–64 m away in summer, up to 140 m in a bare winter wood. */
    val forestSquareMeters: Long = 0,
    /** Parks and mixed ground: trees among lawns, gardens, cemeteries, and whatever the map does not say. */
    val mixedSquareMeters: Long = 0,
    /** Fields, steppe, beaches, sports grounds, large squares: seen hundreds of meters away. */
    val openSquareMeters: Long = 0,
    val blockedSquareMeters: Long = 0,
) {
    /** Where the players can be: everything but houses and water. */
    val playableSquareMeters: Long get() = denseSquareMeters + forestSquareMeters + mixedSquareMeters + openSquareMeters
}

/**
 * How much ground one player needs, by kind of ground, in square meters. Server settings (`hovanki.capacity.*`); an admin
 * sets them per big game. Starting values, to be corrected after the first games outdoors.
 */
@Serializable
data class AreaNorms(
    val denseSquareMeters: Int = 1_000,
    val forestSquareMeters: Int = 1_500,
    val mixedSquareMeters: Int = 2_500,
    val openSquareMeters: Int = 10_000,
)

/**
 * About how many players the zone at the start fits ([players]), from the ground under it ([areas]). [fewCovers]: most
 * of it is open ground. [accepted]: the host chose to play anyway, the lobby warns no more in this game.
 */
@Serializable
data class ZoneCapacity(
    val state: CapacityState = CapacityState.LOADING,
    val players: Int? = null,
    val areas: TerrainAreas? = null,
    val fewCovers: Boolean = false,
    val accepted: Boolean = false,
)
