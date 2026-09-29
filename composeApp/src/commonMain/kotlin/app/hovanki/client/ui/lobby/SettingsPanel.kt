package app.hovanki.client.ui.lobby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_dismiss
import app.hovanki.client.resources.quest_needs_glow
import app.hovanki.client.resources.quest_needs_radar
import app.hovanki.client.resources.settings_activity
import app.hovanki.client.resources.settings_activity_hint
import app.hovanki.client.resources.settings_checkpoints
import app.hovanki.client.resources.settings_checkpoints_hint
import app.hovanki.client.resources.settings_delay_hint
import app.hovanki.client.resources.settings_delay_live
import app.hovanki.client.resources.settings_fair_only
import app.hovanki.client.resources.settings_features
import app.hovanki.client.resources.settings_features_hint
import app.hovanki.client.resources.settings_features_none
import app.hovanki.client.resources.settings_glow
import app.hovanki.client.resources.settings_glow_every
import app.hovanki.client.resources.settings_glow_for
import app.hovanki.client.resources.settings_glow_hint
import app.hovanki.client.resources.settings_hider_sense
import app.hovanki.client.resources.settings_hider_sense_hint
import app.hovanki.client.resources.settings_hiding
import app.hovanki.client.resources.settings_meters
import app.hovanki.client.resources.settings_minutes
import app.hovanki.client.resources.settings_open_game
import app.hovanki.client.resources.settings_open_game_hint
import app.hovanki.client.resources.settings_perks
import app.hovanki.client.resources.settings_perks_hint
import app.hovanki.client.resources.settings_pickups
import app.hovanki.client.resources.settings_pickups_hint
import app.hovanki.client.resources.settings_pocket_stealth
import app.hovanki.client.resources.settings_pocket_stealth_hint
import app.hovanki.client.resources.settings_precision_for_hiders
import app.hovanki.client.resources.settings_precision_radar
import app.hovanki.client.resources.settings_precision_radar_hint
import app.hovanki.client.resources.settings_proximity_catch
import app.hovanki.client.resources.settings_proximity_catch_hint
import app.hovanki.client.resources.settings_quests
import app.hovanki.client.resources.settings_quests_hint
import app.hovanki.client.resources.settings_quests_pick
import app.hovanki.client.resources.settings_radar
import app.hovanki.client.resources.settings_radar_hint
import app.hovanki.client.resources.settings_radar_required
import app.hovanki.client.resources.settings_radar_required_hint
import app.hovanki.client.resources.settings_save
import app.hovanki.client.resources.settings_seconds
import app.hovanki.client.resources.settings_seeking
import app.hovanki.client.resources.settings_shape_circle
import app.hovanki.client.resources.settings_shape_streets
import app.hovanki.client.resources.settings_shape_streets_hint
import app.hovanki.client.resources.settings_shrinks
import app.hovanki.client.resources.settings_spectator_delay
import app.hovanki.client.resources.settings_title
import app.hovanki.client.resources.settings_zone
import app.hovanki.client.resources.sparks_count
import app.hovanki.client.resources.working
import app.hovanki.client.ui.common.Banner
import app.hovanki.client.ui.common.BusyRow
import app.hovanki.client.ui.common.Panel
import app.hovanki.client.ui.common.PickRow
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.audienceTitle
import app.hovanki.client.ui.common.describe
import app.hovanki.client.ui.common.questTitle
import app.hovanki.client.ui.theme.Hovanki
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.FeatureMode
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.rules.GameSetup
import app.hovanki.shared.rules.QuestCatalog
import org.jetbrains.compose.resources.stringResource

/**
 * The host's game setup, full screen (docs/adr/0009-game-setup-glow-streets.md): the zone's size, shape and whether
 * it shrinks, the time to hide and to search, the glow: how often and for how long the seekers see the hiders, and
 * whether the game is open to spectators, and how far behind they see it (docs/adr/0011-spectators-and-recordings.md).
 * «Save» sends it; every phone in the lobby shows the new setup. Back and the close button return without saving.
 */
@Composable
fun SettingsPanel(state: LobbyUiState, viewModel: LobbyViewModel) {
    val setup = viewModel.setupDraft
    val isSaving = viewModel.isSavingSettings
    val edit = { changed: GameSetup -> if (!isSaving) viewModel.editSetup(changed) }

    Panel(
        title = stringResource(Res.string.settings_title),
        onClose = viewModel::closeSettings,
        modifier = Modifier.testTag(TestTags.SETTINGS_PANEL),
    ) {
        ScreenColumn {
            PopCard(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.settings_zone), style = MaterialTheme.typography.titleMedium)
                Stepper(
                    name = "radius",
                    value = stringResource(Res.string.settings_meters, setup.radiusMeters),
                    onMinus = { edit(setup.copy(radiusMeters = setup.radiusMeters - GameSetup.RADIUS_STEP_METERS)) },
                    onPlus = { edit(setup.copy(radiusMeters = setup.radiusMeters + GameSetup.RADIUS_STEP_METERS)) },
                    canMinus = setup.radiusMeters > GameSetup.RADIUS_METERS.first,
                    canPlus = setup.radiusMeters < GameSetup.RADIUS_METERS.last,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShapeButton(
                        text = stringResource(Res.string.settings_shape_circle),
                        selected = setup.zoneShape == ZoneShape.CIRCLE,
                        onClick = { edit(setup.copy(zoneShape = ZoneShape.CIRCLE)) },
                        modifier = Modifier.weight(1f).testTag(TestTags.SETTINGS_SHAPE_CIRCLE),
                    )
                    ShapeButton(
                        text = stringResource(Res.string.settings_shape_streets),
                        selected = setup.zoneShape == ZoneShape.STREETS,
                        onClick = { edit(setup.copy(zoneShape = ZoneShape.STREETS)) },
                        modifier = Modifier.weight(1f).testTag(TestTags.SETTINGS_SHAPE_STREETS),
                    )
                }
                if (setup.zoneShape == ZoneShape.STREETS) {
                    SecondaryText(stringResource(Res.string.settings_shape_streets_hint))
                }
                SwitchRow(
                    text = stringResource(Res.string.settings_shrinks),
                    checked = setup.shrinks,
                    onCheckedChange = { edit(setup.copy(shrinks = it)) },
                    tag = TestTags.SETTINGS_SHRINKS,
                )
            }

            PopCard(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledStepper(
                    label = stringResource(Res.string.settings_hiding),
                    name = "hiding",
                    value = stringResource(Res.string.settings_minutes, setup.hidingMinutes),
                    onMinus = { edit(setup.copy(hidingMinutes = setup.hidingMinutes - 1)) },
                    onPlus = { edit(setup.copy(hidingMinutes = setup.hidingMinutes + 1)) },
                    canMinus = setup.hidingMinutes > GameSetup.HIDING_MINUTES.first,
                    canPlus = setup.hidingMinutes < GameSetup.HIDING_MINUTES.last,
                )
                LabeledStepper(
                    label = stringResource(Res.string.settings_seeking),
                    name = "seeking",
                    value = stringResource(Res.string.settings_minutes, setup.seekingMinutes),
                    onMinus = { edit(setup.copy(seekingMinutes = setup.seekingMinutes - SEEKING_STEP)) },
                    onPlus = { edit(setup.copy(seekingMinutes = setup.seekingMinutes + SEEKING_STEP)) },
                    canMinus = setup.seekingMinutes > GameSetup.SEEKING_MINUTES.first,
                    canPlus = setup.seekingMinutes < GameSetup.SEEKING_MINUTES.last,
                )
            }

            PopCard(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val glowOn = setup.glowEveryMinutes > 0
                SwitchRow(
                    text = stringResource(Res.string.settings_glow),
                    checked = glowOn,
                    onCheckedChange = { on ->
                        edit(setup.copy(glowEveryMinutes = if (on) GameSetup().glowEveryMinutes else 0))
                    },
                    tag = TestTags.SETTINGS_GLOW,
                )
                SecondaryText(stringResource(Res.string.settings_glow_hint))
                if (glowOn) {
                    LabeledStepper(
                        label = stringResource(Res.string.settings_glow_every),
                        name = "glow_every",
                        value = stringResource(Res.string.settings_minutes, setup.glowEveryMinutes),
                        onMinus = { edit(setup.copy(glowEveryMinutes = setup.glowEveryMinutes - 1)) },
                        onPlus = { edit(setup.copy(glowEveryMinutes = setup.glowEveryMinutes + 1)) },
                        canMinus = setup.glowEveryMinutes > GameSetup.GLOW_EVERY_MINUTES.first,
                        canPlus = setup.glowEveryMinutes < GameSetup.GLOW_EVERY_MINUTES.last,
                    )
                    val lengths = glowLengths(setup.glowEveryMinutes)
                    LabeledStepper(
                        label = stringResource(Res.string.settings_glow_for),
                        name = "glow_for",
                        value = stringResource(Res.string.settings_seconds, setup.glowForSeconds),
                        onMinus = {
                            lengths.lastOrNull { it < setup.glowForSeconds }?.let {
                                edit(setup.copy(glowForSeconds = it))
                            }
                        },
                        onPlus = {
                            lengths.firstOrNull { it > setup.glowForSeconds }?.let {
                                edit(setup.copy(glowForSeconds = it))
                            }
                        },
                        canMinus = lengths.any { it < setup.glowForSeconds },
                        canPlus = lengths.any { it > setup.glowForSeconds },
                    )
                }
            }

            FeaturesCard(state, setup, edit)
            PopCard(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SwitchRow(
                    text = stringResource(Res.string.settings_open_game),
                    checked = setup.openGame,
                    onCheckedChange = { edit(setup.copy(openGame = it)) },
                    tag = TestTags.SETTINGS_OPEN_GAME,
                )
                SecondaryText(stringResource(Res.string.settings_open_game_hint))
                if (setup.openGame) {
                    Text(
                        text = stringResource(Res.string.settings_spectator_delay),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (delay in GameSetup.SPECTATOR_DELAYS) {
                            ShapeButton(
                                text = spectatorDelayText(delay),
                                selected = setup.spectatorDelaySeconds == delay,
                                onClick = { edit(setup.copy(spectatorDelaySeconds = delay)) },
                                modifier = Modifier.testTag(TestTags.settingsDelay(delay)),
                            )
                        }
                    }
                    SecondaryText(stringResource(Res.string.settings_delay_hint))
                }
            }

            PopButton(
                text = stringResource(Res.string.settings_save),
                onClick = viewModel::saveSettings,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.SETTINGS_SAVE),
            )
            if (isSaving) BusyRow(stringResource(Res.string.working))
            state.error?.let { error ->
                Banner(
                    text = error.describe(),
                    modifier = Modifier.testTag(TestTags.BANNER_ERROR),
                    isError = true,
                    actionLabel = stringResource(Res.string.action_dismiss),
                    onAction = viewModel::dismissError,
                )
            }
        }
    }
}

/**
 * The extras (docs/adr/0012-nearby-radar.md, docs/adr/0013-quests-sparks-and-sensors.md): only what the server's
 * operator has switched on is offered; the radar's companions only with the radar; the quests to pick with the
 * quests on, the ones that need the radar or the glow greyed out without them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeaturesCard(state: LobbyUiState, setup: GameSetup, edit: (GameSetup) -> Unit) {
    val enabled = state.enabledFeatures
    val features = setup.features
    val set = { changed: GameFeatures -> edit(setup.copy(features = changed)) }
    PopCard(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.settings_features), style = MaterialTheme.typography.titleMedium)
        if (enabled.isEmpty()) {
            SecondaryText(stringResource(Res.string.settings_features_none))
            return@PopCard
        }
        SecondaryText(stringResource(Res.string.settings_features_hint))
        if (ServerFeature.RADAR in enabled) {
            SwitchRow(
                text = stringResource(Res.string.settings_radar),
                checked = features.hasRadar,
                onCheckedChange = { on ->
                    set(features.copy(radar = if (on) FeatureMode.OPTIONAL else FeatureMode.OFF))
                },
                tag = TestTags.SETTINGS_RADAR,
            )
            SecondaryText(stringResource(Res.string.settings_radar_hint))
            if (features.hasRadar) {
                SwitchRow(
                    text = stringResource(Res.string.settings_radar_required),
                    checked = features.radar == FeatureMode.REQUIRED,
                    onCheckedChange = { on ->
                        set(features.copy(radar = if (on) FeatureMode.REQUIRED else FeatureMode.OPTIONAL))
                    },
                    tag = TestTags.SETTINGS_RADAR_REQUIRED,
                )
                if (features.radar == FeatureMode.REQUIRED) {
                    SecondaryText(stringResource(Res.string.settings_radar_required_hint))
                }
                if (ServerFeature.HIDER_SENSE in enabled) {
                    SwitchRow(
                        text = stringResource(Res.string.settings_hider_sense),
                        checked = features.hiderSense,
                        onCheckedChange = { set(features.copy(hiderSense = it)) },
                        tag = TestTags.SETTINGS_HIDER_SENSE,
                    )
                    SecondaryText(stringResource(Res.string.settings_hider_sense_hint))
                }
                if (ServerFeature.PROXIMITY_CATCH in enabled) {
                    SwitchRow(
                        text = stringResource(Res.string.settings_proximity_catch),
                        checked = features.proximityCatch,
                        onCheckedChange = { set(features.copy(proximityCatch = it)) },
                        tag = TestTags.SETTINGS_PROXIMITY_CATCH,
                    )
                    SecondaryText(stringResource(Res.string.settings_proximity_catch_hint))
                }
                if (ServerFeature.POCKET_STEALTH in enabled) {
                    SwitchRow(
                        text = stringResource(Res.string.settings_pocket_stealth),
                        checked = features.pocketStealth,
                        onCheckedChange = { set(features.copy(pocketStealth = it)) },
                        tag = TestTags.SETTINGS_POCKET_STEALTH,
                    )
                    SecondaryText(stringResource(Res.string.settings_pocket_stealth_hint))
                }
            }
        }
        if (ServerFeature.PRECISION_RADAR in enabled) {
            SwitchRow(
                text = stringResource(Res.string.settings_precision_radar),
                checked = features.precisionRadar,
                onCheckedChange = { set(features.copy(precisionRadar = it)) },
                tag = TestTags.SETTINGS_PRECISION_RADAR,
            )
            SecondaryText(stringResource(Res.string.settings_precision_radar_hint))
            if (features.precisionRadar) {
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
        }
        if (ServerFeature.ACTIVITY in enabled) {
            SwitchRow(
                text = stringResource(Res.string.settings_activity),
                checked = features.activity,
                onCheckedChange = { set(features.copy(activity = it)) },
                tag = TestTags.SETTINGS_ACTIVITY,
            )
            SecondaryText(stringResource(Res.string.settings_activity_hint))
        }
        if (ServerFeature.QUESTS in enabled) {
            SwitchRow(
                text = stringResource(Res.string.settings_quests),
                checked = features.quests,
                onCheckedChange = { set(features.copy(quests = it)) },
                tag = TestTags.SETTINGS_QUESTS,
            )
            SecondaryText(stringResource(Res.string.settings_quests_hint))
            if (features.quests) {
                Text(stringResource(Res.string.settings_quests_pick), style = MaterialTheme.typography.titleSmall)
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
        if (ServerFeature.PERKS in enabled) {
            SwitchRow(
                text = stringResource(Res.string.settings_perks),
                checked = features.perks,
                onCheckedChange = { set(features.copy(perks = it)) },
                tag = TestTags.SETTINGS_PERKS,
            )
            SecondaryText(stringResource(Res.string.settings_perks_hint))
        }
        if (ServerFeature.CHECKPOINTS in enabled) {
            SwitchRow(
                text = stringResource(Res.string.settings_checkpoints),
                checked = features.checkpoints,
                onCheckedChange = { set(features.copy(checkpoints = it)) },
                tag = TestTags.SETTINGS_CHECKPOINTS,
            )
            SecondaryText(stringResource(Res.string.settings_checkpoints_hint))
        }
        if (ServerFeature.PICKUPS in enabled) {
            SwitchRow(
                text = stringResource(Res.string.settings_pickups),
                checked = features.pickups,
                onCheckedChange = { set(features.copy(pickups = it)) },
                tag = TestTags.SETTINGS_PICKUPS,
            )
            SecondaryText(stringResource(Res.string.settings_pickups_hint))
        }
    }
}

/** How far behind spectators see a game (docs/adr/0011-spectators-and-recordings.md): «Live», «30 s», «2 min». */
@Composable
internal fun spectatorDelayText(seconds: Int): String = when {
    seconds <= 0 -> stringResource(Res.string.settings_delay_live)
    seconds < SECONDS_PER_MINUTE -> stringResource(Res.string.settings_seconds, seconds)
    else -> stringResource(Res.string.settings_minutes, seconds / SECONDS_PER_MINUTE)
}

/** The glow lengths the panel offers: short ones one by one, then coarser; each shorter than the interval. */
private fun glowLengths(everyMinutes: Int): List<Int> =
    GLOW_LENGTHS.filter { it in GameSetup.GLOW_FOR_SECONDS && it < everyMinutes * SECONDS_PER_MINUTE }

private val GLOW_LENGTHS = listOf(2, 3, 4, 5, 6, 8, 10, 15, 20, 30, 45, 59, 60)
private const val SECONDS_PER_MINUTE = 60
private const val SEEKING_STEP = GameSetup.SEEKING_STEP_MINUTES

/** [label] on the left, the stepper on the right. */
@Composable
internal fun LabeledStepper(
    label: String,
    name: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    canMinus: Boolean,
    canPlus: Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Stepper(name, value, onMinus, onPlus, canMinus, canPlus)
    }
}

/** «−», the value, «+»; tagged by [name] for UI automation ([TestTags.settingValue] and friends). */
@Composable
internal fun Stepper(
    name: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    canMinus: Boolean,
    canPlus: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PopButton(
            text = "−",
            onClick = onMinus,
            enabled = canMinus,
            style = PopStyle.Outline,
            height = 44.dp,
            modifier = Modifier.testTag(TestTags.settingMinus(name)),
        )
        Text(
            text = value,
            style = Hovanki.text.code.copy(fontSize = 18.sp, lineHeight = 22.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 84.dp).testTag(TestTags.settingValue(name)),
        )
        PopButton(
            text = "+",
            onClick = onPlus,
            enabled = canPlus,
            style = PopStyle.Outline,
            height = 44.dp,
            modifier = Modifier.testTag(TestTags.settingPlus(name)),
        )
    }
}

/** One of the zone's shapes; the chosen one is ink. */
@Composable
internal fun ShapeButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PopButton(
        text = text,
        onClick = onClick,
        style = if (selected) PopStyle.Dark else PopStyle.Outline,
        height = 44.dp,
        modifier = modifier,
    )
}

/** [text] and a switch; the whole row toggles it. */
@Composable
internal fun SwitchRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = text, style = MaterialTheme.typography.bodyLarge)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Ink,
                checkedTrackColor = Palette.Lime,
                checkedBorderColor = Palette.Ink,
                uncheckedThumbColor = Palette.Ink3,
                uncheckedTrackColor = Palette.Paper,
                uncheckedBorderColor = Palette.Ink,
            ),
        )
    }
}
