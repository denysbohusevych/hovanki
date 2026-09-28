package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

/** Whether the zone by streets ([ZoneShape.STREETS]) is there yet (docs/adr/0009-game-setup-glow-streets.md). */
@Serializable
enum class StreetZoneState {
    /** The server is loading the streets and building the blocks. */
    LOADING,

    /** Built; the polygons are served at [ApiRoutes.streetZone]. */
    READY,

    /** No streets to build it from (map data down, no streets around): the game uses the circles. */
    UNAVAILABLE,
}

/** A zone as a polygon: its border, a closed ring, without holes. */
@Serializable
data class ZonePolygon(val outline: List<GeoPoint>)

/**
 * The zone by streets of one [mapRevision]: [stages] has the zone at the start and after each stage of the schedule
 * (`ZoneSchedule.stages`), each inside the one before.
 */
@Serializable
data class StreetZoneResponse(
    val state: StreetZoneState? = null,
    val mapRevision: Int = 0,
    val stages: List<ZonePolygon> = emptyList(),
)
