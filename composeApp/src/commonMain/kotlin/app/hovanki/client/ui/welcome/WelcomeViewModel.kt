package app.hovanki.client.ui.welcome

import androidx.compose.ui.text.intl.Locale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.BuildInfo
import app.hovanki.client.account.AccountManager
import app.hovanki.client.automation.LaunchOptions
import app.hovanki.client.automation.LaunchOptionsHolder
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.code_format
import app.hovanki.client.resources.code_sent
import app.hovanki.client.resources.error_invalid_email
import app.hovanki.client.resources.error_invalid_password
import app.hovanki.client.resources.form_check_fields
import app.hovanki.client.resources.login_missing
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.client.ui.common.GameStarter
import app.hovanki.client.ui.common.Notice
import app.hovanki.client.ui.common.StartProblem
import app.hovanki.shared.rules.AccountRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The welcome screen (logged out): log in, register, reset a forgotten password, or join a friend's game as a guest.
 * Once logged in or registered, the app shows the main or the email verification screen by itself (App.kt).
 *
 * One state for the whole screen, [uiState], and one way in, [onEvent] (docs/architecture.md, «Состояние экрана»).
 * The forms ([WelcomeLocal]) change the state at once, on the caller's thread: fast typing loses no characters.
 */
class WelcomeViewModel(
    private val account: AccountManager,
    private val sessionManager: GameSessionManager,
    private val launchOptions: LaunchOptionsHolder,
    private val storage: ClientStorage,
    buildInfo: BuildInfo,
) : ViewModel() {
    private val starter = GameStarter(sessionManager, launchOptions, viewModelScope)
    private val commands = CommandRunner(viewModelScope)
    private val buildLabel = buildInfo.label

    private var local = WelcomeLocal(guestName = storage.playerName.orEmpty())
    private var inputs = currentInputs()
    private val mutableUiState = MutableStateFlow(build())
    val uiState: StateFlow<WelcomeUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            val commandState = combine(commands.message, commands.isBusy) { message, busy -> message to busy }
            combine(
                account.state,
                commandState,
                starter.status,
                sessionManager.state.map { it.lastError },
            ) { accountState, (message, busy), startStatus, sessionError ->
                WelcomeInputs(accountState, message, busy, startStatus, sessionError)
            }.collect {
                inputs = it
                publish()
            }
        }
        // Debug builds under UI automation: start parameters prefill the form (see LaunchOptions).
        viewModelScope.launch { launchOptions.options.filterNotNull().collect(::prefill) }
    }

    fun onEvent(event: WelcomeEvent) {
        when (event) {
            is WelcomeEvent.Open -> open(event.mode)
            WelcomeEvent.Back -> back()
            is WelcomeEvent.LoginChanged -> update { it.copy(login = event.value) }
            is WelcomeEvent.LoginPasswordChanged -> update { it.copy(loginPassword = event.value) }
            is WelcomeEvent.NicknameChanged -> update { it.copy(nickname = nicknameInput(event.value)) }
            is WelcomeEvent.EmailChanged -> update { it.copy(email = emailInput(event.value)) }
            is WelcomeEvent.RegisterPasswordChanged -> update { it.copy(registerPassword = passwordInput(event.value)) }
            is WelcomeEvent.ResetEmailChanged -> update { it.copy(resetEmail = emailInput(event.value)) }
            is WelcomeEvent.ResetCodeChanged -> update { it.copy(resetCode = codeInput(event.value)) }
            is WelcomeEvent.ResetPasswordChanged -> update { it.copy(resetPassword = passwordInput(event.value)) }
            is WelcomeEvent.GuestNameChanged -> onGuestNameChange(event.value)
            is WelcomeEvent.JoinCodeChanged -> onJoinCodeChange(event.value)
            WelcomeEvent.LogIn -> logIn()
            WelcomeEvent.Register -> register()
            WelcomeEvent.RequestPasswordReset -> requestPasswordReset()
            WelcomeEvent.ResendResetCode -> resendResetCode()
            WelcomeEvent.ConfirmPasswordReset -> confirmPasswordReset()
            WelcomeEvent.CheckGuestForm -> checkGuestForm()
            WelcomeEvent.JoinAsGuest -> joinAsGuest()
            WelcomeEvent.DismissMessage -> commands.dismiss()
            WelcomeEvent.DismissStartProblems -> starter.dismissProblems()
            WelcomeEvent.DismissSessionExpired -> account.dismissSessionExpired()
        }
    }

    private fun currentInputs() = WelcomeInputs(
        account = account.state.value,
        message = commands.message.value,
        isBusy = commands.isBusy.value,
        startStatus = starter.status.value,
        sessionError = sessionManager.state.value.lastError,
    )

    private fun build() = buildWelcomeState(inputs, local, buildLabel)

    private fun publish() {
        mutableUiState.value = build()
    }

    /** Changes the screen's own part; the state follows at once. */
    private fun update(change: (WelcomeLocal) -> WelcomeLocal) {
        local = change(local)
        publish()
    }

    private fun open(mode: WelcomeMode) {
        update {
            val resetEmail = if (mode == WelcomeMode.RESET_REQUEST && it.resetEmail.isBlank() && '@' in it.login) {
                it.login.trim()
            } else {
                it.resetEmail
            }
            it.copy(mode = mode, resetEmail = resetEmail, showFieldErrors = false)
        }
        commands.dismiss()
    }

    private fun back() {
        open(
            when (local.mode) {
                WelcomeMode.RESET_CONFIRM -> WelcomeMode.RESET_REQUEST
                WelcomeMode.RESET_REQUEST -> WelcomeMode.LOG_IN
                else -> WelcomeMode.START
            },
        )
    }

    private fun onGuestNameChange(value: String) {
        update { it.copy(guestName = value.take(GameStarter.MAX_NAME_LENGTH)) }
    }

    private fun onJoinCodeChange(value: String) {
        update { it.copy(joinCode = GameStarter.joinCodeInput(value)) }
    }

    private fun logIn() {
        val login = local.login
        val password = local.loginPassword
        if (login.isBlank() || password.isEmpty()) {
            commands.show(Notice.Text(Res.string.login_missing))
            return
        }
        commands.execute({ account.logIn(login, password) }) { clearForms() }
    }

    private fun register() {
        update { it.copy(showFieldErrors = true) }
        val state = uiState.value
        if (!state.isNicknameValid || !state.isEmailValid || !state.isRegisterPasswordValid) {
            commands.show(Notice.Text(Res.string.form_check_fields))
            return
        }
        // The emails come in the app's language.
        commands.execute({
            account.register(state.nickname, state.email, state.registerPassword, Locale.current.language)
        }) {
            clearForms()
        }
    }

    /** The code goes to the reset email (the answer is the same for unknown addresses). */
    private fun requestPasswordReset() {
        val email = local.resetEmail
        if (!AccountRules.isValidEmail(email)) {
            commands.show(Notice.Text(Res.string.error_invalid_email))
            return
        }
        commands.execute({ account.requestPasswordReset(email) }) { open(WelcomeMode.RESET_CONFIRM) }
    }

    private fun resendResetCode() {
        val email = local.resetEmail
        commands.execute({ account.requestPasswordReset(email) }) {
            commands.show(Notice.Text(Res.string.code_sent), isError = false)
        }
    }

    private fun confirmPasswordReset() {
        update { it.copy(showFieldErrors = true) }
        val state = uiState.value
        when {
            !AccountRules.isCodeFormat(state.resetCode) -> commands.show(Notice.Text(Res.string.code_format))

            !state.isResetPasswordValid -> commands.show(Notice.Text(Res.string.error_invalid_password))

            else -> commands.execute({
                account.resetPassword(state.resetEmail, state.resetCode, state.resetPassword)
            }) { clearForms() }
        }
    }

    /** Checks the guest form before the location permission is requested; shows what is missing. */
    private fun checkGuestForm() {
        if (!starter.validate(local.guestName.isNotBlank(), StartProblem.NAME_MISSING)) return
        starter.validate(local.joinCode.isNotBlank(), StartProblem.CODE_MISSING)
    }

    private fun joinAsGuest() {
        val name = local.guestName.trim()
        starter.join(local.joinCode, name) {
            storage.rememberPlayer(name)
            update { it.copy(joinCode = "") }
        }
    }

    private fun prefill(options: LaunchOptions) {
        options.playerName?.let { name ->
            // With a password it is the login the app logs in with at start (onAppStart), else a guest's name.
            if (options.password != null) update { it.copy(login = name) } else onGuestNameChange(name)
        }
        options.joinCode?.let(::onJoinCodeChange)
    }

    /** Logged in: the forms start empty next time (after a logout), without the passwords and codes. */
    private fun clearForms() {
        update {
            it.copy(
                mode = WelcomeMode.START,
                showFieldErrors = false,
                loginPassword = "",
                nickname = "",
                email = "",
                registerPassword = "",
                resetCode = "",
                resetPassword = "",
            )
        }
    }

    /** What the fields take: at most as long as the server accepts, the code digits only. */
    private companion object {
        fun nicknameInput(value: String) = value.take(AccountRules.NICKNAME_MAX_LENGTH)

        fun emailInput(value: String) = value.take(AccountRules.EMAIL_MAX_LENGTH)

        fun passwordInput(value: String) = value.take(AccountRules.PASSWORD_MAX_LENGTH)

        fun codeInput(value: String) = value.filter { it.isDigit() }.take(AccountRules.CODE_LENGTH)
    }
}
