package app.hovanki.client.ui.welcome

import app.hovanki.client.account.AccountState
import app.hovanki.client.session.SessionError
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.client.ui.common.StartStatus
import app.hovanki.shared.rules.AccountRules

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

/** Everything the welcome screen shows (docs/architecture.md, «Состояние экрана»). */
data class WelcomeUiState(
    val mode: WelcomeMode = WelcomeMode.START,
    val login: String = "",
    val loginPassword: String = "",
    val nickname: String = "",
    val email: String = "",
    val registerPassword: String = "",
    val resetEmail: String = "",
    val resetCode: String = "",
    val resetPassword: String = "",
    val guestName: String = "",
    val joinCode: String = "",
    /** Invalid fields are highlighted after the first attempt to submit, not while the player is still typing. */
    val showFieldErrors: Boolean = false,
    val isNicknameValid: Boolean = false,
    val isEmailValid: Boolean = false,
    val isRegisterPasswordValid: Boolean = false,
    val isResetPasswordValid: Boolean = false,
    /** The guest's name and the code are there: joining asks for the location permission first. */
    val isGuestFormComplete: Boolean = false,
    /** A command runs, or the app logs in with the launch options' account (debug builds). */
    val isBusy: Boolean = false,
    val sessionExpired: Boolean = false,
    val message: FormMessage? = null,
    val startStatus: StartStatus = StartStatus(),
    val sessionError: SessionError? = null,
    /** Version, build number and commit, shown small at the bottom so testers can name the build. */
    val buildLabel: String = "",
)

/** What the player does on the welcome screen: everything goes through [WelcomeViewModel.onEvent]. */
sealed interface WelcomeEvent {
    data class Open(val mode: WelcomeMode) : WelcomeEvent

    /** One step back (the system back action); on the first step there is nothing to go back to. */
    data object Back : WelcomeEvent

    data class LoginChanged(val value: String) : WelcomeEvent

    data class LoginPasswordChanged(val value: String) : WelcomeEvent

    data class NicknameChanged(val value: String) : WelcomeEvent

    data class EmailChanged(val value: String) : WelcomeEvent

    data class RegisterPasswordChanged(val value: String) : WelcomeEvent

    data class ResetEmailChanged(val value: String) : WelcomeEvent

    data class ResetCodeChanged(val value: String) : WelcomeEvent

    data class ResetPasswordChanged(val value: String) : WelcomeEvent

    data class GuestNameChanged(val value: String) : WelcomeEvent

    data class JoinCodeChanged(val value: String) : WelcomeEvent

    data object LogIn : WelcomeEvent

    data object Register : WelcomeEvent

    /** First step of a password reset: the code goes to the reset email. */
    data object RequestPasswordReset : WelcomeEvent

    data object ResendResetCode : WelcomeEvent

    /** Second step: the emailed code and a new password; logs in. */
    data object ConfirmPasswordReset : WelcomeEvent

    /**
     * «Join» on the guest form, before the location permission is asked: shows what is missing. The screen asks for
     * the permission only when [WelcomeUiState.isGuestFormComplete], then sends [JoinAsGuest].
     */
    data object CheckGuestForm : WelcomeEvent

    data object JoinAsGuest : WelcomeEvent

    data object DismissMessage : WelcomeEvent

    data object DismissStartProblems : WelcomeEvent

    data object DismissSessionExpired : WelcomeEvent
}

/** What the screen gets from outside: the account, the commands' progress, the start of a game. */
internal data class WelcomeInputs(
    val account: AccountState = AccountState(),
    val message: FormMessage? = null,
    val isBusy: Boolean = false,
    val startStatus: StartStatus = StartStatus(),
    val sessionError: SessionError? = null,
)

/**
 * The screen's own part: the step and the forms. Passwords and codes are cleared once used; the guest's name is kept
 * between launches.
 */
internal data class WelcomeLocal(
    val mode: WelcomeMode = WelcomeMode.START,
    val login: String = "",
    val loginPassword: String = "",
    val nickname: String = "",
    val email: String = "",
    val registerPassword: String = "",
    val resetEmail: String = "",
    val resetCode: String = "",
    val resetPassword: String = "",
    val guestName: String = "",
    val joinCode: String = "",
    val showFieldErrors: Boolean = false,
)

internal fun buildWelcomeState(inputs: WelcomeInputs, local: WelcomeLocal, buildLabel: String) = WelcomeUiState(
    mode = local.mode,
    login = local.login,
    loginPassword = local.loginPassword,
    nickname = local.nickname,
    email = local.email,
    registerPassword = local.registerPassword,
    resetEmail = local.resetEmail,
    resetCode = local.resetCode,
    resetPassword = local.resetPassword,
    guestName = local.guestName,
    joinCode = local.joinCode,
    showFieldErrors = local.showFieldErrors,
    isNicknameValid = AccountRules.isValidNickname(local.nickname.trim()),
    isEmailValid = AccountRules.isValidEmail(local.email),
    isRegisterPasswordValid = AccountRules.isValidPassword(local.registerPassword),
    isResetPasswordValid = AccountRules.isValidPassword(local.resetPassword),
    isGuestFormComplete = local.guestName.isNotBlank() && local.joinCode.isNotBlank(),
    isBusy = inputs.isBusy || inputs.account.isBusy,
    sessionExpired = inputs.account.sessionExpired,
    message = inputs.message,
    startStatus = inputs.startStatus,
    sessionError = inputs.sessionError,
    buildLabel = buildLabel,
)
