package app.hovanki.client.ui.field

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.hovanki.client.lab.FieldSession
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_cancel
import app.hovanki.client.resources.action_confirm
import app.hovanki.client.resources.field_withdraw
import app.hovanki.client.resources.field_withdraw_text
import app.hovanki.client.resources.field_withdraw_title
import app.hovanki.client.ui.theme.Palette
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * The tester takes the field test build's consent back (docs/adr/0018-field-test-build.md §3.4): the log stops, and the
 * agreement comes again, because the build can't be played without it. A button on the profile; nothing in other builds.
 */
@Composable
fun FieldConsentWithdraw(modifier: Modifier = Modifier) {
    val session = koinInject<FieldSession>()
    if (!session.isFieldBuild) return
    var asking by remember { mutableStateOf(false) }
    TextButton(
        onClick = { asking = true },
        colors = ButtonDefaults.textButtonColors(contentColor = Palette.PinkInk),
        modifier = modifier.fillMaxWidth(),
    ) { Text(stringResource(Res.string.field_withdraw)) }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(Res.string.field_withdraw_title)) },
            text = { Text(stringResource(Res.string.field_withdraw_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        session.withdrawConsent()
                    },
                ) { Text(stringResource(Res.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }
}
