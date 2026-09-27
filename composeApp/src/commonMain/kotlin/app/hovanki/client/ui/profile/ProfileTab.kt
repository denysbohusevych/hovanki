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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.verify.VerifyEmailViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * «Profile»: nickname and email (not confirmed yet: confirm it here), change the password, log out, delete the account.
 */
@Composable
fun ProfileTab(verify: VerifyEmailViewModel, viewModel: ProfileViewModel = koinViewModel()) {
    val account by viewModel.accountState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val user = account.user ?: return
    val form = viewModel.form
    SystemBackHandler(enabled = form != null, onBack = viewModel::closeForm)

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
                        onClick = verify::open,
                        style = PopStyle.Dark,
                        height = 40.dp,
                        modifier = Modifier.testTag(TestTags.PROFILE_CONFIRM_EMAIL),
                    )
                }
            }
        }

        PopButton(
            text = stringResource(Res.string.profile_change_password),
            onClick = { viewModel.toggle(ProfileForm.CHANGE_PASSWORD) },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.PROFILE_CHANGE_PASSWORD),
            style = PopStyle.Outline,
        )
        if (form == ProfileForm.CHANGE_PASSWORD) ChangePasswordForm(viewModel, isBusy)

        PopButton(
            text = stringResource(Res.string.action_log_out),
            onClick = viewModel::logOut,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.PROFILE_LOG_OUT),
            style = PopStyle.Outline,
        )
        TextButton(
            onClick = { viewModel.toggle(ProfileForm.DELETE_ACCOUNT) },
            enabled = !isBusy,
            colors = ButtonDefaults.textButtonColors(contentColor = Palette.PinkInk),
            modifier = Modifier.fillMaxWidth().testTag(TestTags.PROFILE_DELETE),
        ) {
            Text(stringResource(Res.string.profile_delete))
        }
        if (form == ProfileForm.DELETE_ACCOUNT) DeleteAccountForm(viewModel, isBusy)

        CommandStatus(
            isBusy = isBusy,
            message = message,
            onDismiss = viewModel::dismissMessage,
            infoTag = TestTags.PROFILE_PASSWORD_CHANGED,
        )

        Spacer(Modifier.height(24.dp))
        BuildLabel(viewModel.buildLabel)
    }
}

@Composable
private fun ChangePasswordForm(viewModel: ProfileViewModel, isBusy: Boolean) {
    PasswordField(
        value = viewModel.currentPassword,
        onValueChange = viewModel::onCurrentPasswordChange,
        label = stringResource(Res.string.profile_current_password),
        isError = viewModel.showFieldErrors && viewModel.currentPassword.isEmpty(),
        enabled = !isBusy,
        modifier = Modifier.testTag(TestTags.PROFILE_CURRENT_PASSWORD),
    )
    PasswordField(
        value = viewModel.newPassword,
        onValueChange = viewModel::onNewPasswordChange,
        label = stringResource(Res.string.new_password_label),
        supportingText = stringResource(Res.string.register_password_hint),
        isError = viewModel.showFieldErrors && !viewModel.isNewPasswordValid,
        enabled = !isBusy,
        onImeAction = viewModel::changePassword,
        modifier = Modifier.testTag(TestTags.PROFILE_NEW_PASSWORD),
    )
    PopButton(
        text = stringResource(Res.string.profile_save_password),
        onClick = viewModel::changePassword,
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.PROFILE_SAVE_PASSWORD),
    )
}

/** A clear warning, the password, and a red button: nothing to undo afterwards. */
@Composable
private fun DeleteAccountForm(viewModel: ProfileViewModel, isBusy: Boolean) {
    PopCard(
        modifier = Modifier.fillMaxWidth(),
        color = Palette.Pink,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(Res.string.profile_delete_warning), style = MaterialTheme.typography.bodyMedium)
        PasswordField(
            value = viewModel.deletePassword,
            onValueChange = viewModel::onDeletePasswordChange,
            label = stringResource(Res.string.password_label),
            enabled = !isBusy,
            modifier = Modifier.testTag(TestTags.PROFILE_DELETE_PASSWORD),
        )
        PopButton(
            text = stringResource(Res.string.profile_delete_confirm),
            onClick = viewModel::deleteAccount,
            enabled = !isBusy && viewModel.deletePassword.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag(TestTags.PROFILE_DELETE_CONFIRM),
            style = PopStyle.Danger,
        )
    }
}
