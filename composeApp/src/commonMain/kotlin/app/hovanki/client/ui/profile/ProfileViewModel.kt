package app.hovanki.client.ui.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.BuildInfo
import app.hovanki.client.account.AccountManager
import app.hovanki.client.account.AccountState
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.error_invalid_password
import app.hovanki.client.resources.error_wrong_password
import app.hovanki.client.resources.profile_password_changed
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.client.ui.common.Notice
import app.hovanki.shared.rules.AccountRules
import kotlinx.coroutines.flow.StateFlow

/** «Profile»: who is logged in, change the password, log out, delete the account. */
class ProfileViewModel(private val account: AccountManager, buildInfo: BuildInfo) : ViewModel() {
    private val commands = CommandRunner(viewModelScope)

    /** The open form; null: none. */
    var form by mutableStateOf<ProfileForm?>(null)
        private set

    // Compose state: text fields need synchronous updates. Cleared whenever a form closes.
    var currentPassword by mutableStateOf("")
        private set
    var newPassword by mutableStateOf("")
        private set
    var deletePassword by mutableStateOf("")
        private set

    /** The new password is highlighted when invalid after the first attempt, not while it is being typed. */
    var showFieldErrors by mutableStateOf(false)
        private set

    val isNewPasswordValid: Boolean get() = AccountRules.isValidPassword(newPassword)

    val accountState: StateFlow<AccountState> = account.state
    val message: StateFlow<FormMessage?> = commands.message
    val isBusy: StateFlow<Boolean> = commands.isBusy

    /** Version, build number and commit, shown small at the bottom so testers can name the build. */
    val buildLabel: String = buildInfo.label

    /** Opens [form], or closes it when it is open already. */
    fun toggle(form: ProfileForm) {
        val opening = this.form != form
        closeForm()
        if (opening) this.form = form
        commands.dismiss()
    }

    fun closeForm() {
        form = null
        showFieldErrors = false
        currentPassword = ""
        newPassword = ""
        deletePassword = ""
    }

    fun onCurrentPasswordChange(value: String) {
        currentPassword = value
    }

    fun onNewPasswordChange(value: String) {
        newPassword = value.take(AccountRules.PASSWORD_MAX_LENGTH)
    }

    fun onDeletePasswordChange(value: String) {
        deletePassword = value
    }

    /** Other devices of the account are logged out; this one stays. */
    fun changePassword() {
        showFieldErrors = true
        if (currentPassword.isEmpty()) return
        if (!isNewPasswordValid) {
            commands.show(Notice.Text(Res.string.error_invalid_password))
            return
        }
        commands.execute(
            command = { account.changePassword(currentPassword, newPassword) },
            wrongCredentials = Res.string.error_wrong_password,
        ) {
            closeForm()
            commands.show(Notice.Text(Res.string.profile_password_changed), isError = false)
        }
    }

    fun logOut() {
        closeForm()
        commands.dismiss()
        account.logOut()
    }

    /** Deletes the account for good; the app then shows the welcome screen. */
    fun deleteAccount() {
        if (deletePassword.isEmpty()) return
        commands.execute(
            command = { account.deleteAccount(deletePassword) },
            wrongCredentials = Res.string.error_wrong_password,
        ) { closeForm() }
    }

    fun dismissMessage() = commands.dismiss()
}

enum class ProfileForm { CHANGE_PASSWORD, DELETE_ACCOUNT }
