package app.hovanki.client.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_dismiss
import app.hovanki.client.resources.changes_back
import app.hovanki.client.resources.changes_board_outside
import app.hovanki.client.resources.changes_board_outside_caption
import app.hovanki.client.resources.changes_board_removed
import app.hovanki.client.resources.changes_everybody
import app.hovanki.client.resources.changes_open_kept
import app.hovanki.client.resources.changes_open_lost
import app.hovanki.client.resources.changes_open_lost_caption
import app.hovanki.client.resources.changes_reload
import app.hovanki.client.resources.changes_reload_caption
import app.hovanki.client.resources.changes_shrink_off
import app.hovanki.client.resources.changes_shrink_on
import app.hovanki.client.resources.changes_title
import app.hovanki.client.resources.changes_zone_circle
import app.hovanki.client.resources.changes_zone_moved
import app.hovanki.client.resources.changes_zone_size
import app.hovanki.client.resources.changes_zone_streets
import app.hovanki.client.resources.explain_ok
import app.hovanki.client.resources.explain_open
import app.hovanki.client.resources.feature_activity
import app.hovanki.client.resources.feature_activity_caption
import app.hovanki.client.resources.feature_checkpoints
import app.hovanki.client.resources.feature_checkpoints_caption
import app.hovanki.client.resources.feature_perks
import app.hovanki.client.resources.feature_perks_caption
import app.hovanki.client.resources.feature_pickups
import app.hovanki.client.resources.feature_pickups_caption
import app.hovanki.client.resources.feature_pocket
import app.hovanki.client.resources.feature_pocket_caption
import app.hovanki.client.resources.feature_precision
import app.hovanki.client.resources.feature_precision_caption
import app.hovanki.client.resources.feature_proximity
import app.hovanki.client.resources.feature_proximity_caption
import app.hovanki.client.resources.feature_quests
import app.hovanki.client.resources.feature_quests_caption
import app.hovanki.client.resources.feature_radar
import app.hovanki.client.resources.feature_radar_caption
import app.hovanki.client.resources.feature_sense
import app.hovanki.client.resources.feature_sense_caption
import app.hovanki.client.resources.ic_bolt
import app.hovanki.client.resources.ic_building
import app.hovanki.client.resources.ic_clock
import app.hovanki.client.resources.ic_crosshair
import app.hovanki.client.resources.ic_eye
import app.hovanki.client.resources.ic_flag
import app.hovanki.client.resources.ic_friends
import app.hovanki.client.resources.ic_location
import app.hovanki.client.resources.ic_near
import app.hovanki.client.resources.ic_pocket
import app.hovanki.client.resources.ic_qr
import app.hovanki.client.resources.ic_radar
import app.hovanki.client.resources.ic_rings
import app.hovanki.client.resources.ic_run
import app.hovanki.client.resources.ic_vibrate
import app.hovanki.client.resources.ic_warning
import app.hovanki.client.resources.quest_needs_glow
import app.hovanki.client.resources.quest_needs_radar
import app.hovanki.client.resources.settings_buildings_count
import app.hovanki.client.resources.settings_buildings_loading
import app.hovanki.client.resources.settings_buildings_off
import app.hovanki.client.resources.settings_buildings_open_caption
import app.hovanki.client.resources.settings_buildings_open_hint
import app.hovanki.client.resources.settings_buildings_save_first
import app.hovanki.client.resources.settings_change_features
import app.hovanki.client.resources.settings_change_glow
import app.hovanki.client.resources.settings_change_place
import app.hovanki.client.resources.settings_change_shape
import app.hovanki.client.resources.settings_change_shrink
import app.hovanki.client.resources.settings_change_size
import app.hovanki.client.resources.settings_change_spectators
import app.hovanki.client.resources.settings_change_time
import app.hovanki.client.resources.settings_changed
import app.hovanki.client.resources.settings_fair_only
import app.hovanki.client.resources.settings_features
import app.hovanki.client.resources.settings_features_caption
import app.hovanki.client.resources.settings_features_none
import app.hovanki.client.resources.settings_glow
import app.hovanki.client.resources.settings_glow_caption
import app.hovanki.client.resources.settings_glow_count
import app.hovanki.client.resources.settings_glow_every_short
import app.hovanki.client.resources.settings_glow_for_short
import app.hovanki.client.resources.settings_glow_off_caption
import app.hovanki.client.resources.settings_hiding_short
import app.hovanki.client.resources.settings_legend_glow
import app.hovanki.client.resources.settings_legend_shrink
import app.hovanki.client.resources.settings_minutes
import app.hovanki.client.resources.settings_minutes_short
import app.hovanki.client.resources.settings_needs_radar
import app.hovanki.client.resources.settings_no_glow
import app.hovanki.client.resources.settings_open_game
import app.hovanki.client.resources.settings_open_game_caption
import app.hovanki.client.resources.settings_precision_for_hiders
import app.hovanki.client.resources.settings_quests_pick
import app.hovanki.client.resources.settings_radar_required
import app.hovanki.client.resources.settings_save
import app.hovanki.client.resources.settings_seconds
import app.hovanki.client.resources.settings_seeking_short
import app.hovanki.client.resources.settings_shape
import app.hovanki.client.resources.settings_shape_circle
import app.hovanki.client.resources.settings_shape_circle_caption
import app.hovanki.client.resources.settings_shape_streets
import app.hovanki.client.resources.settings_shape_streets_caption
import app.hovanki.client.resources.settings_shrinks
import app.hovanki.client.resources.settings_shrinks_caption
import app.hovanki.client.resources.settings_shrinks_off_caption
import app.hovanki.client.resources.settings_size
import app.hovanki.client.resources.settings_size_meta
import app.hovanki.client.resources.settings_size_players
import app.hovanki.client.resources.settings_tab_more
import app.hovanki.client.resources.settings_tab_time
import app.hovanki.client.resources.settings_tab_zone
import app.hovanki.client.resources.settings_timeline
import app.hovanki.client.resources.settings_unchanged
import app.hovanki.client.resources.sparks_count
import app.hovanki.client.resources.working
import app.hovanki.client.ui.common.Banner
import app.hovanki.client.ui.common.BusyRow
import app.hovanki.client.ui.common.CapsText
import app.hovanki.client.ui.common.PickRow
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.PopSurface
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.SystemBackHandler
import app.hovanki.client.ui.common.audienceTitle
import app.hovanki.client.ui.common.describe
import app.hovanki.client.ui.common.questTitle
import app.hovanki.client.ui.lobby.LobbyEvent
import app.hovanki.client.ui.lobby.LobbyUiState
import app.hovanki.client.ui.lobby.SettingsPanelState
import app.hovanki.client.ui.lobby.SwitchRow
import app.hovanki.client.ui.lobby.spectatorDelayText
import app.hovanki.client.ui.theme.Hovanki
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.FeatureMode
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.rules.GameSetup
import app.hovanki.shared.rules.Glow
import app.hovanki.shared.rules.QuestCatalog
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.roundToInt

// ---- Tabs ----

@Composable
internal fun SettingsTabs(selected: SettingsTab, onPick: (SettingsTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Sand)
            .border(2.dp, Palette.Ink, RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (tab in SettingsTab.entries) {
            val isSelected = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) Palette.Green else Color.Transparent)
                    .border(if (isSelected) 2.dp else 0.dp, Palette.Ink, RoundedCornerShape(12.dp))
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onPick(tab) })
                    .testTag(TestTags.settingsTab(tab.name)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        when (tab) {
                            SettingsTab.ZONE -> Res.string.settings_tab_zone
                            SettingsTab.TIME -> Res.string.settings_tab_time
                            SettingsTab.MORE -> Res.string.settings_tab_more
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) Palette.Ink else Palette.Ink2,
                )
            }
        }
    }
}

// ---- Zone ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZoneTab(state: LobbyUiState, settings: SettingsPanelState, onEvent: (LobbyEvent) -> Unit) {
    val setup = settings.setup
    val draft = settings.draft
    val edit = { changed: GameSetup -> onEvent(LobbyEvent.Settings.Edit(changed)) }
    val isSavedZone = draft.zone == state.zone.schedule && draft.zoneShape == state.zoneShape

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CapsText(stringResource(Res.string.settings_size), modifier = Modifier.weight(1f), color = Palette.Ink2)
            val hectares = (PI * setup.radiusMeters * setup.radiusMeters / SQUARE_METERS_PER_HECTARE).roundToInt()
            val capacity = state.capacity?.takeIf { isSavedZone }
            SecondaryText(
                if (capacity != null) {
                    pluralStringResource(Res.plurals.settings_size_players, capacity, hectares, capacity)
                } else {
                    stringResource(Res.string.settings_size_meta, hectares)
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = distanceText(setup.radiusMeters.toDouble()),
                style = Hovanki.text.code.copy(fontSize = 36.sp, lineHeight = 40.sp),
                maxLines = 1,
                modifier = Modifier.widthIn(min = 132.dp).testTag(TestTags.settingValue("radius")),
            )
            Slider(
                value = setup.radiusMeters.toFloat(),
                onValueChange = { value ->
                    val step = GameSetup.RADIUS_STEP_METERS
                    edit(setup.copy(radiusMeters = (value / step).roundToInt() * step))
                },
                valueRange = GameSetup.RADIUS_METERS.first.toFloat()..GameSetup.RADIUS_METERS.last.toFloat(),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Palette.Green)
                            .border(2.5.dp, Palette.Ink, CircleShape),
                    )
                },
                track = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Palette.Ink),
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingLabel(stringResource(Res.string.settings_shape), Explainer.SHAPE) {
            onEvent(LobbyEvent.Settings.ShowHelp(Explainer.SHAPE))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ShapeCard(
                title = stringResource(Res.string.settings_shape_circle),
                caption = stringResource(Res.string.settings_shape_circle_caption),
                shape = ZoneShape.CIRCLE,
                selected = setup.zoneShape == ZoneShape.CIRCLE,
                onClick = { edit(setup.copy(zoneShape = ZoneShape.CIRCLE)) },
                modifier = Modifier.weight(1f).testTag(TestTags.SETTINGS_SHAPE_CIRCLE),
            )
            ShapeCard(
                title = stringResource(Res.string.settings_shape_streets),
                caption = stringResource(Res.string.settings_shape_streets_caption),
                shape = ZoneShape.STREETS,
                selected = setup.zoneShape == ZoneShape.STREETS,
                onClick = { edit(setup.copy(zoneShape = ZoneShape.STREETS)) },
                modifier = Modifier.weight(1f).testTag(TestTags.SETTINGS_SHAPE_STREETS),
            )
        }
    }

    ToggleCard(
        icon = Res.drawable.ic_rings,
        title = stringResource(Res.string.settings_shrinks),
        caption = stringResource(
            if (setup.shrinks) Res.string.settings_shrinks_caption else Res.string.settings_shrinks_off_caption,
        ),
        checked = setup.shrinks,
        onCheckedChange = { edit(setup.copy(shrinks = it)) },
        tag = TestTags.SETTINGS_SHRINKS,
        explainer = Explainer.SHRINK,
        onHelp = { onEvent(LobbyEvent.Settings.ShowHelp(Explainer.SHRINK)) },
    )

    val buildings = state.buildings?.takeIf { state.buildingsState == BuildingsState.READY }
    ActionCard(
        icon = Res.drawable.ic_building,
        title = when {
            buildings != null -> pluralStringResource(
                Res.plurals.settings_buildings_count,
                buildings.buildings.size,
                buildings.buildings.size,
            )

            state.buildingsState == BuildingsState.UNAVAILABLE -> stringResource(Res.string.settings_buildings_off)

            else -> stringResource(Res.string.settings_buildings_loading)
        },
        caption = when {
            !isSavedZone -> stringResource(Res.string.settings_buildings_save_first)

            (buildings?.open?.size ?: 0) > 0 -> stringResource(
                Res.string.settings_buildings_open_caption,
                buildings?.open?.size ?: 0,
            )

            else -> stringResource(Res.string.settings_buildings_open_hint)
        },
        enabled = buildings != null && isSavedZone,
        onClick = { onEvent(LobbyEvent.Buildings.Open) },
        tag = TestTags.SETTINGS_BUILDINGS,
        explainer = Explainer.BUILDINGS,
        onHelp = { onEvent(LobbyEvent.Settings.ShowHelp(Explainer.BUILDINGS)) },
    )
}

// ---- Time ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimeTab(settings: SettingsPanelState, onEvent: (LobbyEvent) -> Unit, elapsed: State<Long?>) {
    val setup = settings.setup
    val draft = settings.draft
    val edit = { changed: GameSetup -> onEvent(LobbyEvent.Settings.Edit(changed)) }
    val total = setup.hidingMinutes + setup.seekingMinutes

    PopCard(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CapsText(stringResource(Res.string.settings_timeline), modifier = Modifier.weight(1f), color = Palette.Ink2)
            Text(
                text = stringResource(Res.string.settings_minutes, total),
                style = Hovanki.text.code.copy(fontSize = 18.sp, lineHeight = 22.sp),
            )
        }
        val playhead = elapsed.value?.takeIf { settings.preview == SettingsPreview.GAME }?.let { played ->
            played.toFloat() / ((draft.hidingSeconds + draft.seekingSeconds) * 1000f)
        }
        GameTimeline(draft, playhead)
        val glows = remember(draft) { glowStarts(draft).size }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend(Palette.Pink, stringResource(Res.string.settings_legend_shrink), diamond = false)
            Legend(Palette.Green, stringResource(Res.string.settings_legend_glow), diamond = true)
            Spacer(Modifier.weight(1f))
            SecondaryText(
                if (Glow.isOn(draft)) {
                    stringResource(Res.string.settings_glow_count, glows)
                } else {
                    stringResource(Res.string.settings_no_glow)
                },
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StepperTile(
            label = stringResource(Res.string.settings_hiding_short),
            labelColor = Palette.Ink,
            name = "hiding",
            value = stringResource(Res.string.settings_minutes, setup.hidingMinutes),
            onMinus = { edit(setup.copy(hidingMinutes = setup.hidingMinutes - 1)) },
            onPlus = { edit(setup.copy(hidingMinutes = setup.hidingMinutes + 1)) },
            canMinus = setup.hidingMinutes > GameSetup.HIDING_MINUTES.first,
            canPlus = setup.hidingMinutes < GameSetup.HIDING_MINUTES.last,
            modifier = Modifier.weight(1f),
        )
        StepperTile(
            label = stringResource(Res.string.settings_seeking_short),
            labelColor = Palette.Ink,
            name = "seeking",
            value = stringResource(Res.string.settings_minutes, setup.seekingMinutes),
            onMinus = { edit(setup.copy(seekingMinutes = setup.seekingMinutes - GameSetup.SEEKING_STEP_MINUTES)) },
            onPlus = { edit(setup.copy(seekingMinutes = setup.seekingMinutes + GameSetup.SEEKING_STEP_MINUTES)) },
            canMinus = setup.seekingMinutes > GameSetup.SEEKING_MINUTES.first,
            canPlus = setup.seekingMinutes < GameSetup.SEEKING_MINUTES.last,
            modifier = Modifier.weight(1f),
        )
    }

    val glowOn = setup.glowEveryMinutes > 0
    ToggleCard(
        icon = Res.drawable.ic_eye,
        title = stringResource(Res.string.settings_glow),
        caption = stringResource(
            if (glowOn) Res.string.settings_glow_caption else Res.string.settings_glow_off_caption,
        ),
        checked = glowOn,
        onCheckedChange = { on -> edit(setup.copy(glowEveryMinutes = if (on) GameSetup().glowEveryMinutes else 0)) },
        tag = TestTags.SETTINGS_GLOW,
        explainer = Explainer.GLOW,
        onHelp = { onEvent(LobbyEvent.Settings.ShowHelp(Explainer.GLOW)) },
    ) {
        if (glowOn) {
            ChipRow(
                label = stringResource(Res.string.settings_glow_every_short),
                values = (GLOW_EVERY + setup.glowEveryMinutes).distinct().sorted(),
                selected = setup.glowEveryMinutes,
                text = { it.toString() },
                unit = stringResource(Res.string.settings_minutes_short),
                tagName = "glow_every",
                onPick = { edit(setup.copy(glowEveryMinutes = it)) },
            )
            ChipRow(
                label = stringResource(Res.string.settings_glow_for_short),
                values = (GLOW_FOR + setup.glowForSeconds).distinct().sorted()
                    .filter { it < setup.glowEveryMinutes * SECONDS_PER_MINUTE },
                selected = setup.glowForSeconds,
                text = { stringResource(Res.string.settings_seconds, it) },
                unit = null,
                tagName = "glow_for",
                onPick = { edit(setup.copy(glowForSeconds = it)) },
            )
        }
    }
}

/** The whole game on one bar: hiding, the search, where the zone starts to shrink (▲) and where the hiders glow (◆). */
@Composable
private fun GameTimeline(draft: GameSettings, playhead: Float?) {
    val hiding = draft.hidingSeconds.toFloat()
    val total = (draft.hidingSeconds + draft.seekingSeconds).toFloat().coerceAtLeast(1f)
    val shrinks = remember(draft) { shrinkStarts(draft) }
    val glows = remember(draft) { glowStarts(draft) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(36.dp)) {
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(12.dp))
                    .border(2.5.dp, Palette.Ink, RoundedCornerShape(12.dp)),
            ) {
                Box(
                    modifier = Modifier.weight(hiding.coerceAtLeast(1f)).fillMaxSize().background(Palette.Hider),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(Res.string.settings_hiding_short),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Box(
                    modifier = Modifier.weight(draft.seekingSeconds.toFloat()).fillMaxSize().background(Palette.Seeker),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(Res.string.settings_seeking_short),
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.Ink,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
            if (playhead != null) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val x = size.width * playhead.coerceIn(0f, 1f)
                    drawLine(Palette.Ink, Offset(x, -4.dp.toPx()), Offset(x, size.height + 4.dp.toPx()), 3.dp.toPx())
                    drawCircle(Palette.Ink, 5.dp.toPx(), Offset(x, -4.dp.toPx()))
                }
            }
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(14.dp)) {
            val half = 6.dp.toPx()
            val border = 1.5.dp.toPx()
            shrinks.forEach { seconds ->
                val x = size.width * (hiding + seconds) / total
                val mark = Path().apply {
                    moveTo(x, 1f)
                    lineTo(x + half, size.height - 1f)
                    lineTo(x - half, size.height - 1f)
                    close()
                }
                drawPath(mark, Palette.Pink)
                drawPath(mark, Palette.Ink, style = Stroke(border, join = StrokeJoin.Round))
            }
            glows.forEach { seconds ->
                val x = size.width * (hiding + seconds) / total
                val y = size.height / 2
                val mark = Path().apply {
                    moveTo(x, y - half)
                    lineTo(x + half, y)
                    lineTo(x, y + half)
                    lineTo(x - half, y)
                    close()
                }
                drawPath(mark, Palette.Green)
                drawPath(mark, Palette.Ink, style = Stroke(border, join = StrokeJoin.Round))
            }
        }
    }
}

/** Seconds into the search when each shrink begins. */
private fun shrinkStarts(draft: GameSettings): List<Float> {
    var at = 0
    return draft.zone.stages.map { stage ->
        val start = at + stage.holdSeconds
        at = start + stage.shrinkSeconds
        start.toFloat()
    }
}

/** Seconds into the search when each glow begins. */
private fun glowStarts(draft: GameSettings): List<Float> {
    if (!Glow.isOn(draft)) return emptyList()
    return (1..draft.seekingSeconds / draft.glowEverySeconds)
        .map { it * draft.glowEverySeconds }
        .filter { it < draft.seekingSeconds }
        .map { it.toFloat() }
}

@Composable
private fun Legend(color: Color, text: String, diamond: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(modifier = Modifier.size(10.dp)) {
            val mark = Path().apply {
                if (diamond) {
                    moveTo(size.width / 2, 0f)
                    lineTo(size.width, size.height / 2)
                    lineTo(size.width / 2, size.height)
                    lineTo(0f, size.height / 2)
                } else {
                    moveTo(size.width / 2, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                }
                close()
            }
            drawPath(mark, color)
            drawPath(mark, Palette.Ink, style = Stroke(1.5.dp.toPx()))
        }
        SecondaryText(text)
    }
}

@Composable
private fun StepperTile(
    label: String,
    labelColor: Color,
    name: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    canMinus: Boolean,
    canPlus: Boolean,
    modifier: Modifier = Modifier,
) {
    PopCard(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CapsText(label, color = labelColor)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−", canMinus, onMinus, TestTags.settingMinus(name))
            Text(
                text = value,
                style = Hovanki.text.code.copy(fontSize = 20.sp, lineHeight = 24.sp),
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f).testTag(TestTags.settingValue(name)),
            )
            StepButton("+", canPlus, onPlus, TestTags.settingPlus(name))
        }
    }
}

// ---- More ----

/** One extra of the «More» tab: what it is, whether the host may turn it on, and how it is set. */
private class FeatureItem(
    val explainer: Explainer,
    val icon: DrawableResource,
    val name: StringResource,
    val caption: StringResource,
    val isOn: Boolean,
    val needsRadar: Boolean,
    val tag: String,
    val set: (Boolean) -> GameFeatures,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoreTab(state: LobbyUiState, settings: SettingsPanelState, onEvent: (LobbyEvent) -> Unit) {
    val setup = settings.setup
    val edit = { changed: GameSetup -> onEvent(LobbyEvent.Settings.Edit(changed)) }
    val focus = { explainer: Explainer -> onEvent(LobbyEvent.Settings.FocusExtra(explainer)) }

    ToggleCard(
        icon = Res.drawable.ic_eye,
        title = stringResource(Res.string.settings_open_game),
        caption = stringResource(Res.string.settings_open_game_caption),
        checked = setup.openGame,
        onCheckedChange = {
            edit(setup.copy(openGame = it))
            focus(Explainer.OPEN_GAME)
        },
        tag = TestTags.SETTINGS_OPEN_GAME,
        explainer = Explainer.OPEN_GAME,
        onHelp = { focus(Explainer.OPEN_GAME) },
    ) {
        if (setup.openGame) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (delay in GameSetup.SPECTATOR_DELAYS) {
                    Chip(
                        text = spectatorDelayText(delay),
                        selected = setup.spectatorDelaySeconds == delay,
                        onClick = { edit(setup.copy(spectatorDelaySeconds = delay)) },
                        modifier = Modifier.testTag(TestTags.settingsDelay(delay)),
                    )
                }
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        CapsText(stringResource(Res.string.settings_features), modifier = Modifier.weight(1f), color = Palette.Ink2)
        SecondaryText(stringResource(Res.string.settings_features_caption))
    }
    val enabled = state.enabledFeatures
    if (enabled.isEmpty()) {
        SecondaryText(stringResource(Res.string.settings_features_none))
        return
    }
    val features = setup.features
    val items = featureItems(features).filter { it.first in enabled }.map { it.second }
    val set = { changed: GameFeatures -> edit(setup.copy(features = changed)) }
    items.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pair.forEach { item ->
                val disabled = item.needsRadar && !features.hasRadar
                FeatureTile(
                    item = item,
                    checked = item.isOn && !disabled,
                    enabled = !disabled,
                    focused = settings.focusedExtra == item.explainer,
                    onFocus = { focus(item.explainer) },
                    onToggle = { on ->
                        set(item.set(on))
                        focus(item.explainer)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }

    // What a turned-on extra lets the host choose further.
    if (ServerFeature.RADAR in enabled && features.hasRadar) {
        SwitchRow(
            text = stringResource(Res.string.settings_radar_required),
            checked = features.radar == FeatureMode.REQUIRED,
            onCheckedChange = { on ->
                set(features.copy(radar = if (on) FeatureMode.REQUIRED else FeatureMode.OPTIONAL))
            },
            tag = TestTags.SETTINGS_RADAR_REQUIRED,
        )
    }
    if (ServerFeature.PRECISION_RADAR in enabled && features.precisionRadar) {
        SwitchRow(
            text = stringResource(Res.string.settings_precision_for_hiders),
            checked = features.precisionForHiders,
            onCheckedChange = { set(features.copy(precisionForHiders = it)) },
            tag = TestTags.SETTINGS_PRECISION_RADAR + "_hiders",
        )
        SwitchRow(
            text = stringResource(Res.string.settings_fair_only),
            checked = features.fairOnly,
            onCheckedChange = { set(features.copy(fairOnly = it)) },
            tag = TestTags.SETTINGS_PRECISION_RADAR + "_fair",
        )
    }
    if (ServerFeature.QUESTS in enabled && features.quests) {
        CapsText(stringResource(Res.string.settings_quests_pick), color = Palette.Ink2)
        QuestCatalog.pickable.forEach { kind ->
            val spec = QuestCatalog.spec(kind)
            val needsRadar = spec.needsRadar && !features.hasRadar
            val needsGlow = spec.needsGlow && setup.glowEveryMinutes <= 0
            val notes = listOfNotNull(
                audienceTitle(spec.audience),
                stringResource(Res.string.sparks_count, spec.sparks),
                stringResource(Res.string.quest_needs_radar).takeIf { needsRadar },
                stringResource(Res.string.quest_needs_glow).takeIf { needsGlow },
            )
            PickRow(
                title = questTitle(kind),
                checked = kind in setup.quests && !needsRadar && !needsGlow,
                onCheckedChange = { on ->
                    edit(setup.copy(quests = if (on) setup.quests + kind else setup.quests - kind))
                },
                enabled = !needsRadar && !needsGlow,
                subtitle = notes.joinToString(" · "),
                modifier = Modifier.testTag(TestTags.settingQuest(kind.name)),
            )
        }
    }
}

/** Every extra, in the order of the screen, with the server feature that lets the host turn it on. */
private fun featureItems(features: GameFeatures): List<Pair<ServerFeature, FeatureItem>> = listOf(
    ServerFeature.RADAR to FeatureItem(
        Explainer.RADAR,
        Res.drawable.ic_radar,
        Res.string.feature_radar,
        Res.string.feature_radar_caption,
        features.hasRadar,
        needsRadar = false,
        tag = TestTags.SETTINGS_RADAR,
    ) { on -> features.copy(radar = if (on) FeatureMode.OPTIONAL else FeatureMode.OFF) },
    ServerFeature.HIDER_SENSE to FeatureItem(
        Explainer.SENSE,
        Res.drawable.ic_vibrate,
        Res.string.feature_sense,
        Res.string.feature_sense_caption,
        features.hiderSense,
        needsRadar = true,
        tag = TestTags.SETTINGS_HIDER_SENSE,
    ) { features.copy(hiderSense = it) },
    ServerFeature.PROXIMITY_CATCH to FeatureItem(
        Explainer.PROXIMITY,
        Res.drawable.ic_near,
        Res.string.feature_proximity,
        Res.string.feature_proximity_caption,
        features.proximityCatch,
        needsRadar = true,
        tag = TestTags.SETTINGS_PROXIMITY_CATCH,
    ) { features.copy(proximityCatch = it) },
    ServerFeature.POCKET_STEALTH to FeatureItem(
        Explainer.POCKET,
        Res.drawable.ic_pocket,
        Res.string.feature_pocket,
        Res.string.feature_pocket_caption,
        features.pocketStealth,
        needsRadar = true,
        tag = TestTags.SETTINGS_POCKET_STEALTH,
    ) { features.copy(pocketStealth = it) },
    ServerFeature.PRECISION_RADAR to FeatureItem(
        Explainer.PRECISION,
        Res.drawable.ic_crosshair,
        Res.string.feature_precision,
        Res.string.feature_precision_caption,
        features.precisionRadar,
        needsRadar = false,
        tag = TestTags.SETTINGS_PRECISION_RADAR,
    ) { features.copy(precisionRadar = it) },
    ServerFeature.ACTIVITY to FeatureItem(
        Explainer.ACTIVITY,
        Res.drawable.ic_run,
        Res.string.feature_activity,
        Res.string.feature_activity_caption,
        features.activity,
        needsRadar = false,
        tag = TestTags.SETTINGS_ACTIVITY,
    ) { features.copy(activity = it) },
    ServerFeature.QUESTS to FeatureItem(
        Explainer.QUESTS,
        Res.drawable.ic_flag,
        Res.string.feature_quests,
        Res.string.feature_quests_caption,
        features.quests,
        needsRadar = false,
        tag = TestTags.SETTINGS_QUESTS,
    ) { features.copy(quests = it) },
    ServerFeature.PERKS to FeatureItem(
        Explainer.PERKS,
        Res.drawable.ic_bolt,
        Res.string.feature_perks,
        Res.string.feature_perks_caption,
        features.perks,
        needsRadar = false,
        tag = TestTags.SETTINGS_PERKS,
    ) { features.copy(perks = it) },
    ServerFeature.CHECKPOINTS to FeatureItem(
        Explainer.CHECKPOINTS,
        Res.drawable.ic_qr,
        Res.string.feature_checkpoints,
        Res.string.feature_checkpoints_caption,
        features.checkpoints,
        needsRadar = false,
        tag = TestTags.SETTINGS_CHECKPOINTS,
    ) { features.copy(checkpoints = it) },
    ServerFeature.PICKUPS to FeatureItem(
        Explainer.PICKUPS,
        Res.drawable.ic_location,
        Res.string.feature_pickups,
        Res.string.feature_pickups_caption,
        features.pickups,
        needsRadar = false,
        tag = TestTags.SETTINGS_PICKUPS,
    ) { features.copy(pickups = it) },
)

/** An extra: its sign, name and one line; the tile shows how it works above, the switch turns it on. */
@Composable
private fun FeatureTile(
    item: FeatureItem,
    checked: Boolean,
    enabled: Boolean,
    focused: Boolean,
    onFocus: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    PopSurface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Palette.Paper,
        borderWidth = 2.dp,
        shadow = if (focused) 4.dp else 0.dp,
        onClick = onFocus,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(item.icon), contentDescription = null, modifier = Modifier.size(26.dp))
                Spacer(Modifier.weight(1f))
                PopSwitch(checked = checked, enabled = enabled, onCheckedChange = onToggle, tag = item.tag)
            }
            Text(text = stringResource(item.name), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                text = stringResource(if (enabled) item.caption else Res.string.settings_needs_radar),
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) Palette.Ink2 else Palette.PinkInk,
                maxLines = 2,
            )
        }
    }
}

// ---- Save ----

/** What changed so far, and «Save»; the server's refusal right under it. */
@Composable
internal fun SaveBar(state: LobbyUiState, settings: SettingsPanelState, onEvent: (LobbyEvent) -> Unit) {
    val parts = settings.changedParts
    val names = parts.map { part ->
        stringResource(
            when (part) {
                ChangedPart.SIZE -> Res.string.settings_change_size
                ChangedPart.SHAPE -> Res.string.settings_change_shape
                ChangedPart.SHRINK -> Res.string.settings_change_shrink
                ChangedPart.PLACE -> Res.string.settings_change_place
                ChangedPart.TIME -> Res.string.settings_change_time
                ChangedPart.GLOW -> Res.string.settings_change_glow
                ChangedPart.FEATURES -> Res.string.settings_change_features
                ChangedPart.SPECTATORS -> Res.string.settings_change_spectators
            },
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.Fog)
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(color = Palette.Line, thickness = 1.5.dp)
        Text(
            text = if (names.isEmpty()) {
                stringResource(Res.string.settings_unchanged)
            } else {
                stringResource(Res.string.settings_changed, names.joinToString(", "))
            },
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Ink2,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        PopButton(
            text = stringResource(Res.string.settings_save),
            onClick = { onEvent(LobbyEvent.Settings.Save) },
            enabled = !settings.isSaving,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.SETTINGS_SAVE),
        )
        if (settings.isSaving) BusyRow(stringResource(Res.string.working))
        state.error?.let { error ->
            Banner(
                text = error.describe(),
                modifier = Modifier.testTag(TestTags.BANNER_ERROR),
                isError = true,
                actionLabel = stringResource(Res.string.action_dismiss),
                onAction = { onEvent(LobbyEvent.DismissError) },
            )
        }
    }
}

// ---- Sheets ----

/** A setting's «?»: its loop, a title and one sentence, over the dimmed settings. */
@Composable
internal fun HelpSheet(explainer: Explainer, onClose: () -> Unit) {
    Sheet(onDismiss = onClose, modifier = Modifier.testTag(TestTags.SETTINGS_HELP)) {
        ExplainerAnimation(explainer, modifier = Modifier.fillMaxWidth().height(170.dp))
        Text(text = stringResource(explainer.title), style = MaterialTheme.typography.headlineSmall)
        Text(text = stringResource(explainer.text), style = MaterialTheme.typography.bodyLarge, color = Palette.Ink2)
        PopButton(
            text = stringResource(Res.string.explain_ok),
            onClick = onClose,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.SETTINGS_HELP_OK),
        )
    }
}

/** The «More» tab's top: how the picked extra works. */
@Composable
internal fun ExplainerCard(explainer: Explainer, modifier: Modifier = Modifier) {
    PopCard(
        modifier = modifier,
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ExplainerAnimation(explainer, modifier = Modifier.fillMaxWidth().weight(1f))
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(text = stringResource(explainer.title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(explainer.text),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Ink2,
                maxLines = 2,
            )
        }
    }
}

/** «What changes»: every consequence of the draft on its own line, then «Save» or back. */
@Composable
internal fun ChangesSheet(changes: List<SettingsChange>, isSaving: Boolean, onSave: () -> Unit, onBack: () -> Unit) {
    Sheet(onDismiss = onBack, modifier = Modifier.testTag(TestTags.SETTINGS_CHANGES)) {
        Text(text = stringResource(Res.string.changes_title), style = MaterialTheme.typography.headlineSmall)
        PopCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(0.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            changes.forEachIndexed { index, change ->
                if (index > 0) HorizontalDivider(color = Palette.Line, thickness = 1.5.dp)
                ChangeRow(change)
            }
        }
        PopButton(
            text = stringResource(Res.string.settings_save),
            onClick = onSave,
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.SETTINGS_CONFIRM),
        )
        PopButton(
            text = stringResource(Res.string.changes_back),
            onClick = onBack,
            style = PopStyle.Outline,
            height = 48.dp,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.SETTINGS_BACK),
        )
    }
}

@Composable
private fun ChangeRow(change: SettingsChange) {
    val green = Palette.Green
    val (icon, tile, texts) = when (change) {
        is SettingsChange.ZoneSize -> Triple(
            Res.drawable.ic_rings,
            green,
            stringResource(
                Res.string.changes_zone_size,
                distanceText(change.fromMeters.toDouble()),
                distanceText(change.toMeters.toDouble()),
            ) to null,
        )

        is SettingsChange.ZoneShapeTo -> Triple(
            Res.drawable.ic_rings,
            green,
            stringResource(
                if (change.shape ==
                    ZoneShape.CIRCLE
                ) {
                    Res.string.changes_zone_circle
                } else {
                    Res.string.changes_zone_streets
                },
            ) to null,
        )

        is SettingsChange.ZoneMoved -> Triple(
            Res.drawable.ic_crosshair,
            green,
            stringResource(Res.string.changes_zone_moved, distanceText(change.meters.toDouble())) to null,
        )

        is SettingsChange.Shrinks -> Triple(
            Res.drawable.ic_rings,
            green,
            stringResource(if (change.on) Res.string.changes_shrink_on else Res.string.changes_shrink_off) to null,
        )

        SettingsChange.MapReloads -> Triple(
            Res.drawable.ic_clock,
            Palette.Sand,
            stringResource(Res.string.changes_reload) to stringResource(Res.string.changes_reload_caption),
        )

        is SettingsChange.OpenBuildingsKept -> Triple(
            Res.drawable.ic_building,
            green,
            stringResource(Res.string.changes_open_kept, change.count) to null,
        )

        is SettingsChange.OpenBuildingsLost -> Triple(
            Res.drawable.ic_warning,
            Palette.Pink,
            stringResource(Res.string.changes_open_lost, change.count) to
                stringResource(Res.string.changes_open_lost_caption),
        )

        is SettingsChange.BoardOutside -> Triple(
            Res.drawable.ic_warning,
            Palette.Pink,
            stringResource(Res.string.changes_board_outside, change.count) to
                stringResource(Res.string.changes_board_outside_caption),
        )

        is SettingsChange.BoardRemoved -> Triple(
            Res.drawable.ic_warning,
            Palette.Pink,
            stringResource(Res.string.changes_board_removed, change.count) to null,
        )

        SettingsChange.EverybodySees -> Triple(
            Res.drawable.ic_friends,
            Palette.Paper,
            stringResource(Res.string.changes_everybody) to null,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tile)
                .border(2.dp, Palette.Ink, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = texts.first, style = MaterialTheme.typography.titleSmall)
            texts.second?.let { SecondaryText(it) }
        }
    }
}

/**
 * A sheet over the settings in the same window (UI automation can't look into a dialog's): the rest dims, a tap on it
 * or the system back closes the sheet.
 */
@Composable
private fun Sheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    SystemBackHandler(enabled = true, onBack = onDismiss)
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Palette.Ink.copy(alpha = SCRIM_ALPHA))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    onDismiss()
                },
        )
        PopSurface(
            modifier = modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = Palette.Fog,
        ) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

// ---- Controls ----

/** A caps label over a setting, with its «?». */
@Composable
private fun SettingLabel(text: String, explainer: Explainer, onHelp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CapsText(text, color = Palette.Ink2)
        HelpButton(explainer, onHelp)
    }
}

/** «?»: how [explainer] works. */
@Composable
private fun HelpButton(explainer: Explainer, onClick: () -> Unit) {
    val description = stringResource(Res.string.explain_open)
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .border(2.dp, Palette.Ink, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(TestTags.settingHelp(explainer.name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "?", style = MaterialTheme.typography.labelMedium)
    }
}

/** A zone's shape to pick: a small picture of it, its name and one line; the picked one is pink. */
@Composable
private fun ShapeCard(
    title: String,
    caption: String,
    shape: ZoneShape,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PopSurface(
        modifier = modifier.semantics { this.selected = selected },
        shape = RoundedCornerShape(18.dp),
        color = if (selected) Palette.Pink else Palette.Paper,
        contentColor = if (selected) Color.White else Palette.Ink,
        shadow = if (selected) 4.dp else 0.dp,
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            ShapePicture(shape, onPink = selected, Modifier.size(40.dp))
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) Color.White else Palette.Ink2,
            )
        }
    }
}

/** Streets as a grid, and the zone on them: a circle across the blocks, or blocks along the streets. */
@Composable
private fun ShapePicture(shape: ZoneShape, onPink: Boolean, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val u = size.width / 40f
        val grid = (if (onPink) Color.White else Palette.Ink3).copy(alpha = 0.35f)
        listOf(10f, 22f, 33f).forEach { at ->
            drawLine(grid, Offset(0f, at * u), Offset(size.width, at * u), 2f * u)
            drawLine(grid, Offset((at - 1f) * u, 0f), Offset((at - 1f) * u, size.height), 2f * u)
        }
        val outline = if (shape == ZoneShape.CIRCLE) {
            Path().apply {
                addOval(Rect(Offset(7f * u, 7f * u), Size(26f * u, 26f * u)))
            }
        } else {
            Path().apply {
                moveTo(9f * u, 10f * u)
                listOf(32f to 10f, 32f to 22f, 36f to 22f, 36f to 33f, 9f to 33f, 9f to 22f, 5f to 22f, 5f to 10f)
                    .forEach { (x, y) -> lineTo(x * u, y * u) }
                close()
            }
        }
        drawPath(outline, Palette.Ink, style = Stroke(5f * u, join = StrokeJoin.Round))
        drawPath(outline, if (onPink) Color.White else Palette.Green, style = Stroke(2.4f * u, join = StrokeJoin.Round))
    }
}

/** A setting that is on or off: its sign, name, one line, the switch, its «?»; [extra] under it while it is on. */
@Composable
private fun ToggleCard(
    icon: DrawableResource,
    title: String,
    caption: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    explainer: Explainer,
    onHelp: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    PopCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(text = caption, style = MaterialTheme.typography.bodySmall, color = Palette.Ink2)
            }
            HelpButton(explainer, onHelp)
            PopSwitch(checked = checked, enabled = true, onCheckedChange = onCheckedChange, tag = tag)
        }
        extra()
    }
}

/** A row that opens more: sign, name, one line, «?» and an arrow. */
@Composable
private fun ActionCard(
    icon: DrawableResource,
    title: String,
    caption: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String,
    explainer: Explainer,
    onHelp: () -> Unit,
) {
    PopSurface(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(20.dp),
        borderWidth = 2.dp,
        onClick = onClick,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) Palette.Ink2 else Palette.Ink3,
                )
            }
            HelpButton(explainer, onHelp)
        }
    }
}

/** The app's switch: green when on, a whole 48 dp to tap. */
@Composable
private fun PopSwitch(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit, tag: String) {
    Box(
        modifier = Modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Ink,
                checkedTrackColor = Palette.Green,
                checkedBorderColor = Palette.Ink,
                uncheckedThumbColor = Palette.Ink3,
                uncheckedTrackColor = Palette.Paper,
                uncheckedBorderColor = Palette.Ink,
            ),
        )
    }
}

/** A row of choices: the label, the chips (the picked one ink), the unit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    label: String,
    values: List<Int>,
    selected: Int,
    text: @Composable (Int) -> String,
    unit: String?,
    tagName: String,
    onPick: (Int) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Ink2,
            modifier = Modifier.widthIn(min = 44.dp),
        )
        values.forEach { value ->
            Chip(
                text = text(value),
                selected = value == selected,
                onClick = { onPick(value) },
                modifier = Modifier.testTag(TestTags.settingChip(tagName, value)),
            )
        }
        if (unit != null) Text(text = unit, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink2)
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PopSurface(
        modifier = modifier.semantics { this.selected = selected },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Palette.Ink else Palette.Paper,
        contentColor = if (selected) Palette.Green else Palette.Ink,
        borderWidth = 2.dp,
        onClick = onClick,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}

/** «−» or «+» of a value, small. */
@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit, tag: String) {
    PopButton(
        text = text,
        onClick = onClick,
        enabled = enabled,
        style = PopStyle.Outline,
        height = STEP_SIZE,
        textStyle = MaterialTheme.typography.titleMedium,
        modifier = Modifier.size(STEP_SIZE).testTag(tag),
    )
}

private val STEP_SIZE: Dp = 44.dp
private const val SCRIM_ALPHA = 0.45f
private const val SQUARE_METERS_PER_HECTARE = 10_000.0
private const val SECONDS_PER_MINUTE = 60
private val GLOW_EVERY = listOf(2, 3, 5, 10, 15)
private val GLOW_FOR = listOf(3, 5, 10, 30)
