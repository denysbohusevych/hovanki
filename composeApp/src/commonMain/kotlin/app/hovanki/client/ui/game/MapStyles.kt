package app.hovanki.client.ui.game

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Our own base styles over OpenFreeMap's tiles (docs/adr/0025-map-styles-and-height.md): the same OpenMapTiles data as
 * Positron, drawn by fewer layers. [dark] for evening games, readable streets on dark gray with their names;
 * [minimal] only streets, houses, water and parks, without a single name. The game's layers go on top of either.
 */
internal object MapStyles {
    fun dark(): JsonObject = streets(
        StreetColors(
            background = "#1B1C1F",
            park = "#1D2721",
            water = "#14212C",
            building = "#26282C",
            buildingOutline = "#303237",
            minorRoad = "#43464E",
            majorRoad = "#5A5E68",
            path = "#34373D",
            rail = "#303338",
            label = "#A3A7B0",
        ),
    )

    fun minimal(): JsonObject = streets(
        StreetColors(
            background = "#F7F7F9",
            park = "#E4EDE0",
            water = "#D5E3ED",
            building = "#EEEEF1",
            buildingOutline = "#E5E5E9",
            minorRoad = "#CACBD3",
            majorRoad = "#B5B7C2",
            path = "#D6D7DE",
            rail = "#DCDCE2",
            label = null,
        ),
    )

    /** The tiles' source in every style of ours, as OpenFreeMap's own styles name it. */
    const val SOURCE = "openmaptiles"

    private fun streets(colors: StreetColors): JsonObject = buildJsonObject {
        put("version", 8)
        put("glyphs", MapStyle.GLYPHS)
        putJsonObject("sources") {
            putJsonObject(SOURCE) {
                put("type", "vector")
                put("url", MapStyle.TILES)
            }
        }
        putJsonArray("layers") {
            add(layer("background", "background") { paint("background-color" to colors.background) })
            add(
                layer("park", "fill", "park") {
                    paint("fill-color" to colors.park)
                },
            )
            add(
                layer("landcover", "fill", "landcover", minZoom = 10) {
                    filter(match(get("class"), listOf("wood", "grass"), true, false))
                    paint("fill-color" to colors.park)
                },
            )
            add(
                layer("water", "fill", "water") {
                    filter(expr("!=", get("brunnel"), "tunnel"))
                    paint("fill-color" to colors.water)
                },
            )
            add(
                layer("waterway", "line", "waterway", minZoom = 12) {
                    paint("line-color" to colors.water, "line-width" to zoomRamp(1.2, 12 to 1, 18 to 5))
                },
            )
            add(
                layer("building", "fill", "building", minZoom = 13) {
                    paint("fill-color" to colors.building, "fill-outline-color" to colors.buildingOutline)
                },
            )
            add(
                road("path", colors.path, listOf("path"), minZoom = 14, width = zoomRamp(1.2, 14 to 1, 20 to 4)) {
                    put("line-dasharray", JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(1.5))))
                },
            )
            add(
                road(
                    "rail",
                    colors.rail,
                    listOf("rail", "transit"),
                    minZoom = 13,
                    width = zoomRamp(1.3, 13 to 1, 20 to 5),
                ),
            )
            add(
                road(
                    "road-minor",
                    colors.minorRoad,
                    listOf("minor", "service", "track"),
                    minZoom = 12,
                    width = zoomRamp(1.55, 13 to 1.6, 20 to 20),
                ),
            )
            add(
                road(
                    "road-major",
                    colors.majorRoad,
                    listOf("primary", "secondary", "tertiary", "trunk", "motorway"),
                    minZoom = 8,
                    width = zoomRamp(1.3, 10 to 1.5, 20 to 22),
                ),
            )
            if (colors.label != null) {
                add(
                    layer("street-names", "symbol", "transportation_name", minZoom = 14) {
                        layout(
                            "symbol-placement" to "line",
                            "text-field" to get("name"),
                            "text-font" to JsonArray(MapStyle.FONTS.map(::JsonPrimitive)),
                            "text-size" to zoomRamp(1.0, 14 to 11, 18 to 14),
                            "text-rotation-alignment" to "map",
                        )
                        paint(
                            "text-color" to colors.label,
                            "text-halo-color" to colors.background,
                            "text-halo-width" to 1.5,
                        )
                    },
                )
                add(
                    layer("place-names", "symbol", "place", minZoom = 11, maxZoom = 16) {
                        filter(match(get("class"), listOf("city", "town", "village", "suburb", "quarter"), true, false))
                        layout(
                            "text-field" to get("name"),
                            "text-font" to JsonArray(MapStyle.FONTS.map(::JsonPrimitive)),
                            "text-size" to zoomRamp(1.0, 11 to 12, 15 to 15),
                            "text-max-width" to 8,
                        )
                        paint(
                            "text-color" to colors.label,
                            "text-halo-color" to colors.background,
                            "text-halo-width" to 1.5,
                        )
                    },
                )
            }
        }
    }

    /** Streets of [classes], tunnels left out: they are under the ground the game is played on. */
    private fun road(
        id: String,
        color: String,
        classes: List<String>,
        minZoom: Int,
        width: JsonElement,
        extraPaint: JsonObjectBuilder.() -> Unit = {},
    ): JsonObject = layer(id, "line", "transportation", minZoom = minZoom) {
        filter(expr("all", match(get("class"), classes, true, false), expr("!=", get("brunnel"), "tunnel")))
        layout("line-cap" to "round", "line-join" to "round")
        put(
            "paint",
            buildJsonObject {
                put("line-color", color)
                put("line-width", width)
                extraPaint()
            },
        )
    }

    private class StreetColors(
        val background: String,
        val park: String,
        val water: String,
        val building: String,
        val buildingOutline: String,
        val minorRoad: String,
        val majorRoad: String,
        val path: String,
        val rail: String,
        /** Street and place names; null: none. */
        val label: String?,
    )
}

private fun layer(
    id: String,
    type: String,
    sourceLayer: String? = null,
    minZoom: Int? = null,
    maxZoom: Int? = null,
    body: JsonObjectBuilder.() -> Unit,
): JsonObject = buildJsonObject {
    put("id", id)
    put("type", type)
    if (sourceLayer != null) {
        put("source", MapStyles.SOURCE)
        put("source-layer", sourceLayer)
    }
    minZoom?.let { put("minzoom", it) }
    maxZoom?.let { put("maxzoom", it) }
    body()
}

private fun JsonObjectBuilder.filter(expression: JsonElement) {
    put("filter", expression)
}

private fun JsonObjectBuilder.paint(vararg properties: Pair<String, Any>) {
    put("paint", properties(properties))
}

private fun JsonObjectBuilder.layout(vararg properties: Pair<String, Any>) {
    put("layout", properties(properties))
}

private fun properties(properties: Array<out Pair<String, Any>>): JsonObject =
    JsonObject(properties.associate { (name, value) -> name to json(value) })

/** `["interpolate", ["exponential", base], ["zoom"], zoom, value, …]`. */
private fun zoomRamp(base: Double, vararg stops: Pair<Int, Number>): JsonElement = expr(
    "interpolate",
    expr("exponential", base),
    expr("zoom"),
    *stops.flatMap { (zoom, value) -> listOf(zoom, value) }.toTypedArray(),
)

private fun get(property: String): JsonElement = expr("get", property)

/** `["match", input, [labels], matched, otherwise]`. */
private fun match(input: JsonElement, labels: List<String>, matched: Any, otherwise: Any): JsonElement =
    expr("match", input, JsonArray(labels.map(::JsonPrimitive)), matched, otherwise)

private fun expr(vararg parts: Any): JsonArray = JsonArray(parts.map(::json))

private fun json(value: Any): JsonElement = when (value) {
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    else -> error("Not a style value: $value")
}
