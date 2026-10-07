package app.hovanki.server.api

import app.hovanki.server.account.AccountService
import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ChangeEmailRequest
import app.hovanki.shared.protocol.ChangePasswordRequest
import app.hovanki.shared.protocol.CityRequest
import app.hovanki.shared.protocol.DeleteAccountRequest
import app.hovanki.shared.protocol.LoginRequest
import app.hovanki.shared.protocol.PasswordResetConfirmRequest
import app.hovanki.shared.protocol.PasswordResetRequest
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.VerifyEmailRequest
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Accounts and the caller's own account; all logic lives in [AccountService]. Client IPs (for rate limits) come from
 * `remoteAddr`, which `server.forward-headers-strategy: native` takes from the proxy's `X-Forwarded-For`.
 */
@RestController
class AccountController(private val accounts: AccountService) {
    @PostMapping(ApiRoutes.ACCOUNTS)
    fun register(@RequestBody request: RegisterRequest, http: HttpServletRequest): AccountSession =
        accounts.register(request, http.remoteAddr)

    @PostMapping(ApiRoutes.LOGIN)
    fun login(@RequestBody request: LoginRequest, http: HttpServletRequest): AccountSession =
        accounts.login(request, http.remoteAddr)

    @PostMapping(ApiRoutes.LOGOUT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(user: AuthenticatedUser) = accounts.logout(user)

    /** Always 204 for a well-formed address: nobody learns whether it has an account. */
    @PostMapping(ApiRoutes.PASSWORD_RESET)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun requestPasswordReset(@RequestBody request: PasswordResetRequest, http: HttpServletRequest) =
        accounts.requestPasswordReset(request, http.remoteAddr)

    @PostMapping(ApiRoutes.PASSWORD_RESET_CONFIRM)
    fun confirmPasswordReset(@RequestBody request: PasswordResetConfirmRequest): AccountSession =
        accounts.confirmPasswordReset(request)

    @GetMapping(ApiRoutes.ME)
    fun me(user: AuthenticatedUser): UserProfile = accounts.me(user)

    /** Confirming the email is optional: the account works either way. */
    @PostMapping(ApiRoutes.ME_EMAIL_VERIFY)
    fun verifyEmail(user: AuthenticatedUser, @RequestBody request: VerifyEmailRequest): UserProfile =
        accounts.verifyEmail(user, request)

    @PostMapping(ApiRoutes.ME_EMAIL_RESEND)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun resendCode(user: AuthenticatedUser) = accounts.resendCode(user)

    /** Needs the current password; only while the email is unconfirmed (a typo at registration), 409 afterwards. */
    @PostMapping(ApiRoutes.ME_EMAIL)
    fun changeEmail(user: AuthenticatedUser, @RequestBody request: ChangeEmailRequest): UserProfile =
        accounts.changeEmail(user, request)

    @PostMapping(ApiRoutes.ME_PASSWORD)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePassword(user: AuthenticatedUser, @RequestBody request: ChangePasswordRequest) =
        accounts.changePassword(user, request)

    /** The city of the city leaderboard, the player's own choice; 400 for an id not in `Cities.IDS`. */
    @PostMapping(ApiRoutes.ME_CITY)
    fun setCity(user: AuthenticatedUser, @RequestBody request: CityRequest): UserProfile =
        accounts.setCity(user, request)

    @PostMapping(ApiRoutes.ME_DELETE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(user: AuthenticatedUser, @RequestBody request: DeleteAccountRequest) = accounts.delete(user, request)
}
