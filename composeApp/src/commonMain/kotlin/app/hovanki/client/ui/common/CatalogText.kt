package app.hovanki.client.ui.common

import androidx.compose.runtime.Composable
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.band_close
import app.hovanki.client.resources.band_far
import app.hovanki.client.resources.band_near
import app.hovanki.client.resources.board_kind_checkpoint_geo
import app.hovanki.client.resources.board_kind_checkpoint_scan
import app.hovanki.client.resources.board_kind_pickup
import app.hovanki.client.resources.board_kind_quest_point
import app.hovanki.client.resources.hud_radar_burning
import app.hovanki.client.resources.hud_radar_hot
import app.hovanki.client.resources.hud_radar_none
import app.hovanki.client.resources.hud_radar_warm
import app.hovanki.client.resources.perk_decoy
import app.hovanki.client.resources.perk_decoy_text
import app.hovanki.client.resources.perk_direction
import app.hovanki.client.resources.perk_direction_text
import app.hovanki.client.resources.perk_erase_trail
import app.hovanki.client.resources.perk_erase_trail_text
import app.hovanki.client.resources.perk_fresh_trail
import app.hovanki.client.resources.perk_fresh_trail_text
import app.hovanki.client.resources.perk_invisible
import app.hovanki.client.resources.perk_invisible_text
import app.hovanki.client.resources.perk_radius
import app.hovanki.client.resources.perk_radius_text
import app.hovanki.client.resources.perk_sense
import app.hovanki.client.resources.perk_sense_text
import app.hovanki.client.resources.perk_spotlight
import app.hovanki.client.resources.perk_spotlight_text
import app.hovanki.client.resources.quest_after_glow
import app.hovanki.client.resources.quest_after_glow_text
import app.hovanki.client.resources.quest_beater
import app.hovanki.client.resources.quest_beater_text
import app.hovanki.client.resources.quest_custom
import app.hovanki.client.resources.quest_first_catch
import app.hovanki.client.resources.quest_first_catch_text
import app.hovanki.client.resources.quest_for_all
import app.hovanki.client.resources.quest_for_hiders
import app.hovanki.client.resources.quest_for_seekers
import app.hovanki.client.resources.quest_freeze
import app.hovanki.client.resources.quest_freeze_text
import app.hovanki.client.resources.quest_meeting
import app.hovanki.client.resources.quest_meeting_text
import app.hovanki.client.resources.quest_on_the_trail
import app.hovanki.client.resources.quest_on_the_trail_text
import app.hovanki.client.resources.quest_relocate
import app.hovanki.client.resources.quest_relocate_text
import app.hovanki.client.resources.quest_shadow
import app.hovanki.client.resources.quest_shadow_text
import app.hovanki.client.resources.quest_split_up
import app.hovanki.client.resources.quest_split_up_text
import app.hovanki.client.resources.quest_sprint
import app.hovanki.client.resources.quest_sprint_text
import app.hovanki.client.resources.quest_spy
import app.hovanki.client.resources.quest_spy_text
import app.hovanki.client.resources.quest_sweep
import app.hovanki.client.resources.quest_sweep_text
import app.hovanki.client.resources.sector_e
import app.hovanki.client.resources.sector_n
import app.hovanki.client.resources.sector_ne
import app.hovanki.client.resources.sector_nw
import app.hovanki.client.resources.sector_s
import app.hovanki.client.resources.sector_se
import app.hovanki.client.resources.sector_sw
import app.hovanki.client.resources.sector_w
import app.hovanki.shared.protocol.Audience
import app.hovanki.shared.protocol.DistanceBand
import app.hovanki.shared.protocol.ItemKind
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.QuestKind
import app.hovanki.shared.protocol.RadarBand
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// The catalogs of docs/adr/0013-quests-sparks-and-sensors.md and the radar's words in the player's language.

@Composable
fun questTitle(kind: QuestKind): String = stringResource(
    when (kind) {
        QuestKind.RELOCATE -> Res.string.quest_relocate
        QuestKind.AFTER_GLOW -> Res.string.quest_after_glow
        QuestKind.FREEZE -> Res.string.quest_freeze
        QuestKind.SPRINT -> Res.string.quest_sprint
        QuestKind.SPY -> Res.string.quest_spy
        QuestKind.MEETING -> Res.string.quest_meeting
        QuestKind.SHADOW -> Res.string.quest_shadow
        QuestKind.SWEEP -> Res.string.quest_sweep
        QuestKind.BEATER -> Res.string.quest_beater
        QuestKind.ON_THE_TRAIL -> Res.string.quest_on_the_trail
        QuestKind.SPLIT_UP -> Res.string.quest_split_up
        QuestKind.FIRST_CATCH -> Res.string.quest_first_catch
        QuestKind.CUSTOM -> Res.string.quest_custom
    },
)

/** What a catalog quest asks for; null for the host's own, which are in the host's words. */
@Composable
fun questText(kind: QuestKind): String? {
    val resource: StringResource = when (kind) {
        QuestKind.RELOCATE -> Res.string.quest_relocate_text
        QuestKind.AFTER_GLOW -> Res.string.quest_after_glow_text
        QuestKind.FREEZE -> Res.string.quest_freeze_text
        QuestKind.SPRINT -> Res.string.quest_sprint_text
        QuestKind.SPY -> Res.string.quest_spy_text
        QuestKind.MEETING -> Res.string.quest_meeting_text
        QuestKind.SHADOW -> Res.string.quest_shadow_text
        QuestKind.SWEEP -> Res.string.quest_sweep_text
        QuestKind.BEATER -> Res.string.quest_beater_text
        QuestKind.ON_THE_TRAIL -> Res.string.quest_on_the_trail_text
        QuestKind.SPLIT_UP -> Res.string.quest_split_up_text
        QuestKind.FIRST_CATCH -> Res.string.quest_first_catch_text
        QuestKind.CUSTOM -> return null
    }
    return stringResource(resource)
}

@Composable
fun perkTitle(perk: PerkKind): String = stringResource(
    when (perk) {
        PerkKind.ERASE_TRAIL -> Res.string.perk_erase_trail
        PerkKind.DECOY -> Res.string.perk_decoy
        PerkKind.INVISIBLE -> Res.string.perk_invisible
        PerkKind.SENSE -> Res.string.perk_sense
        PerkKind.SPOTLIGHT -> Res.string.perk_spotlight
        PerkKind.FRESH_TRAIL -> Res.string.perk_fresh_trail
        PerkKind.DIRECTION -> Res.string.perk_direction
        PerkKind.RADIUS -> Res.string.perk_radius
    },
)

@Composable
fun perkText(perk: PerkKind): String = stringResource(
    when (perk) {
        PerkKind.ERASE_TRAIL -> Res.string.perk_erase_trail_text
        PerkKind.DECOY -> Res.string.perk_decoy_text
        PerkKind.INVISIBLE -> Res.string.perk_invisible_text
        PerkKind.SENSE -> Res.string.perk_sense_text
        PerkKind.SPOTLIGHT -> Res.string.perk_spotlight_text
        PerkKind.FRESH_TRAIL -> Res.string.perk_fresh_trail_text
        PerkKind.DIRECTION -> Res.string.perk_direction_text
        PerkKind.RADIUS -> Res.string.perk_radius_text
    },
)

@Composable
fun itemKindTitle(kind: ItemKind): String = stringResource(
    when (kind) {
        ItemKind.QUEST_POINT -> Res.string.board_kind_quest_point
        ItemKind.CHECKPOINT_GEO -> Res.string.board_kind_checkpoint_geo
        ItemKind.CHECKPOINT_SCAN -> Res.string.board_kind_checkpoint_scan
        ItemKind.PICKUP -> Res.string.board_kind_pickup
    },
)

/** «for everybody», «for the hiders», «for the seekers». */
@Composable
fun audienceTitle(audience: Audience): String = stringResource(
    when (audience) {
        Audience.ALL -> Res.string.quest_for_all
        Audience.HIDERS -> Res.string.quest_for_hiders
        Audience.SEEKERS -> Res.string.quest_for_seekers
    },
)

@Composable
fun bandTitle(band: RadarBand): String = stringResource(
    when (band) {
        RadarBand.NONE -> Res.string.hud_radar_none
        RadarBand.WARM -> Res.string.hud_radar_warm
        RadarBand.HOT -> Res.string.hud_radar_hot
        RadarBand.BURNING -> Res.string.hud_radar_burning
    },
)

/** A compass sector as `Sectors.compassSector` numbers them: 0 north, clockwise. */
@Composable
fun sectorTitle(sector: Int): String = stringResource(
    when (sector.mod(COMPASS_SECTORS)) {
        0 -> Res.string.sector_n
        1 -> Res.string.sector_ne
        2 -> Res.string.sector_e
        3 -> Res.string.sector_se
        4 -> Res.string.sector_s
        5 -> Res.string.sector_sw
        6 -> Res.string.sector_w
        else -> Res.string.sector_nw
    },
)

@Composable
fun distanceBandTitle(band: DistanceBand): String = stringResource(
    when (band) {
        DistanceBand.NEAR -> Res.string.band_near
        DistanceBand.CLOSE -> Res.string.band_close
        DistanceBand.FAR -> Res.string.band_far
    },
)

private const val COMPASS_SECTORS = 8
