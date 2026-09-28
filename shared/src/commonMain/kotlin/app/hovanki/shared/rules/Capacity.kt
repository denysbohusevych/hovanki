package app.hovanki.shared.rules

import app.hovanki.shared.protocol.AreaNorms
import app.hovanki.shared.protocol.CapacityState
import app.hovanki.shared.protocol.TerrainAreas
import app.hovanki.shared.protocol.ZoneCapacity
import kotlin.math.floor

/**
 * How many players a zone fits (docs/adr/0010-big-games.md): each kind of ground divided by what one player needs of
 * it, added up. A zone of woods, blocks and a field counts each part by its own norm.
 */
object Capacity {
    /** Open ground on more than this share of the playing area: few places to hide, the lobby says so. */
    const val FEW_COVERS_SHARE = 0.5

    /** The players [areas] fit with [norms], rounded down. */
    fun players(areas: TerrainAreas, norms: AreaNorms): Int = floor(
        areas.denseSquareMeters.toDouble() / norms.denseSquareMeters +
            areas.forestSquareMeters.toDouble() / norms.forestSquareMeters +
            areas.mixedSquareMeters.toDouble() / norms.mixedSquareMeters +
            areas.openSquareMeters.toDouble() / norms.openSquareMeters,
    ).toInt()

    /** Most of the playing area is open ground. */
    fun fewCovers(areas: TerrainAreas): Boolean {
        val playable = areas.playableSquareMeters
        return playable > 0 && areas.openSquareMeters > playable * FEW_COVERS_SHARE
    }

    /** The zone of [capacity] is known to fit fewer than [players]. */
    fun isCrowded(capacity: ZoneCapacity?, players: Int): Boolean {
        val fits = capacity?.players ?: return false
        return capacity.state == CapacityState.READY && players > fits
    }

    /**
     * What the lobby warns about: too many players for the zone, or few places to hide in it. Nothing once the host
     * chose to play anyway ([ZoneCapacity.accepted]).
     */
    fun needsWarning(capacity: ZoneCapacity?, players: Int): Boolean {
        if (capacity == null || capacity.accepted || capacity.state != CapacityState.READY) return false
        return isCrowded(capacity, players) || capacity.fewCovers
    }

    /** Whether all norms are sensible: positive, at most a square kilometer per player. */
    fun isValid(norms: AreaNorms): Boolean = listOf(
        norms.denseSquareMeters,
        norms.forestSquareMeters,
        norms.mixedSquareMeters,
        norms.openSquareMeters,
    ).all { it in MIN_NORM_SQUARE_METERS..MAX_NORM_SQUARE_METERS }

    const val MIN_NORM_SQUARE_METERS = 10
    const val MAX_NORM_SQUARE_METERS = 1_000_000
}
