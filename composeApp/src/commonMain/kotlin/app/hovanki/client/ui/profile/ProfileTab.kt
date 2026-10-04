package app.hovanki.client.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_log_out
import app.hovanki.client.resources.new_password_label
import app.hovanki.client.resources.password_label
import app.hovanki.client.resources.profile_change_password
import app.hovanki.client.resources.profile_confirm_email
import app.hovanki.client.resources.profile_current_password
import app.hovanki.client.resources.profile_delete
import app.hovanki.client.resources.profile_delete_confirm
import app.hovanki.client.resources.profile_delete_warning
import app.hovanki.client.resources.profile_email
import app.hovanki.client.resources.profile_email_unconfirmed
import app.hovanki.client.resources.profile_nickname
import app.hovanki.client.resources.profile_save_password
import app.hovanki.client.resources.register_password_hint
import app.hovanki.client.ui.common.Avatar
import app.hovanki.client.ui.common.BuildLabel
import app.hovanki.client.ui.common.CommandStatus
import app.hovanki.client.ui.common.PasswordField
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.SystemBackHandler
import app.hovanki.client.ui.common.collectScreenState
import app.hovanki.client.ui.field.FieldConsentWithdraw
import app.hovanki.client.ui.history.HistoryButton
import app.hovanki.client.ui.history.HistoryEvent
import app.hovanki.client.ui.history.HistoryViewModel
import app.hovanki.client.ui.history.RoutesCard
import app.hovanki.client.ui.history.StatsCard
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.verify.VerifyEmailEvent
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * «Profile»: nickname and email (not confirmed yet: confirm it here), the player's statistics and game history, «save
 * my routes», change the password, log out, delete the account.
 */
@Composable
fun ProfileTab(
    onVerifyEvent: (VerifyEmailEvent) -> Unit,
    history: HistoryViewModel,
    viewModel: ProfileViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectScreenState()
    val onEvent = viewModel::onEvent
    val isBusy = state.isBusy
    val historyState by history.uiState.collectScreenState()
    val user = state.user ?: return
    val form = state.form
    SystemBackHandler(enabled = form != null, onBack = { onEvent(ProfileEvent.CloseForm) })
    // Fresh numbers every time the profile opens: a game may have ended meanwhile.
    LaunchedEffect(user.id) { history.onEvent(HistoryEvent.Refresh) }

    ScreenColumn(modifier = Modifier.testTag(TestTags.PROFILE_SCREEN)) {
        PopCard(modifier = Modifier.fillMaxWidth(), shadow = 5.dp, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Avatar(name = user.nickname, color = Palette.Violet, contentColor = Palette.Paper, size = 64.dp)
                Column(modifier = Modifier.weight(1f)) {
                    SecondaryText(stringResource(Res.string.profile_nickname))
                    Text(
                        text = user.nickname,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag(TestTags.PROFILE_NICKNAME),
                    )
                }
            }
            Column {
                SecondaryText(stringResource(Res.string.profile_email))
                Text(
                    text = user.email,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag(TestTags.PROFILE_EMAIL),
                )
            }
            if (!user.emailVerified) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(Res.string.profile_email_unconfirmed),
                        style = MaterialTheme.typography.labelLarge,
                        color = Palette.OrangeInk,
                        modifier = Modifier.weight(1f).testTag(TestTags.PROFILE_EMAIL_UNCONFIRMED),
                    )
                    PopButton(
                        text = stringResource(Res.string.profile_confirm_email),
                        onClick = { onVerifyEvent(VerifyEmailEvent.Open) },
                        style = PopStyle.Dark,
                        height = 40.dp,
                        modifier = Modifier.testTag(TestTags.PROFILE_CONFIRM_EMAIL),
                    )
                }
            }
        }

        StatsCard(historyState.history.stats)
        HistoryButton(onClick = { history.onEvent(HistoryEvent.Open) }, enabled = !historyState.isBusy)
        RoutesCard(historyState, history::onEvent, saveRoutes = user.saveRoutes)
        CommandStatus(
            isBusy = false,
            message = historyState.message,
            onDismiss = { history.onEvent(HistoryEvent.DismissMessage) },
        )

        PopButton(
            text = stringResource(Res.string.profile_change_password),
            onClick = { onEvent(ProfileEvent.Toggle(ProfileForm.CHANGE_PASSWORD)) },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.PROFILE_CHANGE_PASSWORD),
            style = PopStyle.Outline,
        )
        if (form == ProfileForm.CHANGE_PASSWORD) ChangePasswordForm(state, onEvent)

        PopButton(
            text = stringResource(Res.string.action_log_out),
            onClick = { onEvent(ProfileEvent.LogOut) },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.PROFILE_LOG_OUT),
            style = PopStyle.Outline,
        )
        TextButton(
            onClick = { onEvent(ProfileEvent.Toggle(ProfileForm.DELETE_ACCOUNT)) },
            enabled = !isBusy,
            colors = ButtonDefaults.textButtonColors(contentColor = Palette.PinkInk),
            modifier = Modifier.fillMaxWidth().testTag(TestTags.PROFILE_DELETE),
        ) {
            Text(stringResource(Res.string.profile_delete))
        }
        if (form == ProfileForm.DELETE_ACCOUNT) DeleteAccountForm(state, onEvent)

        CommandStatus(
            isBusy = isBusy,
            message = state.message,
            onDismiss = { onEvent(ProfileEvent.DismissMessage) },
            infoTag = TestTags.PROFILE_PASSWORD_CHANGED,
        )

        // The field test build's consent can be taken back here (nothing in other builds).
        FieldConsentWithdraw()

        Spacer(Modifier.height(24.dp))
        BuildLabel(state.buildLabel)
    }
}

@Composable
private fun ChangePasswordForm(state: ProfileUiState, onEvent: (ProfileEvent) -> Unit) {
    val isBusy = state.isBusy
    PasswordField(
        value = state.currentPassword,
        onValueChange = { onEvent(ProfileEvent.CurrentPasswordChanged(it)) },
        label = stringResource(Res.string.profile_current_password),
        isError = state.showFieldErrors && state.currentPassword.isEmpty(),
        enabled = !isBusy,
        modifier = Modifier.testTag(TestTags.PROFILE_CURRENT_PASSWORD),
    )
    PasswordField(
        value = state.newPassword,
        onValueChange = { onEvent(ProfileEvent.NewPasswordChanged(it)) },
        label = stringResource(Res.string.new_password_label),
        supportingText = stringResource(Res.string.register_password_hint),
        isError = state.showFieldErrors && !state.isNewPasswordValid,
        enabled = !isBusy,
        onImeAction = { onEvent(ProfileEvent.ChangePassword) },
        modifier = Modifier.testTag(TestTags.PROFILE_NEW_PASSWORD),
    )
    PopButton(
        text = stringResource(Res.string.profile_save_password),
        onClick = { onEvent(ProfileEvent.ChangePassword) },
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.PROFILE_SAVE_PASSWORD),
    )
}

/** A clear warning, the password, and a red button: nothing to undo afterwards. */
@Composable
private fun DeleteAccountForm(state: ProfileUiState, onEvent: (ProfileEvent) -> Unit) {
    val isBusy = state.isBusy
    PopCard(
        modifier = Modifier.fillMaxWidth(),
        color = Palette.Pink,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(Res.string.profile_delete_warning), style = MaterialTheme.typography.bodyMedium)
        PasswordField(
            value = state.deletePassword,
            onValueChange = { onEvent(ProfileEvent.DeletePasswordChanged(it)) },
            label = stringResource(Res.string.password_label),
            enabled = !isBusy,
            modifier = Modifier.testTag(TestTags.PROFILE_DELETE_PASSWORD),
        )
        PopButton(
            text = stringResource(Res.string.profile_delete_confirm),
            onClick = { onEvent(ProfileEvent.DeleteAccount) },
            enabled = !isBusy && state.deletePassword.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag(TestTags.PROFILE_DELETE_CONFIRM),
            style = PopStyle.Danger,
        )
    }
}
