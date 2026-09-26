package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

/**
 * Whether the "no hiding in buildings" rule is on for a game (docs/adr/0003-map-and-buildings.md). The server loads
 * the building outlines of the zone once, when the game is created.
 */
@Serializable
enum class BuildingsState {
    /** The server is still loading the building outlines. */
    LOADING,

    /** The rule is on; the outlines are served at [ApiRoutes.buildings]. */
    READY,

    /** The outlines could not be loaded (source down, too many buildings): the game runs without the rule. */
    UNAVAILABLE,
}

/** A building where hiding is not allowed: its outline and holes (inner courtyards), each a closed ring. */
@Serializable
data class BuildingArea(val outline: List<GeoPoint>, val holes: List<List<GeoPoint>> = emptyList())

/** A way through or under a building (arch, passage, arcade): standing there is outdoors, not inside. */
@Serializable
data class Passage(val path: List<GeoPoint>, val widthMeters: Double)

/** The buildings the server judges by: exactly what the map shows as forbidden. */
@Serializable
data class BuildingsResponse(
    val state: BuildingsState? = null,
    val buildings: List<BuildingArea> = emptyList(),
    val passages: List<Passage> = emptyList(),
)
