package app.hovanki.client.ui.verify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.account.AccountManager
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Confirming the email, which is optional: the account works either way. The main screen offers it (a card on «Play»
 * until «Later», the status on «Profile») and opens a panel: the 6-digit code from the email, sending it again (at
 * most once a minute), fixing a mistyped address with the current password. Once the email is confirmed the panel
 * closes by itself and the main screen says so briefly.
 *
 * One state, [uiState], and one way in, [onEvent] (docs/architecture.md, «Состояние экрана»); the fields
 * ([VerifyEmailLocal]) change the state at once, on the caller's thread.
 */
class VerifyEmailViewModel(private val account: AccountManager) : ViewModel() {
    private val commands = CommandRunner(viewModelScope)
    private var countdown: Job? = null

    private var local = VerifyEmailLocal()
    private var email = currentEmail()
    private var message: FormMessage? = commands.message.value
    private var isBusy = commands.isBusy.value
    private val mutableUiState = MutableStateFlow(build())
    val uiState: StateFlow<VerifyEmailUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(account.state, commands.message, commands.isBusy) { _, message, busy -> message to busy }
                .collect { (message, busy) ->
                    email = currentEmail()
                    this@VerifyEmailViewModel.message = message
                    isBusy = busy
                    publish()
                }
        }
        // Confirmed (with the code here, or meanwhile on another device and reloaded): the panel has done its job.
        viewModelScope.launch {
            account.state.map { it.hasConfirmedEmail }.distinctUntilChanged().collect { confirmed ->
                if (confirmed && local.isOpen) {
                    close()
                    update { it.copy(showConfirmed = true) }
                }
            }
        }
        // Another account on this phone: its own card, panel and countdown.
        viewModelScope.launch {
            account.state.map { it.user?.id }.distinctUntilChanged().drop(1).collect {
                close()
                countdown?.cancel()
                update { it.copy(isCardDismissed = false, showConfirmed = false, resendSecondsLeft = 0) }
            }
        }
    }

    fun onEvent(event: VerifyEmailEvent) {
        when (event) {
            VerifyEmailEvent.Open -> open()
            VerifyEmailEvent.Close -> close()
            VerifyEmailEvent.DismissCard -> update { it.copy(isCardDismissed = true) }
            VerifyEmailEvent.DismissConfirmed -> update { it.copy(showConfirmed = false) }
            is VerifyEmailEvent.CodeChanged -> update { it.copy(code = codeInput(event.value)) }
            is VerifyEmailEvent.NewEmailChanged -> update { it.copy(newEmail = emailInput(event.value)) }
            is VerifyEmailEvent.PasswordChanged -> update { it.copy(password = event.value) }
            VerifyEmailEvent.Verify -> verify()
            VerifyEmailEvent.Resend -> resend()
            VerifyEmailEvent.StartChangingEmail -> startChangingEmail()
            VerifyEmailEvent.CancelChangingEmail -> update { it.copy(isChangingEmail = false, password = "") }
            VerifyEmailEvent.SaveEmail -> saveEmail()
            VerifyEmailEvent.DismissMessage -> commands.dismiss()
        }
    }

    private fun currentEmail() = account.state.value.user?.email.orEmpty()

    private fun build() = VerifyEmailUiState(
        isOpen = local.isOpen,
        isCardDismissed = local.isCardDismissed,
        showConfirmed = local.showConfirmed,
        email = email,
        code = local.code,
        isChangingEmail = local.isChangingEmail,
        newEmail = local.newEmail,
        password = local.password,
        resendSecondsLeft = local.resendSecondsLeft,
        message = message,
        isBusy = isBusy,
    )

    private fun publish() {
        mutableUiState.value = build()
    }

    /** Changes the panel's own part; the state follows at once. */
    private fun update(change: (VerifyEmailLocal) -> VerifyEmailLocal) {
        local = change(local)
        publish()
    }

    private fun open() {
        update { it.copy(isOpen = true) }
        commands.dismiss()
    }

    private fun close() {
        update { it.copy(isOpen = false, isChangingEmail = false, code = "", password = "") }
        commands.dismiss()
    }

    /** The panel closes by itself once the email is confirmed (see init). */
    private fun verify() {
        val code = local.code
        if (!AccountRules.isCodeFormat(code)) {
            commands.show(Notice.Text(Res.string.code_format))
            return
        }
        commands.execute({ account.verifyEmail(code) })
    }

    private fun resend() {
        if (local.resendSecondsLeft > 0) return
        commands.execute({ account.resendCode() }, onFailure = ::waitAfterRateLimit) {
            commands.show(Notice.Text(Res.string.code_sent), isError = false)
            startCountdown(RESEND_INTERVAL_SECONDS)
        }
    }

    private fun startChangingEmail() {
        update { it.copy(newEmail = currentEmail(), password = "", isChangingEmail = true) }
        commands.dismiss()
    }

    private fun saveEmail() {
        val newEmail = local.newEmail
        val password = local.password
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
            update { it.copy(isChangingEmail = false, code = "", password = "") }
            commands.show(Notice.Text(Res.string.code_sent), isError = false)
            startCountdown(RESEND_INTERVAL_SECONDS)
        }
    }

    /** A rate limit says how long to wait: the resend button counts that down. */
    private fun waitAfterRateLimit(result: ApiResult<*>) {
        val seconds = (result as? ApiResult.Rejected)?.retryAfterSeconds ?: return
        startCountdown(seconds)
    }

    private fun startCountdown(seconds: Long) {
        countdown?.cancel()
        update { it.copy(resendSecondsLeft = seconds.coerceAtLeast(0)) }
        countdown = viewModelScope.launch {
            while (local.resendSecondsLeft > 0) {
                delay(1_000)
                update { it.copy(resendSecondsLeft = (it.resendSecondsLeft - 1).coerceAtLeast(0)) }
            }
        }
    }

    private companion object {
        fun codeInput(value: String) = value.filter { it.isDigit() }.take(AccountRules.CODE_LENGTH)

        fun emailInput(value: String) = value.take(AccountRules.EMAIL_MAX_LENGTH)

        /** The server sends at most one code a minute per account. */
        const val RESEND_INTERVAL_SECONDS = 60L
    }
}
