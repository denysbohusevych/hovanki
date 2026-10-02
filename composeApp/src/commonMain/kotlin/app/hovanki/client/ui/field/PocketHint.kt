package app.hovanki.client.ui.field

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.lab.FieldSession
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.field_pocket_hint
import app.hovanki.client.resources.field_pocket_hint_ok
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopStyle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * «Put the phone in your pocket without locking it: the screen goes dark by itself» (docs/adr/0018-field-test-build.md,
 * wave 4): over the round of the field build once the proximity sensor's screen is on ([FieldPocket.hint]), once a
 * round, until «Got it». Nothing in other builds.
 */
@Composable
fun PocketHint(modifier: Modifier = Modifier) {
    val session = koinInject<FieldSession>()
    if (!session.isFieldBuild) return
    val show by session.pocket.hint.collectAsStateWithLifecycle()
    if (!show) return
    PopCard(modifier = modifier) {
        Text(text = stringResource(Res.string.field_pocket_hint), style = MaterialTheme.typography.bodyMedium)
        PopButton(
            text = stringResource(Res.string.field_pocket_hint_ok),
            onClick = session.pocket::dismissHint,
            height = 40.dp,
            style = PopStyle.Outline,
        )
    }
}
