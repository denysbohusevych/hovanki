package app.hovanki.client.ui.welcome

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.automation.TestTags
import app.hovanki.client.location.rememberLocationPermissionRequester
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_dismiss
import app.hovanki.client.resources.app_name
import app.hovanki.client.resources.code_label
import app.hovanki.client.resources.error_session_expired
import app.hovanki.client.resources.home_code_label
import app.hovanki.client.resources.home_join
import app.hovanki.client.resources.home_location_note
import app.hovanki.client.resources.home_name_label
import app.hovanki.client.resources.home_tagline
import app.hovanki.client.resources.login_forgot
import app.hovanki.client.resources.login_login_label
import app.hovanki.client.resources.login_submit
import app.hovanki.client.resources.login_title
import app.hovanki.client.resources.new_password_label
import app.hovanki.client.resources.password_label
import app.hovanki.client.resources.register_email_hint
import app.hovanki.client.resources.register_email_label
import app.hovanki.client.resources.register_nickname_hint
import app.hovanki.client.resources.register_nickname_label
import app.hovanki.client.resources.register_password_hint
import app.hovanki.client.resources.register_submit
import app.hovanki.client.resources.register_title
import app.hovanki.client.resources.resend_code
import app.hovanki.client.resources.reset_code_sent
import app.hovanki.client.resources.reset_send_code
import app.hovanki.client.resources.reset_submit
import app.hovanki.client.resources.reset_text
import app.hovanki.client.resources.reset_title
import app.hovanki.client.resources.welcome_account_hint
import app.hovanki.client.resources.welcome_guest_hint
import app.hovanki.client.resources.welcome_guest_title
import app.hovanki.client.resources.welcome_log_in
import app.hovanki.client.resources.welcome_register
import app.hovanki.client.ui.common.BackButton
import app.hovanki.client.ui.common.Banner
import app.hovanki.client.ui.common.BuildLabel
import app.hovanki.client.ui.common.CommandStatus
import app.hovanki.client.ui.common.PasswordField
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.StartStatusBanners
import app.hovanki.client.ui.common.SystemBackHandler
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** Logged out: log in, register, reset the password, or join a friend's game as a guest. */
@Composable
fun WelcomeScreen(viewModel: WelcomeViewModel = koinViewModel()) {
    val account by viewModel.accountState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isCommandBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    // Also busy while the app logs in with the launch options' account (debug builds).
    val isBusy = isCommandBusy || account.isBusy
    val mode = viewModel.mode
    SystemBackHandler(enabled = mode != WelcomeMode.START, onBack = viewModel::back)

    ScreenColumn(modifier = Modifier.testTag(TestTags.WELCOME_SCREEN)) {
        if (mode == WelcomeMode.START) {
            Text(
                text = stringResource(Res.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(text = stringResource(Res.string.home_tagline), style = MaterialTheme.typography.bodyLarge)
        } else {
            BackButton(onClick = viewModel::back, modifier = Modifier.testTag(TestTags.FORM_BACK))
        }
        if (account.sessionExpired) {
            Banner(
                text = stringResource(Res.string.error_session_expired),
                modifier = Modifier.testTag(TestTags.WELCOME_SESSION_EXPIRED),
                actionLabel = stringResource(Res.string.action_dismiss),
                onAction = viewModel::dismissSessionExpired,
            )
        }

        when (mode) {
            WelcomeMode.START -> StartContent(viewModel, isBusy)
            WelcomeMode.LOG_IN -> LoginForm(viewModel, isBusy)
            WelcomeMode.REGISTER -> RegisterForm(viewModel, isBusy)
            WelcomeMode.RESET_REQUEST -> ResetRequestForm(viewModel, isBusy)
            WelcomeMode.RESET_CONFIRM -> ResetConfirmForm(viewModel, isBusy)
        }
        CommandStatus(isBusy = isBusy, message = message, onDismiss = viewModel::dismissMessage)

        Spacer(Modifier.height(24.dp))
        BuildLabel(viewModel.buildLabel)
    }
}

/** Log in or register, and below it the guest's way into a game. */
@Composable
private fun StartContent(viewModel: WelcomeViewModel, isBusy: Boolean) {
    val startStatus by viewModel.startStatus.collectAsStateWithLifecycle()
    val sessionError by viewModel.sessionError.collectAsStateWithLifecycle()
    // Asked before joining, so location is already on when the round starts.
    val requestLocationThenJoin = rememberLocationPermissionRequester { viewModel.joinAsGuest() }
    val isStarting = startStatus.isBusy

    Button(
        onClick = { viewModel.open(WelcomeMode.LOG_IN) },
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.WELCOME_LOG_IN),
    ) {
        Text(stringResource(Res.string.welcome_log_in))
    }
    OutlinedButton(
        onClick = { viewModel.open(WelcomeMode.REGISTER) },
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.WELCOME_REGISTER),
    ) {
        Text(stringResource(Res.string.welcome_register))
    }
    SecondaryText(stringResource(Res.string.welcome_account_hint))

    HorizontalDivider()
    Text(text = stringResource(Res.string.welcome_guest_title), style = MaterialTheme.typography.titleMedium)
    SecondaryText(stringResource(Res.string.welcome_guest_hint))
    OutlinedTextField(
        value = viewModel.guestName,
        onValueChange = viewModel::onGuestNameChange,
        label = { Text(stringResource(Res.string.home_name_label)) },
        singleLine = true,
        enabled = !isStarting,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_NAME),
    )
    OutlinedTextField(
        value = viewModel.joinCode,
        onValueChange = viewModel::onJoinCodeChange,
        label = { Text(stringResource(Res.string.home_code_label)) },
        singleLine = true,
        enabled = !isStarting,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
        ),
        modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_JOIN_CODE),
    )
    FilledTonalButton(
        onClick = { if (viewModel.canJoinAsGuest()) requestLocationThenJoin() },
        enabled = !isStarting,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_JOIN),
    ) {
        Text(stringResource(Res.string.home_join))
    }
    SecondaryText(stringResource(Res.string.home_location_note))
    StartStatusBanners(status = startStatus, sessionError = sessionError, onDismiss = viewModel::dismissStartProblems)
}

@Composable
private fun LoginForm(viewModel: WelcomeViewModel, isBusy: Boolean) {
    FormTitle(stringResource(Res.string.login_title))
    OutlinedTextField(
        value = viewModel.login,
        onValueChange = viewModel::onLoginChange,
        label = { Text(stringResource(Res.string.login_login_label)) },
        singleLine = true,
        enabled = !isBusy,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth().testTag(TestTags.LOGIN_LOGIN),
    )
    PasswordField(
        value = viewModel.loginPassword,
        onValueChange = viewModel::onLoginPasswordChange,
        label = stringResource(Res.string.password_label),
        enabled = !isBusy,
        onImeAction = viewModel::logIn,
        modifier = Modifier.testTag(TestTags.LOGIN_PASSWORD),
    )
    Button(
        onClick = viewModel::logIn,
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.LOGIN_SUBMIT),
    ) {
        Text(stringResource(Res.string.login_submit))
    }
    TextButton(
        onClick = { viewModel.open(WelcomeMode.RESET_REQUEST) },
        enabled = !isBusy,
        modifier = Modifier.testTag(TestTags.LOGIN_FORGOT),
    ) {
        Text(stringResource(Res.string.login_forgot))
    }
}

@Composable
private fun RegisterForm(viewModel: WelcomeViewModel, isBusy: Boolean) {
    val showErrors = viewModel.showFieldErrors
    FormTitle(stringResource(Res.string.register_title))
    OutlinedTextField(
        value = viewModel.nickname,
        onValueChange = viewModel::onNicknameChange,
        label = { Text(stringResource(Res.string.register_nickname_label)) },
        supportingText = { Text(stringResource(Res.string.register_nickname_hint)) },
        isError = showErrors && !viewModel.isNicknameValid,
        singleLine = true,
        enabled = !isBusy,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag(TestTags.REGISTER_NICKNAME),
    )
    OutlinedTextField(
        value = viewModel.email,
        onValueChange = viewModel::onEmailChange,
        label = { Text(stringResource(Res.string.register_email_label)) },
        supportingText = { Text(stringResource(Res.string.register_email_hint)) },
        isError = showErrors && !viewModel.isEmailValid,
        singleLine = true,
        enabled = !isBusy,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth().testTag(TestTags.REGISTER_EMAIL),
    )
    PasswordField(
        value = viewModel.registerPassword,
        onValueChange = viewModel::onRegisterPasswordChange,
        label = stringResource(Res.string.password_label),
        supportingText = stringResource(Res.string.register_password_hint),
        isError = showErrors && !viewModel.isRegisterPasswordValid,
        enabled = !isBusy,
        onImeAction = viewModel::register,
        modifier = Modifier.testTag(TestTags.REGISTER_PASSWORD),
    )
    Button(
        onClick = viewModel::register,
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.REGISTER_SUBMIT),
    ) {
        Text(stringResource(Res.string.register_submit))
    }
}

@Composable
private fun ResetRequestForm(viewModel: WelcomeViewModel, isBusy: Boolean) {
    FormTitle(stringResource(Res.string.reset_title))
    Text(text = stringResource(Res.string.reset_text), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = viewModel.resetEmail,
        onValueChange = viewModel::onResetEmailChange,
        label = { Text(stringResource(Res.string.register_email_label)) },
        singleLine = true,
        enabled = !isBusy,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { viewModel.requestPasswordReset() }),
        modifier = Modifier.fillMaxWidth().testTag(TestTags.RESET_EMAIL),
    )
    Button(
        onClick = viewModel::requestPasswordReset,
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.RESET_SEND_CODE),
    ) {
        Text(stringResource(Res.string.reset_send_code))
    }
}

@Composable
private fun ResetConfirmForm(viewModel: WelcomeViewModel, isBusy: Boolean) {
    val showErrors = viewModel.showFieldErrors
    FormTitle(stringResource(Res.string.reset_title))
    Text(
        text = stringResource(Res.string.reset_code_sent, viewModel.resetEmail.trim()),
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = viewModel.resetCode,
        onValueChange = viewModel::onResetCodeChange,
        label = { Text(stringResource(Res.string.code_label)) },
        singleLine = true,
        enabled = !isBusy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag(TestTags.RESET_CODE),
    )
    PasswordField(
        value = viewModel.resetPassword,
        onValueChange = viewModel::onResetPasswordChange,
        label = stringResource(Res.string.new_password_label),
        supportingText = stringResource(Res.string.register_password_hint),
        isError = showErrors && !viewModel.isResetPasswordValid,
        enabled = !isBusy,
        onImeAction = viewModel::confirmPasswordReset,
        modifier = Modifier.testTag(TestTags.RESET_PASSWORD),
    )
    Button(
        onClick = viewModel::confirmPasswordReset,
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.RESET_SUBMIT),
    ) {
        Text(stringResource(Res.string.reset_submit))
    }
    TextButton(onClick = viewModel::resendResetCode, enabled = !isBusy) {
        Text(stringResource(Res.string.resend_code))
    }
}

@Composable
private fun FormTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.headlineSmall)
}
