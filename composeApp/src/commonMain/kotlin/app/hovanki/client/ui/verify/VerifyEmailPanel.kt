package app.hovanki.client.ui.verify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_cancel
import app.hovanki.client.resources.action_dismiss
import app.hovanki.client.resources.code_label
import app.hovanki.client.resources.confirm_card_enter
import app.hovanki.client.resources.confirm_card_later
import app.hovanki.client.resources.confirm_card_text
import app.hovanki.client.resources.profile_current_password
import app.hovanki.client.resources.resend_code
import app.hovanki.client.resources.resend_code_in
import app.hovanki.client.resources.verify_change_email
import app.hovanki.client.resources.verify_confirmed
import app.hovanki.client.resources.verify_new_email_label
import app.hovanki.client.resources.verify_save_email
import app.hovanki.client.resources.verify_spam_hint
import app.hovanki.client.resources.verify_submit
import app.hovanki.client.resources.verify_text
import app.hovanki.client.resources.verify_title
import app.hovanki.client.resources.verify_why
import app.hovanki.client.ui.common.Banner
import app.hovanki.client.ui.common.CommandStatus
import app.hovanki.client.ui.common.Panel
import app.hovanki.client.ui.common.PasswordField
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopSurface
import app.hovanki.client.ui.common.PopTextField
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.SystemBackHandler
import app.hovanki.client.ui.common.formatCountdown
import app.hovanki.client.ui.theme.Palette
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Confirming the email, full screen over the main screen (in the same window): the code from the email, «send again»
 * with a countdown, fixing a mistyped address with the current password. Closes by itself once the email is confirmed;
 * the close button and back return to the main screen without it (it stays optional).
 */
@Composable
fun VerifyEmailPanel(state: VerifyEmailUiState, onEvent: (VerifyEmailEvent) -> Unit) {
    val isBusy = state.isBusy
    val resendSecondsLeft = state.resendSecondsLeft
    val email = state.email

    Panel(
        title = stringResource(Res.string.verify_title),
        onClose = { onEvent(VerifyEmailEvent.Close) },
        modifier = Modifier.testTag(TestTags.VERIFY_PANEL),
    ) {
        // The change email form goes back to the code first (added after the panel's own back handler: it wins).
        SystemBackHandler(enabled = state.isChangingEmail, onBack = { onEvent(VerifyEmailEvent.CancelChangingEmail) })
        ScreenColumn {
            Text(
                text = stringResource(Res.string.verify_text, email),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag(TestTags.VERIFY_EMAIL),
            )
            SecondaryText(stringResource(Res.string.verify_why))
            PopTextField(
                value = state.code,
                onValueChange = { onEvent(VerifyEmailEvent.CodeChanged(it)) },
                label = { Text(stringResource(Res.string.code_label)) },
                singleLine = true,
                enabled = !isBusy,
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onEvent(VerifyEmailEvent.Verify) }),
                modifier = Modifier.fillMaxWidth().testTag(TestTags.VERIFY_CODE),
            )
            PopButton(
                text = stringResource(Res.string.verify_submit),
                onClick = { onEvent(VerifyEmailEvent.Verify) },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.VERIFY_SUBMIT),
            )
            TextButton(
                onClick = { onEvent(VerifyEmailEvent.Resend) },
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
            HorizontalDivider()

            if (state.isChangingEmail) {
                ChangeEmailForm(state, onEvent)
            } else {
                TextButton(
                    onClick = { onEvent(VerifyEmailEvent.StartChangingEmail) },
                    enabled = !isBusy,
                    modifier = Modifier.testTag(TestTags.VERIFY_CHANGE_EMAIL),
                ) {
                    Text(stringResource(Res.string.verify_change_email))
                }
            }
            CommandStatus(
                isBusy = isBusy,
                message = state.message,
                onDismiss = { onEvent(VerifyEmailEvent.DismissMessage) },
            )
        }
    }
}

/** A mistyped address: the new one and the current password (the email is how a forgotten password is reset). */
@Composable
private fun ChangeEmailForm(state: VerifyEmailUiState, onEvent: (VerifyEmailEvent) -> Unit) {
    val isBusy = state.isBusy
    PopTextField(
        value = state.newEmail,
        onValueChange = { onEvent(VerifyEmailEvent.NewEmailChanged(it)) },
        label = { Text(stringResource(Res.string.verify_new_email_label)) },
        singleLine = true,
        enabled = !isBusy,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth().testTag(TestTags.VERIFY_NEW_EMAIL),
    )
    PasswordField(
        value = state.password,
        onValueChange = { onEvent(VerifyEmailEvent.PasswordChanged(it)) },
        label = stringResource(Res.string.profile_current_password),
        enabled = !isBusy,
        onImeAction = { onEvent(VerifyEmailEvent.SaveEmail) },
        modifier = Modifier.testTag(TestTags.VERIFY_PASSWORD),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PopButton(
            text = stringResource(Res.string.verify_save_email),
            onClick = { onEvent(VerifyEmailEvent.SaveEmail) },
            enabled = !isBusy && state.password.isNotEmpty(),
            modifier = Modifier.testTag(TestTags.VERIFY_SAVE_EMAIL),
        )
        TextButton(onClick = { onEvent(VerifyEmailEvent.CancelChangingEmail) }) {
            Text(stringResource(Res.string.action_cancel))
        }
    }
}

/**
 * «Play», while the email is not confirmed: where the code went, «Enter the code» (the panel) and «Later» (hidden until
 * the app starts again).
 */
@Composable
fun ConfirmEmailCard(email: String, onOpen: () -> Unit, onLater: () -> Unit) {
    PopSurface(
        modifier = Modifier.fillMaxWidth().testTag(TestTags.CONFIRM_EMAIL_CARD),
        color = Palette.Paper,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(Res.string.confirm_card_text, email),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onLater, modifier = Modifier.testTag(TestTags.CONFIRM_EMAIL_LATER)) {
                    Text(stringResource(Res.string.confirm_card_later))
                }
                TextButton(onClick = onOpen, modifier = Modifier.testTag(TestTags.CONFIRM_EMAIL_OPEN)) {
                    Text(stringResource(Res.string.confirm_card_enter))
                }
            }
        }
    }
}

/** The email was just confirmed: says so for a few seconds (or until OK). */
@Composable
fun EmailConfirmedNotice(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) {
        delay(CONFIRMED_NOTICE_MILLIS)
        onDismiss()
    }
    Banner(
        text = stringResource(Res.string.verify_confirmed),
        modifier = modifier.testTag(TestTags.VERIFY_CONFIRMED),
        actionLabel = stringResource(Res.string.action_dismiss),
        onAction = onDismiss,
    )
}

private const val CONFIRMED_NOTICE_MILLIS = 4_000L
