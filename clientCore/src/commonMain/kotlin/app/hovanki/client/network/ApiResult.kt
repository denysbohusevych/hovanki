package app.hovanki.client.network

import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import kotlinx.coroutines.CancellationException

/**
 * Outcome of a command of [app.hovanki.client.account.AccountManager] or [app.hovanki.client.social.SocialManager]:
 * they never throw, the UI maps the result to a message.
 */
sealed interface ApiResult<out T> {
    data class Success<out T>(val value: T) : ApiResult<T>

    /**
     * The server refused (or the command needs an account first). Decide on [code] and [reason]; [message] is the
     * server's explanation in English, for logs rather than for players.
     */
    data class Rejected(
        val code: ErrorCode?,
        val reason: ErrorReason?,
        val message: String,
        /** Rate limits ([ErrorReason.TOO_MANY_REQUESTS]): seconds until it may be tried again, if the server said. */
        val retryAfterSeconds: Long? = null,
    ) : ApiResult<Nothing>

    /** The server could not be reached or answered with something unreadable. */
    data class Network(val details: String?) : ApiResult<Nothing>
}

val ApiResult<*>.isSuccess: Boolean get() = this is ApiResult.Success

/** Runs a call: its value, or what went wrong. [onRejected] sees every [ApiException] first (e.g. 401 → log out). */
internal suspend fun <T> apiResult(onRejected: (ApiException) -> Unit = {}, call: suspend () -> T): ApiResult<T> = try {
    ApiResult.Success(call())
} catch (e: CancellationException) {
    throw e
} catch (e: ApiException) {
    onRejected(e)
    ApiResult.Rejected(e.error?.code, e.reason, e.error?.message ?: e.message.orEmpty(), e.retryAfterSeconds)
} catch (e: Exception) {
    ApiResult.Network(e.message)
}
