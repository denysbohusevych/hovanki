package app.hovanki.client.ui.welcome

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.intl.Locale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.BuildInfo
import app.hovanki.client.account.AccountManager
import app.hovanki.client.account.AccountState
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
import app.hovanki.client.session.SessionError
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.client.ui.common.GameStarter
import app.hovanki.client.ui.common.Notice
import app.hovanki.client.ui.common.StartProblem
import app.hovanki.client.ui.common.StartStatus
import app.hovanki.shared.rules.AccountRules
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The welcome screen (logged out): log in, register, reset a forgotten password, or join a friend's game as a guest.
 * Once logged in or registered, the app shows the main or the email verification screen by itself (App.kt).
 */
class WelcomeViewModel(
    private val account: AccountManager,
    sessionManager: GameSessionManager,
    private val launchOptions: LaunchOptionsHolder,
    private val storage: ClientStorage,
    buildInfo: BuildInfo,
) : ViewModel() {
    private val starter = GameStarter(sessionManager, launchOptions, viewModelScope)
    private val commands = CommandRunner(viewModelScope)

    var mode by mutableStateOf(WelcomeMode.START)
        private set

    // Text field values are Compose state rather than StateFlow: text fields need synchronous updates, otherwise fast
    // typing can lose characters. Passwords and codes are cleared once used; the guest's name is kept between launches.
    var login by mutableStateOf("")
        private set
    var loginPassword by mutableStateOf("")
        private set
    var nickname by mutableStateOf("")
        private set
    var email by mutableStateOf("")
        private set
    var registerPassword by mutableStateOf("")
        private set
    var resetEmail by mutableStateOf("")
        private set
    var resetCode by mutableStateOf("")
        private set
    var resetPassword by mutableStateOf("")
        private set
    var guestName by mutableStateOf(storage.playerName.orEmpty())
        private set
    var joinCode by mutableStateOf("")
        private set

    /** Invalid fields are highlighted after the first attempt to submit, not while the player is still typing. */
    var showFieldErrors by mutableStateOf(false)
        private set

    val isNicknameValid: Boolean get() = AccountRules.isValidNickname(nickname.trim())
    val isEmailValid: Boolean get() = AccountRules.isValidEmail(email)
    val isRegisterPasswordValid: Boolean get() = AccountRules.isValidPassword(registerPassword)
    val isResetPasswordValid: Boolean get() = AccountRules.isValidPassword(resetPassword)

    val accountState: StateFlow<AccountState> = account.state
    val message: StateFlow<FormMessage?> = commands.message
    val isBusy: StateFlow<Boolean> = commands.isBusy
    val startStatus: StateFlow<StartStatus> = starter.status
    val sessionError: StateFlow<SessionError?> = sessionManager.state
        .map { it.lastError }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), sessionManager.state.value.lastError)

    /** Version, build number and commit, shown small at the bottom so testers can name the build. */
    val buildLabel: String = buildInfo.label

    init {
        // Debug builds under UI automation: start parameters prefill the form (see LaunchOptions).
        viewModelScope.launch { launchOptions.options.filterNotNull().collect(::prefill) }
    }

    fun open(mode: WelcomeMode) {
        if (mode == WelcomeMode.RESET_REQUEST && resetEmail.isBlank() && '@' in login) resetEmail = login.trim()
        this.mode = mode
        showFieldErrors = false
        commands.dismiss()
    }

    /** One step back (the system back action); on the first step there is nothing to go back to. */
    fun back() {
        open(
            when (mode) {
                WelcomeMode.RESET_CONFIRM -> WelcomeMode.RESET_REQUEST
                WelcomeMode.RESET_REQUEST -> WelcomeMode.LOG_IN
                else -> WelcomeMode.START
            },
        )
    }

    fun onLoginChange(value: String) {
        login = value
    }

    fun onLoginPasswordChange(value: String) {
        loginPassword = value
    }

    fun onNicknameChange(value: String) {
        nickname = value.take(AccountRules.NICKNAME_MAX_LENGTH)
    }

    fun onEmailChange(value: String) {
        email = value.take(AccountRules.EMAIL_MAX_LENGTH)
    }

    fun onRegisterPasswordChange(value: String) {
        registerPassword = value.take(AccountRules.PASSWORD_MAX_LENGTH)
    }

    fun onResetEmailChange(value: String) {
        resetEmail = value.take(AccountRules.EMAIL_MAX_LENGTH)
    }

    fun onResetCodeChange(value: String) {
        resetCode = value.filter { it.isDigit() }.take(AccountRules.CODE_LENGTH)
    }

    fun onResetPasswordChange(value: String) {
        resetPassword = value.take(AccountRules.PASSWORD_MAX_LENGTH)
    }

    fun onGuestNameChange(value: String) {
        guestName = value.take(GameStarter.MAX_NAME_LENGTH)
    }

    fun onJoinCodeChange(value: String) {
        joinCode = GameStarter.joinCodeInput(value)
    }

    fun logIn() {
        if (login.isBlank() || loginPassword.isEmpty()) {
            commands.show(Notice.Text(Res.string.login_missing))
            return
        }
        commands.execute({ account.logIn(login, loginPassword) }) { clearForms() }
    }

    fun register() {
        showFieldErrors = true
        if (!isNicknameValid || !isEmailValid || !isRegisterPasswordValid) {
            commands.show(Notice.Text(Res.string.form_check_fields))
            return
        }
        // The emails come in the app's language.
        commands.execute({ account.register(nickname, email, registerPassword, Locale.current.language) }) {
            clearForms()
        }
    }

    /** First step of a password reset: the code goes to [resetEmail] (the answer is the same for unknown addresses). */
    fun requestPasswordReset() {
        if (!AccountRules.isValidEmail(resetEmail)) {
            commands.show(Notice.Text(Res.string.error_invalid_email))
            return
        }
        commands.execute({ account.requestPasswordReset(resetEmail) }) { open(WelcomeMode.RESET_CONFIRM) }
    }

    fun resendResetCode() {
        commands.execute({ account.requestPasswordReset(resetEmail) }) {
            commands.show(Notice.Text(Res.string.code_sent), isError = false)
        }
    }

    /** Second step: the emailed code and a new password; logs in. */
    fun confirmPasswordReset() {
        showFieldErrors = true
        when {
            !AccountRules.isCodeFormat(resetCode) -> commands.show(Notice.Text(Res.string.code_format))
            !isResetPasswordValid -> commands.show(Notice.Text(Res.string.error_invalid_password))
            else -> commands.execute({ account.resetPassword(resetEmail, resetCode, resetPassword) }) { clearForms() }
        }
    }

    /** Checks the guest form before the location permission is requested; shows what is missing. */
    fun canJoinAsGuest(): Boolean = starter.validate(guestName.isNotBlank(), StartProblem.NAME_MISSING) &&
        starter.validate(joinCode.isNotBlank(), StartProblem.CODE_MISSING)

    fun joinAsGuest() {
        val name = guestName.trim()
        starter.join(joinCode, name) {
            storage.rememberPlayer(name)
            joinCode = ""
        }
    }

    fun dismissMessage() = commands.dismiss()

    fun dismissStartProblems() = starter.dismissProblems()

    fun dismissSessionExpired() = account.dismissSessionExpired()

    private fun prefill(options: LaunchOptions) {
        options.playerName?.let { name ->
            // With a password it is the login the app logs in with at start (onAppStart), else a guest's name.
            if (options.password != null) onLoginChange(name) else onGuestNameChange(name)
        }
        options.joinCode?.let(::onJoinCodeChange)
    }

    /** Logged in: the forms start empty next time (after a logout), without the passwords and codes. */
    private fun clearForms() {
        mode = WelcomeMode.START
        showFieldErrors = false
        loginPassword = ""
        nickname = ""
        email = ""
        registerPassword = ""
        resetCode = ""
        resetPassword = ""
    }
}

/** The welcome screen's step. */
enum class WelcomeMode {
    /** Log in, register, or join as a guest. */
    START,
    LOG_IN,
    REGISTER,

    /** Forgot the password: which email to send the code to. */
    RESET_REQUEST,

    /** The code from the email and a new password. */
    RESET_CONFIRM,
}
