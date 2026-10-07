package app.hovanki.client.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.BuildInfo
import app.hovanki.client.account.AccountManager
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.error_invalid_password
import app.hovanki.client.resources.error_wrong_password
import app.hovanki.client.resources.profile_password_changed
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.client.ui.common.Notice
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.rules.AccountRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * «Profile»: who is logged in, the city of the city leaderboard, change the password, log out, delete the account.
 *
 * One state for the whole screen, [uiState], and one way in, [onEvent] (docs/architecture.md, «Состояние экрана»).
 * The forms' fields change the state at once, on the caller's thread: a text field's edit is there before the next
 * frame.
 */
class ProfileViewModel(private val account: AccountManager, buildInfo: BuildInfo) : ViewModel() {
    private val commands = CommandRunner(viewModelScope)

    private val mutableUiState = MutableStateFlow(
        ProfileUiState(
            user = account.state.value.user,
            message = commands.message.value,
            isBusy = commands.isBusy.value,
            buildLabel = buildInfo.label,
        ),
    )
    val uiState: StateFlow<ProfileUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(account.state, commands.message, commands.isBusy) { accountState, message, busy ->
                Triple(accountState.user, message, busy)
            }.collect { (user, message, busy) ->
                mutableUiState.update { it.copy(user = user, message = message, isBusy = busy) }
            }
        }
    }

    fun onEvent(event: ProfileEvent) {
        when (event) {
            is ProfileEvent.Toggle -> toggle(event.form)

            ProfileEvent.CloseForm -> closeForm()

            is ProfileEvent.CurrentPasswordChanged -> mutableUiState.update { it.copy(currentPassword = event.value) }

            is ProfileEvent.NewPasswordChanged -> mutableUiState.update {
                it.copy(newPassword = event.value.take(AccountRules.PASSWORD_MAX_LENGTH))
            }

            is ProfileEvent.DeletePasswordChanged -> mutableUiState.update { it.copy(deletePassword = event.value) }

            ProfileEvent.ChangePassword -> changePassword()

            ProfileEvent.LogOut -> logOut()

            ProfileEvent.DeleteAccount -> deleteAccount()

            ProfileEvent.DismissMessage -> commands.dismiss()

            is ProfileEvent.PickingCity -> mutableUiState.update { it.copy(pickingCity = event.open) }

            is ProfileEvent.PickCity -> {
                mutableUiState.update { it.copy(pickingCity = false) }
                commands.execute(command = { account.setCity(event.city) })
            }
        }
    }

    /** Opens [form], or closes it when it is open already. */
    private fun toggle(form: ProfileForm) {
        val opening = uiState.value.form != form
        closeForm()
        if (opening) mutableUiState.update { it.copy(form = form) }
        commands.dismiss()
    }

    /** The fields are cleared whenever a form closes. */
    private fun closeForm() {
        mutableUiState.update {
            it.copy(form = null, showFieldErrors = false, currentPassword = "", newPassword = "", deletePassword = "")
        }
    }

    /** Other devices of the account are logged out; this one stays. */
    private fun changePassword() {
        mutableUiState.update { it.copy(showFieldErrors = true) }
        val state = uiState.value
        if (state.currentPassword.isEmpty()) return
        if (!state.isNewPasswordValid) {
            commands.show(Notice.Text(Res.string.error_invalid_password))
            return
        }
        commands.execute(
            command = { account.changePassword(state.currentPassword, state.newPassword) },
            wrongCredentials = Res.string.error_wrong_password,
        ) {
            closeForm()
            commands.show(Notice.Text(Res.string.profile_password_changed), isError = false)
        }
    }

    private fun logOut() {
        closeForm()
        commands.dismiss()
        account.logOut()
    }

    /** Deletes the account for good; the app then shows the welcome screen. */
    private fun deleteAccount() {
        val password = uiState.value.deletePassword
        if (password.isEmpty()) return
        commands.execute(
            command = { account.deleteAccount(password) },
            wrongCredentials = Res.string.error_wrong_password,
        ) { closeForm() }
    }
}

/** What «Profile» shows. */
data class ProfileUiState(
    /** The logged-in account; null: logged out, nothing to show. */
    val user: UserProfile? = null,
    /** The open form; null: none. */
    val form: ProfileForm? = null,
    val currentPassword: String = "",
    val newPassword: String = "",
    val deletePassword: String = "",
    /** The new password is highlighted when invalid after the first attempt, not while it is being typed. */
    val showFieldErrors: Boolean = false,
    val message: FormMessage? = null,
    val isBusy: Boolean = false,
    /** The city picker of the city leaderboard is open (docs/adr/0022-city-leaderboard.md). */
    val pickingCity: Boolean = false,
    /** Version, build number and commit, shown small at the bottom so testers can name the build. */
    val buildLabel: String = "",
) {
    val isNewPasswordValid: Boolean get() = AccountRules.isValidPassword(newPassword)
}

sealed interface ProfileEvent {
    /** Opens the form, or closes it when it is open already. */
    data class Toggle(val form: ProfileForm) : ProfileEvent

    data object CloseForm : ProfileEvent

    data class CurrentPasswordChanged(val value: String) : ProfileEvent

    data class NewPasswordChanged(val value: String) : ProfileEvent

    data class DeletePasswordChanged(val value: String) : ProfileEvent

    data object ChangePassword : ProfileEvent

    data object LogOut : ProfileEvent

    data object DeleteAccount : ProfileEvent

    data object DismissMessage : ProfileEvent

    /** The city picker opens ([open]) or closes. */
    data class PickingCity(val open: Boolean) : ProfileEvent

    /** The player picked [city] (null: none) for the city leaderboard. */
    data class PickCity(val city: String?) : ProfileEvent
}

enum class ProfileForm { CHANGE_PASSWORD, DELETE_ACCOUNT }
