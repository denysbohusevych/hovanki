package app.hovanki.client.ui.field

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.field_consent_agree
import app.hovanki.client.resources.field_consent_decline
import app.hovanki.client.resources.field_consent_text
import app.hovanki.client.resources.field_consent_title
import app.hovanki.client.resources.field_declined_back
import app.hovanki.client.resources.field_declined_text
import app.hovanki.client.resources.field_declined_title
import app.hovanki.client.ui.common.BuildLabel
import app.hovanki.client.ui.common.Logo
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.appSafeDrawingPadding
import org.jetbrains.compose.resources.stringResource

/**
 * The field test build's agreement (docs/adr/0018-field-test-build.md §3.4): the first thing a tester sees, before
 * login. Without it the build can't be played: «No, thanks» shows a screen that says so, with a way back to the
 * agreement. [onAgree] keeps the consent ([app.hovanki.client.lab.FieldSession.giveConsent]); [buildLabel] names the
 * build, so a tester's report does too.
 */
@Composable
fun FieldConsentScreen(onAgree: () -> Unit, buildLabel: String, modifier: Modifier = Modifier) {
    var declined by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .appSafeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Logo(
            markSize = 48.dp,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (declined) {
            PopCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(Res.string.field_declined_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(text = stringResource(Res.string.field_declined_text), style = MaterialTheme.typography.bodyLarge)
            }
            PopButton(
                text = stringResource(Res.string.field_declined_back),
                onClick = { declined = false },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            PopCard(modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(Res.string.field_consent_title), style = MaterialTheme.typography.titleLarge)
                Text(text = stringResource(Res.string.field_consent_text), style = MaterialTheme.typography.bodyLarge)
            }
            PopButton(
                text = stringResource(Res.string.field_consent_agree),
                onClick = onAgree,
                modifier = Modifier.fillMaxWidth(),
            )
            PopButton(
                text = stringResource(Res.string.field_consent_decline),
                onClick = { declined = true },
                style = PopStyle.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        BuildLabel(buildLabel)
    }
}
