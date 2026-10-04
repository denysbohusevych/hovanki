package app.hovanki.client.ui.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
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
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_dismiss
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
import app.hovanki.client.ui.common.Logo
import app.hovanki.client.ui.common.PasswordField
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.PopTextField
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.StartStatusBanners
import app.hovanki.client.ui.common.SystemBackHandler
import app.hovanki.client.ui.common.collectScreenState
import app.hovanki.client.ui.common.rememberLocationRequest
import app.hovanki.client.ui.field.FieldConsentWithdraw
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** Logged out: log in, register, reset the password, or join a friend's game as a guest. */
@Composable
fun WelcomeScreen(viewModel: WelcomeViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectScreenState()
    WelcomeContent(state, viewModel::onEvent)
}

@Composable
private fun WelcomeContent(state: WelcomeUiState, onEvent: (WelcomeEvent) -> Unit) {
    val isBusy = state.isBusy
    val mode = state.mode
    SystemBackHandler(enabled = mode != WelcomeMode.START, onBack = { onEvent(WelcomeEvent.Back) })

    ScreenColumn(modifier = Modifier.testTag(TestTags.WELCOME_SCREEN)) {
        if (mode == WelcomeMode.START) {
            Logo(
                markSize = 48.dp,
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = stringResource(Res.string.home_tagline),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        } else {
            BackButton(onClick = { onEvent(WelcomeEvent.Back) }, modifier = Modifier.testTag(TestTags.FORM_BACK))
        }
        if (state.sessionExpired) {
            Banner(
                text = stringResource(Res.string.error_session_expired),
                modifier = Modifier.testTag(TestTags.WELCOME_SESSION_EXPIRED),
                actionLabel = stringResource(Res.string.action_dismiss),
                onAction = { onEvent(WelcomeEvent.DismissSessionExpired) },
            )
        }

        when (mode) {
            WelcomeMode.START -> StartContent(state, onEvent)
            WelcomeMode.LOG_IN -> LoginForm(state, onEvent)
            WelcomeMode.REGISTER -> RegisterForm(state, onEvent)
            WelcomeMode.RESET_REQUEST -> ResetRequestForm(state, onEvent)
            WelcomeMode.RESET_CONFIRM -> ResetConfirmForm(state, onEvent)
        }
        CommandStatus(isBusy = isBusy, message = state.message, onDismiss = { onEvent(WelcomeEvent.DismissMessage) })

        // The field test build's consent can be taken back by guests too (the profile is for accounts only).
        if (mode == WelcomeMode.START) FieldConsentWithdraw()

        Spacer(Modifier.height(24.dp))
        BuildLabel(state.buildLabel)
    }
}

/** Log in or register, and below it the guest's way into a game. */
@Composable
private fun StartContent(state: WelcomeUiState, onEvent: (WelcomeEvent) -> Unit) {
    val isBusy = state.isBusy
    val startStatus = state.startStatus
    // Asked before joining, so location is already on when the round starts.
    val requestLocationThenJoin = rememberLocationRequest { onEvent(WelcomeEvent.JoinAsGuest) }
    val isStarting = startStatus.isBusy

    PopButton(
        text = stringResource(Res.string.welcome_log_in),
        onClick = { onEvent(WelcomeEvent.Open(WelcomeMode.LOG_IN)) },
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.WELCOME_LOG_IN),
    )
    PopButton(
        text = stringResource(Res.string.welcome_register),
        onClick = { onEvent(WelcomeEvent.Open(WelcomeMode.REGISTER)) },
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.WELCOME_REGISTER),
        style = PopStyle.Outline,
    )
    SecondaryText(stringResource(Res.string.welcome_account_hint))

    PopCard(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = stringResource(Res.string.welcome_guest_title), style = MaterialTheme.typography.titleLarge)
        SecondaryText(stringResource(Res.string.welcome_guest_hint))
        PopTextField(
            value = state.guestName,
            onValueChange = { onEvent(WelcomeEvent.GuestNameChanged(it)) },
            label = { Text(stringResource(Res.string.home_name_label)) },
            singleLine = true,
            enabled = !isStarting,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_NAME),
        )
        PopTextField(
            value = state.joinCode,
            onValueChange = { onEvent(WelcomeEvent.JoinCodeChanged(it)) },
            label = { Text(stringResource(Res.string.home_code_label)) },
            singleLine = true,
            enabled = !isStarting,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
            ),
            modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_JOIN_CODE),
        )
        PopButton(
            text = stringResource(Res.string.home_join),
            onClick = {
                onEvent(WelcomeEvent.CheckGuestForm)
                if (state.isGuestFormComplete) requestLocationThenJoin()
            },
            enabled = !isStarting,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_JOIN),
            style = PopStyle.Hider,
        )
        SecondaryText(stringResource(Res.string.home_location_note))
    }
    StartStatusBanners(
        status = startStatus,
        sessionError = state.sessionError,
        onDismiss = { onEvent(WelcomeEvent.DismissStartProblems) },
    )
}

@Composable
private fun LoginForm(state: WelcomeUiState, onEvent: (WelcomeEvent) -> Unit) {
    val isBusy = state.isBusy
    FormTitle(stringResource(Res.string.login_title))
    PopTextField(
        value = state.login,
        onValueChange = { onEvent(WelcomeEvent.LoginChanged(it)) },
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
        value = state.loginPassword,
        onValueChange = { onEvent(WelcomeEvent.LoginPasswordChanged(it)) },
        label = stringResource(Res.string.password_label),
        enabled = !isBusy,
        onImeAction = { onEvent(WelcomeEvent.LogIn) },
        modifier = Modifier.testTag(TestTags.LOGIN_PASSWORD),
    )
    PopButton(
        text = stringResource(Res.string.login_submit),
        onClick = { onEvent(WelcomeEvent.LogIn) },
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.LOGIN_SUBMIT),
    )
    TextButton(
        onClick = { onEvent(WelcomeEvent.Open(WelcomeMode.RESET_REQUEST)) },
        enabled = !isBusy,
        modifier = Modifier.testTag(TestTags.LOGIN_FORGOT),
    ) {
        Text(stringResource(Res.string.login_forgot))
    }
}

@Composable
private fun RegisterForm(state: WelcomeUiState, onEvent: (WelcomeEvent) -> Unit) {
    val isBusy = state.isBusy
    val showErrors = state.showFieldErrors
    FormTitle(stringResource(Res.string.register_title))
    PopTextField(
        value = state.nickname,
        onValueChange = { onEvent(WelcomeEvent.NicknameChanged(it)) },
        label = { Text(stringResource(Res.string.register_nickname_label)) },
        supportingText = { Text(stringResource(Res.string.register_nickname_hint)) },
        isError = showErrors && !state.isNicknameValid,
        singleLine = true,
        enabled = !isBusy,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag(TestTags.REGISTER_NICKNAME),
    )
    PopTextField(
        value = state.email,
        onValueChange = { onEvent(WelcomeEvent.EmailChanged(it)) },
        label = { Text(stringResource(Res.string.register_email_label)) },
        supportingText = { Text(stringResource(Res.string.register_email_hint)) },
        isError = showErrors && !state.isEmailValid,
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
        value = state.registerPassword,
        onValueChange = { onEvent(WelcomeEvent.RegisterPasswordChanged(it)) },
        label = stringResource(Res.string.password_label),
        supportingText = stringResource(Res.string.register_password_hint),
        isError = showErrors && !state.isRegisterPasswordValid,
        enabled = !isBusy,
        onImeAction = { onEvent(WelcomeEvent.Register) },
        modifier = Modifier.testTag(TestTags.REGISTER_PASSWORD),
    )
    PopButton(
        text = stringResource(Res.string.register_submit),
        onClick = { onEvent(WelcomeEvent.Register) },
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.REGISTER_SUBMIT),
    )
}

@Composable
private fun ResetRequestForm(state: WelcomeUiState, onEvent: (WelcomeEvent) -> Unit) {
    val isBusy = state.isBusy
    FormTitle(stringResource(Res.string.reset_title))
    Text(text = stringResource(Res.string.reset_text), style = MaterialTheme.typography.bodyMedium)
    PopTextField(
        value = state.resetEmail,
        onValueChange = { onEvent(WelcomeEvent.ResetEmailChanged(it)) },
        label = { Text(stringResource(Res.string.register_email_label)) },
        singleLine = true,
        enabled = !isBusy,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onEvent(WelcomeEvent.RequestPasswordReset) }),
        modifier = Modifier.fillMaxWidth().testTag(TestTags.RESET_EMAIL),
    )
    PopButton(
        text = stringResource(Res.string.reset_send_code),
        onClick = { onEvent(WelcomeEvent.RequestPasswordReset) },
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.RESET_SEND_CODE),
    )
}

@Composable
private fun ResetConfirmForm(state: WelcomeUiState, onEvent: (WelcomeEvent) -> Unit) {
    val isBusy = state.isBusy
    val showErrors = state.showFieldErrors
    FormTitle(stringResource(Res.string.reset_title))
    Text(
        text = stringResource(Res.string.reset_code_sent, state.resetEmail.trim()),
        style = MaterialTheme.typography.bodyMedium,
    )
    PopTextField(
        value = state.resetCode,
        onValueChange = { onEvent(WelcomeEvent.ResetCodeChanged(it)) },
        label = { Text(stringResource(Res.string.code_label)) },
        singleLine = true,
        enabled = !isBusy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag(TestTags.RESET_CODE),
    )
    PasswordField(
        value = state.resetPassword,
        onValueChange = { onEvent(WelcomeEvent.ResetPasswordChanged(it)) },
        label = stringResource(Res.string.new_password_label),
        supportingText = stringResource(Res.string.register_password_hint),
        isError = showErrors && !state.isResetPasswordValid,
        enabled = !isBusy,
        onImeAction = { onEvent(WelcomeEvent.ConfirmPasswordReset) },
        modifier = Modifier.testTag(TestTags.RESET_PASSWORD),
    )
    PopButton(
        text = stringResource(Res.string.reset_submit),
        onClick = { onEvent(WelcomeEvent.ConfirmPasswordReset) },
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.RESET_SUBMIT),
    )
    TextButton(onClick = { onEvent(WelcomeEvent.ResendResetCode) }, enabled = !isBusy) {
        Text(stringResource(Res.string.resend_code))
    }
}

@Composable
private fun FormTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.headlineSmall)
}
