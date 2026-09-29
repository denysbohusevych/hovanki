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
import app.hovanki.client.resources.settings_delay_live
import app.hovanki.client.resources.settings_minutes
import app.hovanki.client.resources.settings_seconds
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.theme.Hovanki
import app.hovanki.client.ui.theme.Palette
import org.jetbrains.compose.resources.stringResource

/*
 * Controls of the host's setup screens: the settings (ui/settings), the board and the lobby's radar card.
 */

/** How far behind spectators see a game (docs/adr/0011-spectators-and-recordings.md): «Live», «30 s», «2 min». */
@Composable
internal fun spectatorDelayText(seconds: Int): String = when {
    seconds <= 0 -> stringResource(Res.string.settings_delay_live)
    seconds < SECONDS_PER_MINUTE -> stringResource(Res.string.settings_seconds, seconds)
    else -> stringResource(Res.string.settings_minutes, seconds / SECONDS_PER_MINUTE)
}

private const val SECONDS_PER_MINUTE = 60

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
