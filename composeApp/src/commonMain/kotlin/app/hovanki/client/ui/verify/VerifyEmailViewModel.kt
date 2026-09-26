package app.hovanki.client.ui.verify

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.account.AccountManager
import app.hovanki.client.account.AccountState
import app.hovanki.client.network.ApiResult
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.code_format
import app.hovanki.client.resources.code_sent
import app.hovanki.client.resources.error_invalid_email
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.client.ui.common.Notice
import app.hovanki.shared.rules.AccountRules
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Logged in with an email that is not confirmed yet: the 6-digit code from the email, sending it again (at most once a
 * minute), fixing a mistyped address, or logging out. Once confirmed, the app shows the main screen by itself.
 */
class VerifyEmailViewModel(private val account: AccountManager) : ViewModel() {
    private val commands = CommandRunner(viewModelScope)
    private var countdown: Job? = null

    var code by mutableStateOf("")
        private set

    /** The inline "change the email" field is open. */
    var isChangingEmail by mutableStateOf(false)
        private set
    var newEmail by mutableStateOf("")
        private set

    private val mutableResendSeconds = MutableStateFlow(0L)

    /** Seconds until the code may be sent again; 0: now. */
    val resendSecondsLeft: StateFlow<Long> = mutableResendSeconds.asStateFlow()

    val accountState: StateFlow<AccountState> = account.state
    val message: StateFlow<FormMessage?> = commands.message
    val isBusy: StateFlow<Boolean> = commands.isBusy

    init {
        viewModelScope.launch {
            account.state.map { state -> state.user?.takeIf { !it.emailVerified }?.id }
                .distinctUntilChanged()
                .collect { unconfirmed ->
                    // Another account to confirm (just registered, or logged in): its code was sent a moment ago.
                    if (unconfirmed != null) {
                        code = ""
                        isChangingEmail = false
                        commands.dismiss()
                        startCountdown(RESEND_INTERVAL_SECONDS)
                    }
                }
        }
    }

    fun onCodeChange(value: String) {
        code = value.filter { it.isDigit() }.take(AccountRules.CODE_LENGTH)
    }

    fun onNewEmailChange(value: String) {
        newEmail = value.take(AccountRules.EMAIL_MAX_LENGTH)
    }

    fun verify() {
        if (!AccountRules.isCodeFormat(code)) {
            commands.show(Notice.Text(Res.string.code_format))
            return
        }
        commands.execute({ account.verifyEmail(code) }) { code = "" }
    }

    fun resend() {
        if (mutableResendSeconds.value > 0) return
        commands.execute({ account.resendCode() }, onFailure = ::waitAfterRateLimit) {
            commands.show(Notice.Text(Res.string.code_sent), isError = false)
            startCountdown(RESEND_INTERVAL_SECONDS)
        }
    }

    fun startChangingEmail() {
        newEmail = account.state.value.user?.email.orEmpty()
        isChangingEmail = true
        commands.dismiss()
    }

    fun cancelChangingEmail() {
        isChangingEmail = false
    }

    /** The code goes to the new address; the old code no longer works. */
    fun saveEmail() {
        if (!AccountRules.isValidEmail(newEmail)) {
            commands.show(Notice.Text(Res.string.error_invalid_email))
            return
        }
        commands.execute({ account.changeEmail(newEmail) }, onFailure = ::waitAfterRateLimit) {
            isChangingEmail = false
            code = ""
            commands.show(Notice.Text(Res.string.code_sent), isError = false)
            startCountdown(RESEND_INTERVAL_SECONDS)
        }
    }

    fun logOut() = account.logOut()

    fun dismissMessage() = commands.dismiss()

    /** A rate limit says how long to wait: the resend button counts that down. */
    private fun waitAfterRateLimit(result: ApiResult<*>) {
        val seconds = (result as? ApiResult.Rejected)?.retryAfterSeconds ?: return
        startCountdown(seconds)
    }

    private fun startCountdown(seconds: Long) {
        countdown?.cancel()
        mutableResendSeconds.value = seconds.coerceAtLeast(0)
        countdown = viewModelScope.launch {
            while (mutableResendSeconds.value > 0) {
                delay(1_000)
                mutableResendSeconds.value = (mutableResendSeconds.value - 1).coerceAtLeast(0)
            }
        }
    }

    private companion object {
        /** The server sends at most one code a minute per account. */
        const val RESEND_INTERVAL_SECONDS = 60L
    }
}
