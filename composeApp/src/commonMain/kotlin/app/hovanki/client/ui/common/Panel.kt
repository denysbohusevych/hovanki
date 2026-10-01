package app.hovanki.client.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_close
import app.hovanki.client.resources.ic_close
import app.hovanki.client.ui.field.TrackScreen
import org.jetbrains.compose.resources.stringResource

/**
 * A full-screen panel over a screen (a group, the lobby's invitations, the chat), drawn in the same window: not a
 * dialog or a bottom sheet, whose separate window UI automation on Android can't look into. The close button
 * ([TestTags.PANEL_CLOSE]) and the system back action run [onClose]. [content] fills the rest; wrap it in a
 * [ScreenColumn] to scroll. [screen]: its name for the field log (`ui` events), a plain word.
 */
@Composable
fun Panel(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    screen: String? = null,
    content: @Composable () -> Unit,
) {
    // The field test build's log hears which panel is open, by [screen]'s plain name (nothing elsewhere).
    if (screen != null) TrackScreen(screen)
    SystemBackHandler(enabled = true, onBack = onClose)
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PopIconButton(
                    icon = Res.drawable.ic_close,
                    contentDescription = stringResource(Res.string.action_close),
                    onClick = onClose,
                    size = 44.dp,
                    modifier = Modifier.testTag(TestTags.PANEL_CLOSE),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                content()
            }
        }
    }
}

/** A person or a group to pick, with a checkbox; the whole row toggles it. */
@Composable
fun PickRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) SecondaryText(subtitle)
        }
    }
}

/** A section title in a list. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 8.dp))
}
