package app.hovanki.e2e.devices

import app.hovanki.client.network.AccountApi
import app.hovanki.client.network.HttpAccountApi
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.createHttpClient
import app.hovanki.e2e.bot.BotAccount
import app.hovanki.e2e.observer.EmailPurpose
import app.hovanki.e2e.observer.Observer
import app.hovanki.shared.protocol.RegisterRequest
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Accounts for the players on the devices, made like a person makes one, but through the API instead of the forms:
 * register, read the code in the email (the server's debug route, [Observer.awaitEmail]), confirm it. The app on the
 * device then logs in with it from its launch options (`name` + `password`), so a device run spends no taps on forms
 * the scenarios don't test. The registration's own session is ended again: the device logs in with one of its own.
 *
 * Confirming the email is optional (the account works without it), but the app offers it on the «Play» tab to an
 * account that hasn't: a confirmed account keeps that offer out of the way of the Maestro flows.
 */
class DeviceAccounts(
    private val api: AccountApi,
    /** The code of the next verification email to this address; waits for it (the server sends asynchronously). */
    private val emailedCode: suspend (email: String) -> String,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    /** A new account with a confirmed email for the player [name], with a nickname and email unique to this run. */
    suspend fun create(name: String): BotAccount {
        val account = BotAccount.unique(name)
        val session = api.register(RegisterRequest(account.nickname, account.email, account.password, LANGUAGE))
        val user = api.verifyEmail(session.token, emailedCode(account.email))
        check(user.emailVerified) { "${account.nickname}: the email is still not confirmed" }
        api.logOut(session.token)
        return account
    }

    override fun close() = onClose()

    companion object {
        /** The language of the emails: the devices run in English. */
        const val LANGUAGE = "en"

        /** Over HTTP to the server at [serverUrl] (as this machine reaches it), with the codes from [observer]. */
        fun over(serverUrl: String, observer: Observer): DeviceAccounts {
            val client = createHttpClient(OkHttp.create(), logRequests = false)
            return DeviceAccounts(
                HttpAccountApi(client, ServerUrl(serverUrl)),
                { email -> checkNotNull(observer.awaitEmail(email, EmailPurpose.VERIFY_EMAIL).code) },
                client::close,
            )
        }
    }
}
