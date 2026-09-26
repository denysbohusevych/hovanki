package app.hovanki.server.buildings

import app.hovanki.shared.debug.DebugBuildings
import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.Passage
import app.hovanki.shared.protocol.ZoneCircle

/** The buildings of an area where hiding is not allowed, and the ways through them (docs/adr/0003). */
data class Buildings(val buildings: List<BuildingArea>, val passages: List<Passage> = emptyList())

/** The buildings could not be loaded; the game runs without the building rule. [message] must not hold coordinates. */
class BuildingsUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Where the outlines of a game's buildings come from (docs/adr/0003-map-and-buildings.md): OpenStreetMap through
 * the Overpass API in production, a fixed test quarter in tests and with the `e2e` profile. Blocking: called off the
 * request threads, once per game.
 */
fun interface BuildingSource {
    /** Buildings within [area]; throws (preferably [BuildingsUnavailableException]) when they can't be loaded. */
    fun load(area: ZoneCircle): Buildings
}

/** The test quarter of [DebugBuildings] next to the zone center: the building rule without an external service. */
class FakeBuildingSource : BuildingSource {
    override fun load(area: ZoneCircle): Buildings {
        val quarter = DebugBuildings.around(area.center)
        return Buildings(quarter.buildings, quarter.passages)
    }
}

/** No building data at all: every game runs without the building rule. */
class NoBuildingSource : BuildingSource {
    override fun load(area: ZoneCircle): Buildings = throw BuildingsUnavailableException("The building source is off")
}
