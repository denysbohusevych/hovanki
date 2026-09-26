package app.hovanki.client.ui.verify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_cancel
import app.hovanki.client.resources.action_log_out
import app.hovanki.client.resources.code_label
import app.hovanki.client.resources.resend_code
import app.hovanki.client.resources.resend_code_in
import app.hovanki.client.resources.verify_change_email
import app.hovanki.client.resources.verify_new_email_label
import app.hovanki.client.resources.verify_save_email
import app.hovanki.client.resources.verify_spam_hint
import app.hovanki.client.resources.verify_submit
import app.hovanki.client.resources.verify_text
import app.hovanki.client.resources.verify_title
import app.hovanki.client.ui.common.CommandStatus
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.SystemBackHandler
import app.hovanki.client.ui.common.formatCountdown
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** Logged in, email not confirmed yet: only this screen (and logging out) until it is. */
@Composable
fun VerifyEmailScreen(viewModel: VerifyEmailViewModel = koinViewModel()) {
    val account by viewModel.accountState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val resendSecondsLeft by viewModel.resendSecondsLeft.collectAsStateWithLifecycle()
    val email = account.user?.email.orEmpty()
    SystemBackHandler(enabled = viewModel.isChangingEmail, onBack = viewModel::cancelChangingEmail)

    ScreenColumn(modifier = Modifier.testTag(TestTags.VERIFY_SCREEN)) {
        Text(text = stringResource(Res.string.verify_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(Res.string.verify_text, email),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(TestTags.VERIFY_EMAIL),
        )
        OutlinedTextField(
            value = viewModel.code,
            onValueChange = viewModel::onCodeChange,
            label = { Text(stringResource(Res.string.code_label)) },
            singleLine = true,
            enabled = !isBusy,
            textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.verify() }),
            modifier = Modifier.fillMaxWidth().testTag(TestTags.VERIFY_CODE),
        )
        Button(
            onClick = viewModel::verify,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.VERIFY_SUBMIT),
        ) {
            Text(stringResource(Res.string.verify_submit))
        }
        TextButton(
            onClick = viewModel::resend,
            enabled = !isBusy && resendSecondsLeft == 0L,
            modifier = Modifier.testTag(TestTags.VERIFY_RESEND),
        ) {
            Text(
                if (resendSecondsLeft > 0) {
                    stringResource(Res.string.resend_code_in, formatCountdown(resendSecondsLeft * 1000))
                } else {
                    stringResource(Res.string.resend_code)
                },
            )
        }
        SecondaryText(stringResource(Res.string.verify_spam_hint))

        if (viewModel.isChangingEmail) {
            OutlinedTextField(
                value = viewModel.newEmail,
                onValueChange = viewModel::onNewEmailChange,
                label = { Text(stringResource(Res.string.verify_new_email_label)) },
                singleLine = true,
                enabled = !isBusy,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { viewModel.saveEmail() }),
                modifier = Modifier.fillMaxWidth().testTag(TestTags.VERIFY_NEW_EMAIL),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::saveEmail,
                    enabled = !isBusy,
                    modifier = Modifier.testTag(TestTags.VERIFY_SAVE_EMAIL),
                ) {
                    Text(stringResource(Res.string.verify_save_email))
                }
                TextButton(onClick = viewModel::cancelChangingEmail) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        } else {
            TextButton(
                onClick = viewModel::startChangingEmail,
                enabled = !isBusy,
                modifier = Modifier.testTag(TestTags.VERIFY_CHANGE_EMAIL),
            ) {
                Text(stringResource(Res.string.verify_change_email))
            }
        }
        CommandStatus(isBusy = isBusy, message = message, onDismiss = viewModel::dismissMessage)

        HorizontalDivider()
        OutlinedButton(
            onClick = viewModel::logOut,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.VERIFY_LOG_OUT),
        ) {
            Text(stringResource(Res.string.action_log_out))
        }
    }
}
