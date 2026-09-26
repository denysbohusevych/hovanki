package app.hovanki.client.account

import app.hovanki.client.network.AccountApi
import app.hovanki.client.network.ApiException
import app.hovanki.client.network.ApiResult
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.apiResult
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.SavedAccount
import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.LoginRequest
import app.hovanki.shared.protocol.PasswordResetConfirmRequest
import app.hovanki.shared.protocol.PasswordResetRequest
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.rules.AccountRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The player's account on this device (docs/adr/0004-accounts-friends-chat.md): who is logged in, and the account
 * commands. App-scoped like [app.hovanki.client.session.GameSessionManager]; runs on the main thread.
 *
 * The account is saved in [storage] (key `account`, token in Keystore/Keychain) and comes back with [restore] at app
 * start. Whenever the server answers 401 to a call with the token (session revoked or expired), the account is
 * forgotten locally and [AccountState.sessionExpired] is set: the player logs in again.
 *
 * An account works as soon as it is registered. Confirming the email with the emailed code ([verifyEmail]) is optional:
 * it only shows that the address reaches the player, who can then reset a forgotten password.
 *
 * Commands never throw; they return an [ApiResult] for the UI to map to a message, and update [state] on success.
 */
class AccountManager(
    private val api: AccountApi,
    private val storage: ClientStorage,
    private val serverUrl: ServerUrl,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : AccountCredentials {
    private val mutableState = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = mutableState.asStateFlow()

    /** The logged-in account with its token; the token stays out of [state], which the UI passes around. */
    private val current = MutableStateFlow<SavedAccount?>(null)
    private var restoreAttempted = false
    private var runningCommands = 0

    override val accountToken: String?
        get() = current.value?.token

    /**
     * [accountToken] as it changes (login, logout, another account; not when the email is confirmed): for the social
     * state that depends on it.
     */
    internal val tokens: Flow<String?> = current.map { it?.token }.distinctUntilChanged()

    /**
     * Comes back logged in as the account saved by an earlier run, if any, then refreshes its profile with `GET /me`
     * in the background (401: logged out; no network: the saved profile stays). An account saved for another server
     * is dropped. Call once at app start, after the server address is final; later calls do nothing.
     */
    fun restore() {
        if (restoreAttempted) return
        restoreAttempted = true
        val saved = storage.loadAccount()
        val usable = saved?.takeIf { ServerUrl.normalize(it.serverUrl) == serverUrl.value }
        if (saved != null && usable == null) storage.clearAccount()
        current.value = usable
        mutableState.update { it.copy(user = usable?.user, isRestored = true) }
        if (usable != null) scope.launch { refresh() }
    }

    /**
     * Drops a saved account without restoring it; for UI automation that must start logged out. The server session
     * is ended best effort.
     */
    fun forgetSavedAccount() {
        if (current.value != null) return
        val saved = storage.loadAccount() ?: return
        storage.clearAccount()
        endServerSession(saved.token)
    }

    /** Reloads the profile (e.g. the email was confirmed meanwhile). */
    suspend fun refresh(): ApiResult<Unit> = withToken(busy = false) { token -> updateUser(token, api.me(token)) }

    /**
     * New account, logged in and usable right away. The code to confirm the email (optional, [verifyEmail]) goes to
     * [email].
     */
    suspend fun register(nickname: String, email: String, password: String, language: String): ApiResult<Unit> =
        command {
            val request = RegisterRequest(
                nickname = nickname.trim(),
                email = AccountRules.normalizeEmail(email),
                password = password,
                language = AccountRules.language(language),
            )
            logInWith(api.register(request))
        }

    /** Confirms the email with the emailed code (whenever the player likes: the account works either way). */
    suspend fun verifyEmail(code: String): ApiResult<Unit> =
        withToken { token -> updateUser(token, api.verifyEmail(token, AccountRules.normalizeCode(code))) }

    /** Emails a new verification code (rate limited: [ErrorReason.TOO_MANY_REQUESTS]). */
    suspend fun resendCode(): ApiResult<Unit> = withToken { token -> api.resendCode(token) }

    /**
     * Fixes a mistyped email before it is confirmed; a new code goes there. Needs the current [password]
     * ([ErrorReason.WRONG_CREDENTIALS] when wrong): whoever controls the email can reset the password.
     */
    suspend fun changeEmail(email: String, password: String): ApiResult<Unit> = withToken { token ->
        updateUser(token, api.changeEmail(token, AccountRules.normalizeEmail(email), password))
    }

    /** [login] is the email or the nickname. */
    suspend fun logIn(login: String, password: String): ApiResult<Unit> =
        command { logInWith(api.logIn(LoginRequest(login.trim(), password))) }

    /** Emails a reset code; succeeds whether or not the address has an account. */
    suspend fun requestPasswordReset(email: String): ApiResult<Unit> =
        command { api.requestPasswordReset(PasswordResetRequest(AccountRules.normalizeEmail(email))) }

    /** Sets a new password with the emailed code and logs in; other devices are logged out, the email is confirmed. */
    suspend fun resetPassword(email: String, code: String, newPassword: String): ApiResult<Unit> = command {
        val request = PasswordResetConfirmRequest(
            email = AccountRules.normalizeEmail(email),
            code = AccountRules.normalizeCode(code),
            newPassword = newPassword,
        )
        logInWith(api.confirmPasswordReset(request))
    }

    /** Logs out right away; the server is told in the background (best effort). */
    fun logOut() {
        val token = current.value?.token ?: return
        forget(sessionExpired = false)
        endServerSession(token)
    }

    /** Other devices of the account are logged out; this one stays. */
    suspend fun changePassword(currentPassword: String, newPassword: String): ApiResult<Unit> =
        withToken { token -> api.changePassword(token, currentPassword, newPassword) }

    /** Deletes the account for good (friends, groups, requests); logged out afterwards. */
    suspend fun deleteAccount(password: String): ApiResult<Unit> = withToken { token ->
        api.deleteAccount(token, password)
        if (current.value?.token == token) forget(sessionExpired = false)
    }

    /** The player has seen the "logged out, log in again" notice. */
    fun dismissSessionExpired() {
        mutableState.update { it.copy(sessionExpired = false) }
    }

    override fun onTokenRejected(token: String) {
        if (current.value?.token == token) forget(sessionExpired = true)
    }

    /** Why a command that needs an account can't run right now (logged out), as the server would put it; null: it can. */
    internal fun missingAccount(): ApiResult.Rejected? = NOT_LOGGED_IN.takeIf { current.value == null }

    private fun logInWith(session: AccountSession) {
        val saved = SavedAccount(serverUrl.value, session.token, session.user)
        storage.saveAccount(saved)
        current.value = saved
        mutableState.update { it.copy(user = session.user, isRestored = true, sessionExpired = false) }
    }

    /** A fresh profile from the server, unless the player logged out or in as someone else meanwhile. */
    private fun updateUser(token: String, user: UserProfile) {
        val account = current.value?.takeIf { it.token == token } ?: return
        val updated = account.copy(user = user)
        if (updated != account) storage.saveAccount(updated)
        current.value = updated
        mutableState.update { it.copy(user = user) }
    }

    private fun forget(sessionExpired: Boolean) {
        storage.clearAccount()
        current.value = null
        mutableState.update { it.copy(user = null, isRestored = true, sessionExpired = sessionExpired) }
    }

    private fun endServerSession(token: String) {
        scope.launch {
            try {
                api.logOut(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The session expires on the server by itself; nothing the player could do about it.
            }
        }
    }

    /** A command with the account token; a 401 means the session is gone: logged out. */
    private suspend fun <T> withToken(busy: Boolean = true, call: suspend (token: String) -> T): ApiResult<T> {
        val token = current.value?.token ?: return NOT_LOGGED_IN
        return command(busy, onRejected = { if (it.status == UNAUTHORIZED) onTokenRejected(token) }) { call(token) }
    }

    private suspend fun <T> command(
        busy: Boolean = true,
        onRejected: (ApiException) -> Unit = {},
        call: suspend () -> T,
    ): ApiResult<T> {
        if (busy) setBusy(+1)
        return try {
            apiResult(onRejected, call)
        } finally {
            if (busy) setBusy(-1)
        }
    }

    private fun setBusy(change: Int) {
        runningCommands += change
        mutableState.update { it.copy(isBusy = runningCommands > 0) }
    }

    private companion object {
        const val UNAUTHORIZED = 401

        val NOT_LOGGED_IN = ApiResult.Rejected(ErrorCode.FORBIDDEN, ErrorReason.ACCOUNT_REQUIRED, "Not logged in")
    }
}

/** Who is logged in on this device, as the UI needs it. */
data class AccountState(
    /** The logged-in account as the server last described it; null: logged out (a guest). */
    val user: UserProfile? = null,
    /** False until [AccountManager.restore] ran: whether someone is logged in is not known yet. */
    val isRestored: Boolean = false,
    /** A command is running: show progress, ignore repeated taps. */
    val isBusy: Boolean = false,
    /**
     * Logged out because the server no longer accepts the session (expired, or revoked by a password reset on another
     * device): tell the player to log in again. Cleared by the next login or [AccountManager.dismissSessionExpired].
     */
    val sessionExpired: Boolean = false,
) {
    /** Logged in: games under the nickname, friends, groups, invites. The email need not be confirmed for any of it. */
    val isLoggedIn: Boolean get() = user != null

    /** Logged in with an email that is not confirmed yet; confirming it is optional (the app offers it). */
    val hasUnconfirmedEmail: Boolean get() = user?.emailVerified == false

    /** Logged in with a confirmed email: it is known to reach the player (password resets). */
    val hasConfirmedEmail: Boolean get() = user?.emailVerified == true
}
