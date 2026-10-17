package app.hovanki.server.game

import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.Passage
import kotlin.math.roundToInt

/** A building as the game's `BuildingMap` keeps it and gives it back to the players: every point to 10⁻⁷ of a degree. */
fun BuildingArea.asKept() = BuildingArea(outline.pointsAsKept(), holes.map { it.pointsAsKept() })

@JvmName("areasAsKept")
fun List<BuildingArea>.asKept() = map { it.asKept() }

fun Passage.asKept() = Passage(path.pointsAsKept(), widthMeters)

@JvmName("passagesAsKept")
fun List<Passage>.asKept() = map { it.asKept() }

private fun List<GeoPoint>.pointsAsKept() =
    map { GeoPoint((it.lat * 1e7).roundToInt() / 1e7, (it.lon * 1e7).roundToInt() / 1e7) }
