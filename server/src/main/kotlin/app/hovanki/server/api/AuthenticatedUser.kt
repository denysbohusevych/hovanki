package app.hovanki.server.api

import app.hovanki.server.account.AccountService
import app.hovanki.server.game.GameException
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.UserId
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * The account behind the request's `Authorization: Bearer <account token>`, as a controller parameter
 * ([UserArgumentResolver]):
 * - non-null parameter: the route needs an account. No token → 401 `UNAUTHORIZED`;
 * - nullable parameter: the account is optional (game create/join). No token → null;
 * - either way, an unknown, revoked or expired token → 401 [ErrorReason.SESSION_EXPIRED], and an unconfirmed email →
 *   403 [ErrorReason.EMAIL_NOT_VERIFIED] unless the handler is annotated [AllowUnverifiedEmail].
 */
data class AuthenticatedUser(
    val userId: UserId,
    /** Identifies this device's session (logout, "log out the other devices"). */
    val tokenHash: String,
    val emailVerified: Boolean,
)

/** The handler also serves accounts whose email is not confirmed yet (me, verify, resend, logout, ...). */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class AllowUnverifiedEmail

class UserArgumentResolver(private val accounts: AccountService) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == AuthenticatedUser::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): AuthenticatedUser? {
        val token = webRequest.bearerToken()
        if (token == null) {
            // Kotlin nullability: `user: AuthenticatedUser?` is optional.
            if (parameter.isOptional) return null
            throw GameException(ErrorCode.UNAUTHORIZED, "Missing bearer token")
        }
        val user = accounts.authenticate(token)
        if (!user.emailVerified && !parameter.hasMethodAnnotation(AllowUnverifiedEmail::class.java)) {
            throw GameException(ErrorCode.FORBIDDEN, "Confirm your email first", ErrorReason.EMAIL_NOT_VERIFIED)
        }
        return user
    }
}
