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
import app.hovanki.client.resources.error_wrong_password
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Confirming the email, which is optional: the account works either way. The main screen offers it (a card on «Play»
 * until «Later», the status on «Profile») and opens a panel: the 6-digit code from the email, sending it again (at
 * most once a minute), fixing a mistyped address with the current password. Once the email is confirmed the panel
 * closes by itself and the main screen says so briefly.
 */
class VerifyEmailViewModel(private val account: AccountManager) : ViewModel() {
    private val commands = CommandRunner(viewModelScope)
    private var countdown: Job? = null

    /** The panel is open. */
    var isOpen by mutableStateOf(false)
        private set

    /** «Later» on the card: it stays hidden until the app starts again (or another account logs in). */
    var isCardDismissed by mutableStateOf(false)
        private set

    /** The email was just confirmed in the panel: the main screen shows a short notice. */
    var showConfirmed by mutableStateOf(false)
        private set

    // Compose state: text fields need synchronous updates. Cleared when the panel closes.
    var code by mutableStateOf("")
        private set

    /** The inline "change the email" form is open. */
    var isChangingEmail by mutableStateOf(false)
        private set
    var newEmail by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set

    private val mutableResendSeconds = MutableStateFlow(0L)

    /** Seconds until the code may be sent again; 0: now (the server says when it was too soon). */
    val resendSecondsLeft: StateFlow<Long> = mutableResendSeconds.asStateFlow()

    val accountState: StateFlow<AccountState> = account.state
    val message: StateFlow<FormMessage?> = commands.message
    val isBusy: StateFlow<Boolean> = commands.isBusy

    init {
        // Confirmed (with the code here, or meanwhile on another device and reloaded): the panel has done its job.
        viewModelScope.launch {
            account.state.map { it.hasConfirmedEmail }.distinctUntilChanged().collect { confirmed ->
                if (confirmed && isOpen) {
                    close()
                    showConfirmed = true
                }
            }
        }
        // Another account on this phone: its own card, panel and countdown.
        viewModelScope.launch {
            account.state.map { it.user?.id }.distinctUntilChanged().drop(1).collect {
                close()
                isCardDismissed = false
                showConfirmed = false
                countdown?.cancel()
                mutableResendSeconds.value = 0
            }
        }
    }

    fun open() {
        isOpen = true
        commands.dismiss()
    }

    fun close() {
        isOpen = false
        isChangingEmail = false
        code = ""
        password = ""
        commands.dismiss()
    }

    fun dismissCard() {
        isCardDismissed = true
    }

    fun dismissConfirmed() {
        showConfirmed = false
    }

    fun onCodeChange(value: String) {
        code = value.filter { it.isDigit() }.take(AccountRules.CODE_LENGTH)
    }

    fun onNewEmailChange(value: String) {
        newEmail = value.take(AccountRules.EMAIL_MAX_LENGTH)
    }

    fun onPasswordChange(value: String) {
        password = value
    }

    /** The panel closes by itself once the email is confirmed (see init). */
    fun verify() {
        if (!AccountRules.isCodeFormat(code)) {
            commands.show(Notice.Text(Res.string.code_format))
            return
        }
        commands.execute({ account.verifyEmail(code) })
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
        password = ""
        isChangingEmail = true
        commands.dismiss()
    }

    fun cancelChangingEmail() {
        isChangingEmail = false
        password = ""
    }

    /** The code goes to the new address; the old code no longer works. Needs the current password. */
    fun saveEmail() {
        if (!AccountRules.isValidEmail(newEmail)) {
            commands.show(Notice.Text(Res.string.error_invalid_email))
            return
        }
        if (password.isEmpty()) return
        commands.execute(
            command = { account.changeEmail(newEmail, password) },
            wrongCredentials = Res.string.error_wrong_password,
            onFailure = ::waitAfterRateLimit,
        ) {
            isChangingEmail = false
            code = ""
            password = ""
            commands.show(Notice.Text(Res.string.code_sent), isError = false)
            startCountdown(RESEND_INTERVAL_SECONDS)
        }
    }

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
