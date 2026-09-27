package app.hovanki.server.game

import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import java.time.Duration

/**
 * Expected, client-visible failure. Mapped to [app.hovanki.shared.protocol.ApiError] by the API layer: the HTTP status
 * follows [code], except [ErrorReason.TOO_MANY_REQUESTS] (429, `Retry-After` from [retryAfterSeconds]).
 */
class GameException(
    val code: ErrorCode,
    message: String,
    val reason: ErrorReason? = null,
    /** For [ErrorReason.TOO_MANY_REQUESTS]: when to try again. */
    val retryAfterSeconds: Long? = null,
) : RuntimeException(message) {
    companion object {
        /** A rate limit was hit; the client may try again after [retryAfter]. */
        fun tooManyRequests(retryAfter: Duration, message: String = "Too many requests, try again later") =
            GameException(
                ErrorCode.WRONG_STATE,
                message,
                ErrorReason.TOO_MANY_REQUESTS,
                // Whole seconds, rounded up: never "try again in 0 seconds".
                retryAfterSeconds = maxOf(1, (retryAfter.toMillis() + 999) / 1000),
            )
    }
}
