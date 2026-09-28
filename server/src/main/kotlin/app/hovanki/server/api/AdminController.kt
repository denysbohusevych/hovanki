package app.hovanki.server.api

import app.hovanki.server.admin.AdminLogin
import app.hovanki.server.admin.AdminProperties
import app.hovanki.server.admin.AdminService
import app.hovanki.server.admin.Staff
import app.hovanki.server.admin.StaffAuthService
import app.hovanki.shared.protocol.AdminAudit
import app.hovanki.shared.protocol.AdminEnrollRequest
import app.hovanki.shared.protocol.AdminEnrollment
import app.hovanki.shared.protocol.AdminFindByEmailRequest
import app.hovanki.shared.protocol.AdminGames
import app.hovanki.shared.protocol.AdminLoginRequest
import app.hovanki.shared.protocol.AdminLoginResponse
import app.hovanki.shared.protocol.AdminMe
import app.hovanki.shared.protocol.AdminReasonRequest
import app.hovanki.shared.protocol.AdminReport
import app.hovanki.shared.protocol.AdminReports
import app.hovanki.shared.protocol.AdminRevealedEmail
import app.hovanki.shared.protocol.AdminSetRoleRequest
import app.hovanki.shared.protocol.AdminStaff
import app.hovanki.shared.protocol.AdminStats
import app.hovanki.shared.protocol.AdminTotpRequest
import app.hovanki.shared.protocol.AdminUserCard
import app.hovanki.shared.protocol.AdminUsers
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.ResolveReportRequest
import app.hovanki.shared.protocol.SanctionKind
import app.hovanki.shared.protocol.SanctionRequest
import app.hovanki.shared.protocol.UserId
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * The staff admin's API (docs/adr/0008-admin.md), used by the /admin page. [AdminGuard] checks the admin is on and the
 * CSRF header; a [Staff] parameter needs a live admin session; the rules are in [AdminService].
 */
@RestController
class AdminController(
    private val auth: StaffAuthService,
    private val admin: AdminService,
    private val properties: AdminProperties,
) {
    @PostMapping(ApiRoutes.ADMIN_LOGIN)
    fun login(@RequestBody request: AdminLoginRequest, http: HttpServletRequest): AdminLoginResponse =
        auth.login(request, http.remoteAddr)

    @PostMapping(ApiRoutes.ADMIN_LOGIN_TOTP)
    fun loginWithCode(@RequestBody request: AdminTotpRequest, response: HttpServletResponse): AdminMe =
        startSession(auth.loginWithCode(request), response)

    @PostMapping(ApiRoutes.ADMIN_ENROLL)
    fun enroll(@RequestBody request: AdminEnrollRequest): AdminEnrollment = auth.enroll(request)

    @PostMapping(ApiRoutes.ADMIN_ENROLL_CONFIRM)
    fun confirmEnrollment(@RequestBody request: AdminTotpRequest, response: HttpServletResponse): AdminMe =
        startSession(auth.confirmEnrollment(request), response)

    @PostMapping(ApiRoutes.ADMIN_LOGOUT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(staff: Staff, response: HttpServletResponse) {
        auth.logout(staff)
        response.addHeader(HttpHeaders.SET_COOKIE, AdminWebConfig.clearedCookie())
    }

    @GetMapping(ApiRoutes.ADMIN_ME)
    fun me(staff: Staff): AdminMe = staff.toMe()

    @GetMapping(ApiRoutes.ADMIN_REPORTS)
    fun reports(
        @Suppress("UNUSED_PARAMETER") staff: Staff,
        @RequestParam(defaultValue = "true") open: Boolean,
        @RequestParam(required = false) before: Long?,
    ): AdminReports = admin.reports(open, before)

    @PostMapping(ApiRoutes.ADMIN_REPORT_RESOLVE)
    fun resolveReport(
        staff: Staff,
        @PathVariable reportId: Long,
        @RequestBody request: ResolveReportRequest,
    ): AdminReport = admin.resolveReport(staff, reportId, request)

    @GetMapping(ApiRoutes.ADMIN_USERS)
    fun searchUsers(@Suppress("UNUSED_PARAMETER") staff: Staff, @RequestParam q: String): AdminUsers =
        admin.searchUsers(q)

    /** POST: the address stays out of URLs and access logs. */
    @PostMapping(ApiRoutes.ADMIN_USERS_BY_EMAIL)
    fun findByEmail(staff: Staff, @RequestBody request: AdminFindByEmailRequest): AdminUsers =
        admin.findByEmail(staff, request)

    @GetMapping(ApiRoutes.ADMIN_USER)
    fun card(@Suppress("UNUSED_PARAMETER") staff: Staff, @PathVariable userId: String): AdminUserCard =
        admin.card(UserId(userId))

    @PostMapping(ApiRoutes.ADMIN_USER_EMAIL)
    fun showEmail(
        staff: Staff,
        @PathVariable userId: String,
        @RequestBody request: AdminReasonRequest,
    ): AdminRevealedEmail = admin.showEmail(staff, UserId(userId), request.reason)

    @PostMapping(ApiRoutes.ADMIN_USER_BAN)
    fun ban(staff: Staff, @PathVariable userId: String, @RequestBody request: SanctionRequest): AdminUserCard =
        admin.ban(staff, UserId(userId), request)

    @PostMapping(ApiRoutes.ADMIN_USER_UNBAN)
    fun unban(staff: Staff, @PathVariable userId: String, @RequestBody request: AdminReasonRequest): AdminUserCard =
        admin.lift(staff, UserId(userId), SanctionKind.BAN, request.reason)

    @PostMapping(ApiRoutes.ADMIN_USER_MUTE)
    fun mute(staff: Staff, @PathVariable userId: String, @RequestBody request: SanctionRequest): AdminUserCard =
        admin.mute(staff, UserId(userId), request)

    @PostMapping(ApiRoutes.ADMIN_USER_UNMUTE)
    fun unmute(staff: Staff, @PathVariable userId: String, @RequestBody request: AdminReasonRequest): AdminUserCard =
        admin.lift(staff, UserId(userId), SanctionKind.MUTE, request.reason)

    @PostMapping(ApiRoutes.ADMIN_USER_RENAME)
    fun rename(staff: Staff, @PathVariable userId: String, @RequestBody request: AdminReasonRequest): AdminUserCard =
        admin.rename(staff, UserId(userId), request.reason)

    @PostMapping(ApiRoutes.ADMIN_USER_LOGOUT)
    fun logoutDevices(
        staff: Staff,
        @PathVariable userId: String,
        @RequestBody request: AdminReasonRequest,
    ): AdminUserCard = admin.logoutDevices(staff, UserId(userId), request.reason)

    @PostMapping(ApiRoutes.ADMIN_USER_DELETE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteAccount(staff: Staff, @PathVariable userId: String, @RequestBody request: AdminReasonRequest) =
        admin.deleteAccount(staff, UserId(userId), request.reason)

    @PostMapping(ApiRoutes.ADMIN_USER_ROLE)
    fun setRole(staff: Staff, @PathVariable userId: String, @RequestBody request: AdminSetRoleRequest): AdminUserCard =
        admin.setRole(staff, UserId(userId), request)

    @PostMapping(ApiRoutes.ADMIN_USER_RESET_TOTP)
    fun resetTotp(staff: Staff, @PathVariable userId: String, @RequestBody request: AdminReasonRequest): AdminUserCard =
        admin.resetTotp(staff, UserId(userId), request.reason)

    @GetMapping(ApiRoutes.ADMIN_GAMES)
    fun games(@Suppress("UNUSED_PARAMETER") staff: Staff): AdminGames = admin.games()

    @PostMapping(ApiRoutes.ADMIN_GAME_END)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun endGame(staff: Staff, @PathVariable gameId: String, @RequestBody request: AdminReasonRequest) =
        admin.endGame(staff, GameId(gameId), request.reason)

    @GetMapping(ApiRoutes.ADMIN_STATS)
    fun stats(@Suppress("UNUSED_PARAMETER") staff: Staff): AdminStats = admin.stats()

    @GetMapping(ApiRoutes.ADMIN_STAFF)
    fun staff(staff: Staff): AdminStaff = admin.staff(staff)

    @GetMapping(ApiRoutes.ADMIN_AUDIT)
    fun audit(staff: Staff, @RequestParam(required = false) before: Long?): AdminAudit = admin.audit(staff, before)

    private fun startSession(login: AdminLogin, response: HttpServletResponse): AdminMe {
        response.addHeader(HttpHeaders.SET_COOKIE, AdminWebConfig.sessionCookie(login.token, properties.sessionMax))
        return login.me
    }
}
