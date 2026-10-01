package app.hovanki.client.ui.field

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.lab.FieldSession
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.field_touch_again_text
import app.hovanki.client.resources.field_touch_count
import app.hovanki.client.resources.field_touch_dismiss
import app.hovanki.client.resources.field_touch_done
import app.hovanki.client.resources.field_touch_pick
import app.hovanki.client.resources.field_touch_text
import app.hovanki.client.resources.field_touch_title
import app.hovanki.client.resources.field_touch_title_again
import app.hovanki.client.ui.common.CapsText
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopChip
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.PlayerId
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** A player the tester can say they touched phones with: the id goes into the log, the name only on the screen. */
data class TouchNeighbour(val id: PlayerId, val name: String)

/**
 * «Touch phones with a neighbour» (docs/adr/0018-field-test-build.md §5, docs/field-test.md step 5): the lobby's card
 * and, with [again], the results screen's («once more, for the drift»). The tester picks a neighbour, they touch the
 * phones back to back for a second, and both press «We touched»: the truth the touch detector is checked against.
 * Shown only while [FieldSession.touchCard] is on (the field build, the log running, a game with the radar); nothing in
 * other builds. A tester who has no use for it dismisses it until the screen is left.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TouchCard(neighbours: List<TouchNeighbour>, modifier: Modifier = Modifier, again: Boolean = false) {
    val session = koinInject<FieldSession>()
    if (!session.isFieldBuild) return
    val show by session.touchCard.collectAsStateWithLifecycle()
    val count by session.touchCount.collectAsStateWithLifecycle()
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (!show || dismissed || neighbours.isEmpty()) return

    var picked by remember { mutableStateOf<PlayerId?>(null) }
    // Somebody left: the pick goes with them.
    val partner = neighbours.firstOrNull { it.id == picked }

    PopCard(modifier = modifier.fillMaxWidth()) {
        CapsText(stringResource(if (again) Res.string.field_touch_title_again else Res.string.field_touch_title))
        Text(
            text = stringResource(if (again) Res.string.field_touch_again_text else Res.string.field_touch_text),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(text = stringResource(Res.string.field_touch_pick), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (neighbour in neighbours) {
                val selected = neighbour.id == partner?.id
                PopChip(
                    text = neighbour.name,
                    color = if (selected) Palette.Ink else Palette.Paper,
                    contentColor = if (selected) Palette.Lime else Palette.Ink,
                    border = Palette.Ink,
                    onClick = { picked = neighbour.id },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PopButton(
                text = stringResource(Res.string.field_touch_done),
                onClick = { partner?.let { session.touched(it.id) } },
                enabled = partner != null,
                height = 44.dp,
            )
            PopButton(
                text = stringResource(Res.string.field_touch_dismiss),
                onClick = { dismissed = true },
                height = 44.dp,
                style = PopStyle.Outline,
            )
        }
        if (count > 0) SecondaryText(stringResource(Res.string.field_touch_count, count))
    }
}
