package app.hovanki.server.api

import app.hovanki.server.MutableClock
import app.hovanki.server.account.AccountTestConfig
import app.hovanki.server.account.awaitCode
import app.hovanki.server.account.uniqueName
import app.hovanki.server.mail.EmailPurpose
import app.hovanki.server.mail.EmailSender
import app.hovanki.server.mail.RecordingEmailSender
import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.AdminAction
import app.hovanki.shared.protocol.AdminAudit
import app.hovanki.shared.protocol.AdminEnrollRequest
import app.hovanki.shared.protocol.AdminEnrollment
import app.hovanki.shared.protocol.AdminFindByEmailRequest
import app.hovanki.shared.protocol.AdminGames
import app.hovanki.shared.protocol.AdminLoginRequest
import app.hovanki.shared.protocol.AdminLoginResponse
import app.hovanki.shared.protocol.AdminLoginStep
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
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.LoginRequest
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.ReportAction
import app.hovanki.shared.protocol.ResolveReportRequest
import app.hovanki.shared.protocol.SanctionKind
import app.hovanki.shared.protocol.SanctionRequest
import app.hovanki.shared.protocol.SendChatRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserRole
import app.hovanki.shared.protocol.VerifyEmailRequest
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.shrinkingZone
import app.hovanki.shared.totp.Totp
import jakarta.servlet.http.Cookie
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.io.ByteArrayOutputStream
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The staff admin over HTTP (docs/adr/0008-admin.md): the login, the session cookie, the rules of who may do what. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountTestConfig::class)
class AdminApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired emailSender: EmailSender,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcClient,
) {
    private val emails = emailSender as RecordingEmailSender

    @Test
    fun staffLogInWithThePasswordAndTheAuthenticator() {
        val player = account()
        // Not staff: the same answer as a wrong password.
        post(ApiRoutes.ADMIN_LOGIN, AdminLoginRequest(player.email, PASSWORD))
            .error(403, ErrorCode.FORBIDDEN, ErrorReason.WRONG_CREDENTIALS)
        post(ApiRoutes.ADMIN_LOGIN, AdminLoginRequest(player.email, "wrong password"))
            .error(403, ErrorCode.FORBIDDEN, ErrorReason.WRONG_CREDENTIALS)

        // An unconfirmed email can't set up the authenticator.
        val unconfirmed = account(verified = false, role = UserRole.MODERATOR)
        post(ApiRoutes.ADMIN_LOGIN, AdminLoginRequest(unconfirmed.email, PASSWORD))
            .error(409, ErrorCode.WRONG_STATE, ErrorReason.EMAIL_NOT_VERIFIED)

        val moderator = account(role = UserRole.MODERATOR)
        val first = post(
            ApiRoutes.ADMIN_LOGIN,
            AdminLoginRequest(moderator.nickname, PASSWORD),
        ).ok<AdminLoginResponse>()
        assertEquals(AdminLoginStep.ENROLL, first.next)
        assertEquals("${moderator.email.first()}•••@example.com", first.emailHint)
        val emailed = emails.awaitCode(moderator.email, purpose = EmailPurpose.STAFF_ENROLL)
        post(ApiRoutes.ADMIN_ENROLL, AdminEnrollRequest(first.challenge, wrong(emailed)))
            .error(422, ErrorCode.INVALID_CODE)
        val enrollment = post(
            ApiRoutes.ADMIN_ENROLL,
            AdminEnrollRequest(first.challenge, emailed),
        ).ok<AdminEnrollment>()
        assertContains(enrollment.otpauthUri, "secret=${enrollment.secret}")
        assertTrue(enrollment.qr.isNotEmpty() && enrollment.qr.all { it.length == enrollment.qr.size })
        val totp = Totp(base32(enrollment.secret))
        post(ApiRoutes.ADMIN_ENROLL_CONFIRM, AdminTotpRequest(first.challenge, wrong(totp.codeAt(clock.millis()))))
            .error(422, ErrorCode.INVALID_CODE)
        val enrolled =
            post(ApiRoutes.ADMIN_ENROLL_CONFIRM, AdminTotpRequest(first.challenge, totp.codeAt(clock.millis())))
        val me = enrolled.ok<AdminMe>()
        assertEquals(UserRole.MODERATOR, me.role)
        val cookie = enrolled.setCookie!!
        assertContains(cookie, "${ApiRoutes.ADMIN_COOKIE}=")
        for (attribute in listOf("Path=/", "Secure", "HttpOnly", "SameSite=Strict")) assertContains(cookie, attribute)
        assertEquals(me.userId, get(ApiRoutes.ADMIN_ME, enrolled.session).ok<AdminMe>().userId)
        // The challenge is used up.
        post(ApiRoutes.ADMIN_ENROLL_CONFIRM, AdminTotpRequest(first.challenge, totp.codeAt(clock.millis())))
            .error(401, ErrorCode.UNAUTHORIZED, ErrorReason.SESSION_EXPIRED)

        // Next time: the password, then a code. The code of this time step was used already.
        val second = post(ApiRoutes.ADMIN_LOGIN, AdminLoginRequest(moderator.email, PASSWORD)).ok<AdminLoginResponse>()
        assertEquals(AdminLoginStep.TOTP, second.next)
        assertNull(second.emailHint)
        post(ApiRoutes.ADMIN_LOGIN_TOTP, AdminTotpRequest(second.challenge, totp.codeAt(clock.millis())))
            .error(422, ErrorCode.INVALID_CODE)
        clock.advance(Duration.ofSeconds(30))
        val again = post(ApiRoutes.ADMIN_LOGIN_TOTP, AdminTotpRequest(second.challenge, totp.codeAt(clock.millis())))
        assertEquals(me.userId, again.ok<AdminMe>().userId)

        // Logging out ends that session only.
        post(ApiRoutes.ADMIN_LOGOUT, json = null, session = again.session).expect(204)
        get(ApiRoutes.ADMIN_ME, again.session).error(401, ErrorCode.UNAUTHORIZED, ErrorReason.SESSION_EXPIRED)
        get(ApiRoutes.ADMIN_ME, enrolled.session).ok<AdminMe>()
    }

    @Test
    fun fiveWrongCodesEndTheAttempt() {
        val (moderator, totp) = enrolledStaff(UserRole.MODERATOR)
        clock.advance(Duration.ofSeconds(30))
        val login = post(ApiRoutes.ADMIN_LOGIN, AdminLoginRequest(moderator.email, PASSWORD)).ok<AdminLoginResponse>()
        repeat(5) {
            post(
                ApiRoutes.ADMIN_LOGIN_TOTP,
                AdminTotpRequest(login.challenge, "000000"),
            ).error(422, ErrorCode.INVALID_CODE)
        }
        post(ApiRoutes.ADMIN_LOGIN_TOTP, AdminTotpRequest(login.challenge, totp.codeAt(clock.millis())))
            .error(401, ErrorCode.UNAUTHORIZED, ErrorReason.SESSION_EXPIRED)
    }

    @Test
    fun withoutTheHeaderNothingGoesThrough() {
        val admin = staff(UserRole.ADMIN)
        // Not even the login: a form on another site can't set the header.
        post(ApiRoutes.ADMIN_LOGIN, AdminLoginRequest("anyone", PASSWORD), header = false)
            .error(403, ErrorCode.FORBIDDEN)
        get(ApiRoutes.ADMIN_ME, admin, header = false).error(403, ErrorCode.FORBIDDEN)
        // Without the cookie, the app's account token is no way in.
        val response = mvc.get(ApiRoutes.ADMIN_ME) {
            header(ApiRoutes.ADMIN_HEADER, "1")
            header(HttpHeaders.AUTHORIZATION, "${ApiRoutes.AUTH_SCHEME} ${admin.account.token}")
        }.andReturn().response
        Response(
            response.status,
            response.contentAsString,
            null,
        ).error(401, ErrorCode.UNAUTHORIZED, ErrorReason.SESSION_EXPIRED)
        // Every answer of the admin is private and never cached.
        val me = mvc.get(ApiRoutes.ADMIN_ME) {
            header(ApiRoutes.ADMIN_HEADER, "1")
            cookie(Cookie(ApiRoutes.ADMIN_COOKIE, admin.token))
        }.andReturn().response
        assertEquals("no-store", me.getHeader(HttpHeaders.CACHE_CONTROL))
        assertEquals("nosniff", me.getHeader("X-Content-Type-Options"))
    }

    @Test
    fun theSessionEndsWhenIdleOrTooOldOrTheRoleIsGone() {
        val admin = staff(UserRole.ADMIN)
        clock.advance(Duration.ofMinutes(29))
        get(ApiRoutes.ADMIN_ME, admin).ok<AdminMe>()
        clock.advance(Duration.ofMinutes(31))
        get(ApiRoutes.ADMIN_ME, admin).error(401, ErrorCode.UNAUTHORIZED, ErrorReason.SESSION_EXPIRED)

        val busy = staff(UserRole.ADMIN)
        repeat(16) {
            clock.advance(Duration.ofMinutes(29))
            get(ApiRoutes.ADMIN_ME, busy).ok<AdminMe>()
        }
        // 8 hours after the login, however busy.
        clock.advance(Duration.ofMinutes(29))
        get(ApiRoutes.ADMIN_ME, busy).error(401, ErrorCode.UNAUTHORIZED, ErrorReason.SESSION_EXPIRED)

        val moderator = staff(UserRole.MODERATOR)
        setRole(moderator.account.user.id, UserRole.PLAYER)
        get(ApiRoutes.ADMIN_ME, moderator).error(401, ErrorCode.UNAUTHORIZED, ErrorReason.SESSION_EXPIRED)
    }

    @Test
    fun aReportEndsInABan() {
        val game = gameWithChat()
        val moderator = staff(UserRole.MODERATOR)
        val admin = staff(UserRole.ADMIN)

        val report = get(ApiRoutes.ADMIN_REPORTS, moderator).ok<AdminReports>().reports.single {
            it.gameId ==
                game.gameId
        }
        assertEquals(game.message, report.text)
        assertEquals(game.author.user.id, report.authorId)
        assertEquals(game.reporter.user.nickname, report.reporterName)
        assertEquals(1, report.authorReports)
        assertNull(report.resolvedAtMillis)

        // Moderators: 30 days at most.
        resolve(
            moderator,
            report.id,
            ResolveReportRequest(ReportAction.BAN, "insults", days = null),
        ).error(403, ErrorCode.FORBIDDEN)
        resolve(
            moderator,
            report.id,
            ResolveReportRequest(ReportAction.BAN, "insults", days = 31),
        ).error(403, ErrorCode.FORBIDDEN)
        resolve(
            moderator,
            report.id,
            ResolveReportRequest(ReportAction.BAN, " ", days = 7),
        ).error(400, ErrorCode.BAD_REQUEST)
        val resolved = resolve(
            moderator,
            report.id,
            ResolveReportRequest(ReportAction.BAN, "insults", days = 7),
        ).ok<AdminReport>()
        assertNotNull(resolved.resolvedAtMillis)
        assertEquals(moderator.account.user.nickname, resolved.resolvedByName)
        assertContains(resolved.resolution.orEmpty(), "BAN 7d")
        resolve(
            moderator,
            report.id,
            ResolveReportRequest(ReportAction.DISMISS, "twice"),
        ).error(409, ErrorCode.WRONG_STATE)
        assertTrue(get(ApiRoutes.ADMIN_REPORTS, moderator).ok<AdminReports>().reports.none { it.id == report.id })

        // The author is logged out everywhere and can't log in until the ban ends; with the right password only.
        getAccount(ApiRoutes.ME, game.author.token).error(401, ErrorCode.UNAUTHORIZED, ErrorReason.SESSION_EXPIRED)
        post(ApiRoutes.LOGIN, LoginRequest(game.author.user.email, "wrong password"), header = false)
            .error(403, ErrorCode.FORBIDDEN, ErrorReason.WRONG_CREDENTIALS)
        val banned = post(ApiRoutes.LOGIN, LoginRequest(game.author.user.email, PASSWORD), header = false)
        banned.error(403, ErrorCode.FORBIDDEN, ErrorReason.ACCOUNT_BANNED)
        assertEquals(clock.millis() + Duration.ofDays(7).toMillis(), banned.apiError().untilMillis)
        // Nor chat in the game that goes on.
        chat(game.authorPlayer, "again").error(403, ErrorCode.FORBIDDEN, ErrorReason.CHAT_MUTED)

        val card = get(ApiRoutes.adminUser(game.author.user.id), moderator).ok<AdminUserCard>()
        assertEquals(0, card.devices)
        val ban = card.sanctions.single()
        assertEquals(SanctionKind.BAN, ban.kind)
        assertTrue(ban.isActiveAt(clock.millis()))

        val audit = get(ApiRoutes.ADMIN_AUDIT, admin).ok<AdminAudit>().entries
        val entry = audit.first { it.action == AdminAction.RESOLVE_REPORT && it.targetUserId == game.author.user.id }
        assertEquals(moderator.account.user.id, entry.actorId)
        assertEquals("insults", entry.reason)
        assertTrue(audit.any { it.action == AdminAction.BAN && it.targetUserId == game.author.user.id })

        // After the ban, all is back.
        clock.advance(Duration.ofDays(7))
        post(ApiRoutes.LOGIN, LoginRequest(game.author.user.email, PASSWORD), header = false).ok<AccountSession>()
    }

    @Test
    fun aChatBanStopsTheChatOnly() {
        val game = gameWithChat()
        val moderator = staff(UserRole.MODERATOR)
        val authorId = game.author.user.id

        post(ApiRoutes.adminUser(authorId, "mute"), SanctionRequest(3, "spam"), moderator).ok<AdminUserCard>()
        val muted = chat(game.authorPlayer, "still here")
        muted.error(403, ErrorCode.FORBIDDEN, ErrorReason.CHAT_MUTED)
        assertEquals(clock.millis() + Duration.ofDays(3).toMillis(), muted.apiError().untilMillis)
        getAccount(ApiRoutes.ME, game.author.token).expect(200)
        assertTrue(
            get(
                ApiRoutes.ADMIN_USERS + "?q=" + game.author.user.nickname,
                moderator,
            ).ok<AdminUsers>().users.single().muted,
        )

        post(ApiRoutes.adminUser(authorId, "unmute"), AdminReasonRequest("apologized"), moderator).ok<AdminUserCard>()
        chat(game.authorPlayer, "sorry").expect(200)
    }

    @Test
    fun whoMayDoWhat() {
        val moderator = staff(UserRole.MODERATOR)
        val admin = staff(UserRole.ADMIN)
        val player = account()

        // Admins only.
        post(
            ApiRoutes.ADMIN_USERS_BY_EMAIL,
            AdminFindByEmailRequest(player.email, "support"),
            moderator,
        ).error(403, ErrorCode.FORBIDDEN)
        post(
            ApiRoutes.adminUser(player.user.id, "email"),
            AdminReasonRequest("support"),
            moderator,
        ).error(403, ErrorCode.FORBIDDEN)
        post(
            ApiRoutes.adminUser(player.user.id, "logout"),
            AdminReasonRequest("stolen"),
            moderator,
        ).error(403, ErrorCode.FORBIDDEN)
        post(
            ApiRoutes.adminUser(player.user.id, "delete"),
            AdminReasonRequest("asked"),
            moderator,
        ).error(403, ErrorCode.FORBIDDEN)
        get(ApiRoutes.ADMIN_AUDIT, moderator).error(403, ErrorCode.FORBIDDEN)
        get(ApiRoutes.ADMIN_STAFF, moderator).error(403, ErrorCode.FORBIDDEN)
        post(ApiRoutes.adminUser(player.user.id, "role"), AdminSetRoleRequest(UserRole.MODERATOR, "help"), moderator)
            .error(403, ErrorCode.FORBIDDEN)

        // Staff are not sanctioned, nobody acts on themselves, admins are made on the server.
        post(
            ApiRoutes.adminUser(admin.account.user.id, "ban"),
            SanctionRequest(1, "x"),
            moderator,
        ).error(403, ErrorCode.FORBIDDEN)
        post(
            ApiRoutes.adminUser(moderator.account.user.id, "mute"),
            SanctionRequest(1, "x"),
            admin,
        ).error(403, ErrorCode.FORBIDDEN)
        post(ApiRoutes.adminUser(admin.account.user.id, "role"), AdminSetRoleRequest(UserRole.PLAYER, "x"), admin)
            .error(403, ErrorCode.FORBIDDEN)
        post(ApiRoutes.adminUser(player.user.id, "role"), AdminSetRoleRequest(UserRole.ADMIN, "x"), admin)
            .error(403, ErrorCode.FORBIDDEN)

        // The card never shows the email; showing it is logged.
        val card = get(ApiRoutes.adminUser(player.user.id), moderator).ok<AdminUserCard>()
        assertFalse(card.emailMasked.contains(player.user.nickname))
        assertEquals(
            player.email,
            post(
                ApiRoutes.adminUser(player.user.id, "email"),
                AdminReasonRequest("support"),
                admin,
            ).ok<AdminRevealedEmail>().email,
        )
        val found = post(
            ApiRoutes.ADMIN_USERS_BY_EMAIL,
            AdminFindByEmailRequest(player.email.uppercase(), "support"),
            admin,
        ).ok<AdminUsers>()
        assertEquals(listOf(player.user.id), found.users.map { it.id })
        val audit = get(ApiRoutes.ADMIN_AUDIT, admin).ok<AdminAudit>().entries
        val lookup = audit.first { it.action == AdminAction.FIND_BY_EMAIL && it.actorId == admin.account.user.id }
        assertFalse(lookup.target.orEmpty().contains(player.user.nickname), "the log keeps the address masked")
        assertTrue(audit.any { it.action == AdminAction.SHOW_EMAIL && it.targetUserId == player.user.id })

        // An admin makes a moderator, who then logs in and sets up an authenticator; and takes the role back.
        post(
            ApiRoutes.adminUser(player.user.id, "role"),
            AdminSetRoleRequest(UserRole.MODERATOR, "help"),
            admin,
        ).ok<AdminUserCard>()
        assertTrue(
            get(ApiRoutes.ADMIN_STAFF, admin).ok<AdminStaff>().staff.any {
                it.id == player.user.id &&
                    !it.totpEnrolled
            },
        )
        post(
            ApiRoutes.adminUser(player.user.id, "role"),
            AdminSetRoleRequest(UserRole.PLAYER, "done"),
            admin,
        ).ok<AdminUserCard>()
        assertTrue(get(ApiRoutes.ADMIN_STAFF, admin).ok<AdminStaff>().staff.none { it.id == player.user.id })

        // A nickname reset gives a random one.
        val renamed = post(
            ApiRoutes.adminUser(player.user.id, "rename"),
            AdminReasonRequest("offensive"),
            moderator,
        ).ok<AdminUserCard>()
        assertTrue(renamed.nickname.startsWith("player-"), renamed.nickname)

        // Deleting an account on request.
        post(ApiRoutes.adminUser(player.user.id, "delete"), AdminReasonRequest("asked by email"), admin).expect(204)
        get(ApiRoutes.adminUser(player.user.id), admin).error(404, ErrorCode.NOT_FOUND, ErrorReason.USER_NOT_FOUND)
        assertTrue(
            get(ApiRoutes.ADMIN_AUDIT, admin).ok<AdminAudit>().entries.any {
                it.action == AdminAction.DELETE_ACCOUNT && it.targetUserId == player.user.id
            },
            "the log outlives the account",
        )
    }

    @Test
    fun gamesAndNumbers() {
        val game = gameWithChat()
        val moderator = staff(UserRole.MODERATOR)
        val admin = staff(UserRole.ADMIN)

        val listed = get(ApiRoutes.ADMIN_GAMES, moderator).ok<AdminGames>().games.single { it.gameId == game.gameId }
        assertEquals(GamePhase.LOBBY, listed.phase)
        assertEquals(2, listed.players)
        assertEquals(0, listed.guests)
        assertEquals(1, listed.chatMessages)
        // Numbers, never the zone's center: that's where the host is.
        val raw = get(ApiRoutes.ADMIN_GAMES, moderator).body
        assertFalse(raw.contains(PARK.lat.toString()), raw)

        post(ApiRoutes.adminGameEnd(game.gameId), AdminReasonRequest("x"), moderator).error(403, ErrorCode.FORBIDDEN)
        post(ApiRoutes.adminGameEnd(game.gameId), AdminReasonRequest("abuse"), admin).expect(204)
        assertTrue(get(ApiRoutes.ADMIN_GAMES, admin).ok<AdminGames>().games.none { it.gameId == game.gameId })

        val stats = get(ApiRoutes.ADMIN_STATS, moderator).ok<AdminStats>()
        assertTrue(stats.users >= 2 && stats.reportsOpen >= 0 && stats.heapMaxMb > 0, "$stats")
    }

    // An account, a staff member, a game with a reported message

    private data class Account(val account: AccountSession, val email: String) {
        val user get() = account.user
        val token get() = account.token
        val nickname get() = account.user.nickname
    }

    private fun account(verified: Boolean = true, role: UserRole = UserRole.PLAYER): Account {
        val nickname = uniqueName()
        val email = "$nickname@example.com"
        val session = post(
            ApiRoutes.ACCOUNTS,
            RegisterRequest(nickname, email, PASSWORD),
            header = false,
        ).ok<AccountSession>()
        if (verified) {
            val code = emails.awaitCode(email, purpose = EmailPurpose.VERIFY_EMAIL)
            postAccount(ApiRoutes.ME_EMAIL_VERIFY, VerifyEmailRequest(code), session.token).expect(200)
        }
        if (role != UserRole.PLAYER) setRole(session.user.id, role)
        return Account(session, email)
    }

    private class StaffLogin(val account: AccountSession, val token: String)

    private fun enrolledStaff(role: UserRole): Pair<Account, Totp> {
        val member = account(role = role)
        val login = post(ApiRoutes.ADMIN_LOGIN, AdminLoginRequest(member.email, PASSWORD)).ok<AdminLoginResponse>()
        val code = emails.awaitCode(member.email, purpose = EmailPurpose.STAFF_ENROLL)
        val enrollment = post(ApiRoutes.ADMIN_ENROLL, AdminEnrollRequest(login.challenge, code)).ok<AdminEnrollment>()
        val totp = Totp(base32(enrollment.secret))
        post(ApiRoutes.ADMIN_ENROLL_CONFIRM, AdminTotpRequest(login.challenge, totp.codeAt(clock.millis()))).expect(200)
        return member to totp
    }

    /** A logged-in staff member. */
    private fun staff(role: UserRole): StaffLogin {
        val member = account(role = role)
        val login = post(ApiRoutes.ADMIN_LOGIN, AdminLoginRequest(member.email, PASSWORD)).ok<AdminLoginResponse>()
        val code = emails.awaitCode(member.email, purpose = EmailPurpose.STAFF_ENROLL)
        val enrollment = post(ApiRoutes.ADMIN_ENROLL, AdminEnrollRequest(login.challenge, code)).ok<AdminEnrollment>()
        val totp = Totp(base32(enrollment.secret))
        val session =
            post(ApiRoutes.ADMIN_ENROLL_CONFIRM, AdminTotpRequest(login.challenge, totp.codeAt(clock.millis())))
        return StaffLogin(member.account, checkNotNull(session.session))
    }

    private fun setRole(userId: UserId, role: UserRole) {
        jdbc.sql(
            "UPDATE users SET role = :role WHERE id = :id",
        ).param("role", role.name).param("id", userId.value).update()
    }

    private class ChatGame(
        val gameId: GameId,
        val author: Account,
        val authorPlayer: PlayerSession,
        val reporter: Account,
        val message: String,
    )

    /** Two players with accounts; the author's message is reported by the other one. */
    private fun gameWithChat(): ChatGame {
        val author = account()
        val reporter = account()
        val created = postAccount(
            ApiRoutes.GAMES,
            CreateGameRequest("", GameSettings(zone = shrinkingZone(PARK))),
            author.token,
        )
            .ok<SessionResponse>()
        val joined = postAccount(ApiRoutes.JOIN, JoinGameRequest(created.snapshot.joinCode, ""), reporter.token)
            .ok<SessionResponse>()
        val message = "you are a ${uniqueName("word")}"
        val sent = chat(created.session, message).ok<GameSnapshot>()
        val seq = sent.chat.single { it.text == message }.seq
        playerPost(ApiRoutes.chatReport(created.session.gameId, seq), null, joined.session).expect(200)
        return ChatGame(created.session.gameId, author, created.session, reporter, message)
    }

    private fun chat(player: PlayerSession, text: String) =
        playerPost(ApiRoutes.chat(player.gameId), SendChatRequest(text, chatAfter = 0).json(), player)

    private fun resolve(staff: StaffLogin, reportId: Long, request: ResolveReportRequest) =
        post(ApiRoutes.adminReportResolve(reportId), request, staff)

    // HTTP

    private class Response(val status: Int, val body: String, val setCookie: String?) {
        fun expect(status: Int) = also { assertEquals(status, this.status, body) }

        inline fun <reified T> ok(): T = protocolJson.decodeFromString(expect(200).body)

        fun apiError(): ApiError = protocolJson.decodeFromString(body)

        fun error(status: Int, code: ErrorCode, reason: ErrorReason? = null) {
            val error = protocolJson.decodeFromString<ApiError>(expect(status).body)
            assertEquals(code, error.code, body)
            if (reason != null) assertEquals(reason, error.reason, body)
        }

        /** The admin session token of the cookie this answer set. */
        val session: String? get() = setCookie?.substringAfter("${ApiRoutes.ADMIN_COOKIE}=")?.substringBefore(';')
    }

    private inline fun <reified T> T.json(): String = protocolJson.encodeToString(this)

    private inline fun <reified T> post(path: String, body: T, staff: StaffLogin? = null, header: Boolean = true) =
        postRaw(path, body?.json(), staff?.token, header)

    private fun post(path: String, json: String?, session: String?) = postRaw(path, json, session, header = true)

    private fun postRaw(path: String, json: String?, session: String?, header: Boolean): Response {
        val response = mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            if (json != null) content = json
            if (header) header(ApiRoutes.ADMIN_HEADER, "1")
            if (session != null) cookie(Cookie(ApiRoutes.ADMIN_COOKIE, session))
        }.andReturn().response
        return Response(
            response.status,
            response.getContentAsString(Charsets.UTF_8),
            response.getHeader(HttpHeaders.SET_COOKIE),
        )
    }

    private fun get(path: String, staff: StaffLogin, header: Boolean = true) = get(path, staff.token, header)

    private fun get(path: String, session: String?, header: Boolean = true): Response {
        val response = mvc.get(path) {
            if (header) header(ApiRoutes.ADMIN_HEADER, "1")
            if (session != null) cookie(Cookie(ApiRoutes.ADMIN_COOKIE, session))
        }.andReturn().response
        return Response(response.status, response.getContentAsString(Charsets.UTF_8), null)
    }

    private inline fun <reified T> postAccount(path: String, body: T, token: String): Response {
        val response = mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = body.json()
            header(HttpHeaders.AUTHORIZATION, "${ApiRoutes.AUTH_SCHEME} $token")
        }.andReturn().response
        return Response(response.status, response.getContentAsString(Charsets.UTF_8), null)
    }

    private fun getAccount(path: String, token: String): Response {
        val response = mvc.get(path) {
            header(HttpHeaders.AUTHORIZATION, "${ApiRoutes.AUTH_SCHEME} $token")
        }.andReturn().response
        return Response(response.status, response.getContentAsString(Charsets.UTF_8), null)
    }

    private fun playerPost(path: String, json: String?, player: PlayerSession): Response {
        val response = mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            if (json != null) content = json
            header(HttpHeaders.AUTHORIZATION, "${ApiRoutes.AUTH_SCHEME} ${player.token}")
        }.andReturn().response
        return Response(response.status, response.getContentAsString(Charsets.UTF_8), null)
    }

    private companion object {
        const val PASSWORD = "correct horse battery"
        val PARK = GeoPoint(50.4501, 30.5234)

        /** Another 6-digit code. */
        fun wrong(code: String) = code.map { '0' + (it - '0' + 1) % 10 }.joinToString("")

        /** RFC 4648 base32, as an authenticator app reads the secret. */
        fun base32(text: String): ByteArray {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            var buffer = 0
            var bits = 0
            val out = ByteArrayOutputStream()
            for (char in text) {
                buffer = (buffer shl 5) or alphabet.indexOf(char)
                bits += 5
                if (bits >= 8) {
                    out.write((buffer shr (bits - 8)) and 0xff)
                    bits -= 8
                }
            }
            return out.toByteArray()
        }
    }
}
