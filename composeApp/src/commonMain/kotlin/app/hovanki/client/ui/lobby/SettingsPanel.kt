package app.hovanki.client.ui.lobby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import app.hovanki.client.resources.settings_glow
import app.hovanki.client.resources.settings_glow_every
import app.hovanki.client.resources.settings_glow_for
import app.hovanki.client.resources.settings_glow_hint
import app.hovanki.client.resources.settings_hiding
import app.hovanki.client.resources.settings_meters
import app.hovanki.client.resources.settings_minutes
import app.hovanki.client.resources.settings_save
import app.hovanki.client.resources.settings_seconds
import app.hovanki.client.resources.settings_seeking
import app.hovanki.client.resources.settings_shape_circle
import app.hovanki.client.resources.settings_shape_streets
import app.hovanki.client.resources.settings_shape_streets_hint
import app.hovanki.client.resources.settings_shrinks
import app.hovanki.client.resources.settings_title
import app.hovanki.client.resources.settings_zone
import app.hovanki.client.resources.working
import app.hovanki.client.ui.common.Banner
import app.hovanki.client.ui.common.BusyRow
import app.hovanki.client.ui.common.Panel
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.describe
import app.hovanki.client.ui.theme.Hovanki
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.rules.GameSetup
import org.jetbrains.compose.resources.stringResource

/**
 * The host's game setup, full screen (docs/adr/0009-game-setup-glow-streets.md): the zone's size, shape and whether
 * it shrinks, the time to hide and to search, and the glow: how often and for how long the seekers see the hiders.
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

/** The glow lengths the panel offers: short ones one by one, then coarser; each shorter than the interval. */
private fun glowLengths(everyMinutes: Int): List<Int> =
    GLOW_LENGTHS.filter { it in GameSetup.GLOW_FOR_SECONDS && it < everyMinutes * SECONDS_PER_MINUTE }

private val GLOW_LENGTHS = listOf(2, 3, 4, 5, 6, 8, 10, 15, 20, 30, 45, 59, 60)
private const val SECONDS_PER_MINUTE = 60
private const val SEEKING_STEP = GameSetup.SEEKING_STEP_MINUTES

/** [label] on the left, the stepper on the right. */
@Composable
private fun LabeledStepper(
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
private fun Stepper(
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
private fun ShapeButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
private fun SwitchRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, tag: String) {
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
