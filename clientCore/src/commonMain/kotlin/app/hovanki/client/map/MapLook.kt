package app.hovanki.client.map

import app.hovanki.client.storage.ClientStorage
import app.hovanki.shared.protocol.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** The map's colors (docs/adr/0025-map-styles-and-height.md). */
@Serializable
enum class MapTheme {
    /** OpenFreeMap's Positron, as before: light and neutral. */
    LIGHT,

    /** Dark gray streets for evening games: less glare, the game's colors stand out. */
    DARK,

    /** Only streets, houses, water and parks: no names, nothing else to read on the run. */
    MINIMAL,

    /** [LIGHT] by day, [DARK] from sunset to sunrise where the map is ([Daylight]). */
    AUTO,
}

/**
 * How this phone draws its maps (docs/adr/0025-map-styles-and-height.md): the player's own choice, kept on the phone
 * only, for every map (the round, the lobby, a saved route, the replay). Nothing of it goes to the server.
 */
@Serializable
data class MapLook(
    val theme: MapTheme = MapTheme.LIGHT,
    /** Houses rise by their height while the map is tilted. */
    val buildings3d: Boolean = true,
    /** Hills and ravines shaded under the streets. */
    val relief: Boolean = true,
) {
    /** The theme to draw at [point] at [epochMillis]: [MapTheme.AUTO] picks light or dark by the sun. */
    fun themeAt(point: GeoPoint, epochMillis: Long): MapTheme = when (theme) {
        MapTheme.AUTO -> if (Daylight.isNight(point, epochMillis)) MapTheme.DARK else MapTheme.LIGHT
        else -> theme
    }
}

/** The look of every map on this phone: saved in [ClientStorage], and the maps follow [look] as the player changes it. */
class MapSettings(private val storage: ClientStorage) {
    private val state = MutableStateFlow(storage.loadMapLook() ?: MapLook())

    val look: StateFlow<MapLook> = state.asStateFlow()

    fun update(look: MapLook) {
        state.value = look
        storage.saveMapLook(look)
    }
}
