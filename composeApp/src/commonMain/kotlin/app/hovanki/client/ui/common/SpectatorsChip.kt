package app.hovanki.client.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.ic_eye
import app.hovanki.client.resources.spectators_count
import app.hovanki.client.ui.theme.Palette
import org.jetbrains.compose.resources.stringResource

/**
 * How many watch an open game right now (docs/adr/0011-spectators-and-recordings.md): in the lobby, in the round and
 * on the spectators' own screen.
 */
@Composable
fun SpectatorsChip(count: Int, modifier: Modifier = Modifier) {
    PopChip(
        text = stringResource(Res.string.spectators_count, count),
        color = Palette.Paper,
        contentColor = Palette.Ink,
        border = Palette.Ink,
        icon = Res.drawable.ic_eye,
        modifier = modifier.testTag(TestTags.SPECTATORS),
    )
}
