package app.hovanki.client.ui.settings

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.BoardItem
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ItemKind
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.rules.GameSetup
import app.hovanki.shared.rules.SettingsLimits
import app.hovanki.shared.rules.boundingCircle
import kotlin.math.roundToInt

/** The settings panel's tabs (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 2.1). */
enum class SettingsTab { ZONE, TIME, MORE }

/** What a preview on the settings' map plays: the zone's stages, or the whole game sped up. */
enum class SettingsPreview { SHRINK, GAME }

/** What the host changed so far, for «Changed: size, shape» above «Save». */
enum class ChangedPart { SIZE, SHAPE, SHRINK, PLACE, TIME, GLOW, FEATURES, SPECTATORS }

/** The parts of the setup that differ between the game's ([current] around [currentCenter]) and the draft. */
fun changedParts(
    current: GameSetup,
    currentCenter: GeoPoint,
    draft: GameSetup,
    draftCenter: GeoPoint,
): List<ChangedPart> = buildList {
    if (draft.radiusMeters != current.radiusMeters) add(ChangedPart.SIZE)
    if (draft.zoneShape != current.zoneShape) add(ChangedPart.SHAPE)
    if (draft.shrinks != current.shrinks) add(ChangedPart.SHRINK)
    if (draftCenter.distanceTo(currentCenter) >= MOVED_METERS) add(ChangedPart.PLACE)
    if (draft.hidingMinutes != current.hidingMinutes || draft.seekingMinutes != current.seekingMinutes) {
        add(ChangedPart.TIME)
    }
    if (draft.glowEveryMinutes != current.glowEveryMinutes || draft.glowForSeconds != current.glowForSeconds) {
        add(ChangedPart.GLOW)
    }
    if (draft.features != current.features || draft.quests != current.quests) add(ChangedPart.FEATURES)
    if (draft.openGame != current.openGame || draft.spectatorDelaySeconds != current.spectatorDelaySeconds) {
        add(ChangedPart.SPECTATORS)
    }
}

/** One line of «What changes», shown before a setup that touches the map is saved. */
sealed interface SettingsChange {
    data class ZoneSize(val fromMeters: Int, val toMeters: Int) : SettingsChange

    data class ZoneShapeTo(val shape: ZoneShape) : SettingsChange

    data class ZoneMoved(val meters: Int) : SettingsChange

    data class Shrinks(val on: Boolean) : SettingsChange

    /** The buildings and the blocks of the zone load again. */
    data object MapReloads : SettingsChange

    data class OpenBuildingsKept(val count: Int) : SettingsChange

    data class OpenBuildingsLost(val count: Int) : SettingsChange

    /** Points of the board the new zone leaves outside. */
    data class BoardOutside(val count: Int) : SettingsChange

    /** Points of the board whose extra goes off with the new setup: the server takes them away. */
    data class BoardRemoved(val count: Int) : SettingsChange

    data object EverybodySees : SettingsChange
}

/**
 * What saving [draft] over [current] does beyond the numbers on the screen
 * (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 2.3): a new zone loads its map again, open
 * buildings stay or close, points of the board end up outside or go with their extra. Empty when nothing like that
 * happens (only times, the glow, spectators): «Save» then sends right away. [allowedKinds]: the kinds of items a
 * setup's extras allow on the board.
 */
fun settingsChanges(
    current: GameSettings,
    draft: GameSettings,
    items: List<BoardItem>,
    allowedKinds: (GameFeatures) -> Collection<ItemKind>,
): List<SettingsChange> {
    val changes = mutableListOf<SettingsChange>()
    val from = current.zone.initial
    val to = draft.zone.initial
    val mapChanged = draft.zone != current.zone || draft.zoneShape != current.zoneShape
    if (to.radiusMeters.roundToInt() != from.radiusMeters.roundToInt()) {
        changes += SettingsChange.ZoneSize(from.radiusMeters.roundToInt(), to.radiusMeters.roundToInt())
    }
    if (draft.zoneShape != current.zoneShape) changes += SettingsChange.ZoneShapeTo(draft.zoneShape)
    val moved = to.center.distanceTo(from.center)
    if (moved >= MOVED_METERS) changes += SettingsChange.ZoneMoved(moved.roundToInt())
    if (draft.zone.stages.isEmpty() != current.zone.stages.isEmpty()) {
        changes += SettingsChange.Shrinks(on = draft.zone.stages.isNotEmpty())
    }
    if (mapChanged) {
        changes += SettingsChange.MapReloads
        val (kept, lost) = current.openBuildings.orEmpty().partition { SettingsLimits.isNearZone(it, draft.zone) }
        if (kept.isNotEmpty()) changes += SettingsChange.OpenBuildingsKept(kept.size)
        if (lost.isNotEmpty()) changes += SettingsChange.OpenBuildingsLost(lost.size)
    }
    val allowed = allowedKinds(draft.features).toSet()
    val removed = items.count { it.kind !in allowed }
    if (removed > 0) changes += SettingsChange.BoardRemoved(removed)
    if (mapChanged) {
        val area = draft.zone.boundingCircle()
        val outside = items.count { it.kind in allowed && it.point.distanceTo(area.center) > area.radiusMeters }
        if (outside > 0) changes += SettingsChange.BoardOutside(outside)
    }
    if (changes.isNotEmpty()) changes += SettingsChange.EverybodySees
    return changes
}

/** A center closer than this is the same place: a pin never lands on the very meter. */
private const val MOVED_METERS = 5.0
