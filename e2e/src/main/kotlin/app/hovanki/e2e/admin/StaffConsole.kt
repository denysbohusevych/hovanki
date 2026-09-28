package app.hovanki.e2e.admin

import app.hovanki.client.network.createHttpClient
import app.hovanki.e2e.bot.BotAccount
import app.hovanki.e2e.observer.EmailPurpose
import app.hovanki.e2e.observer.Observer
import app.hovanki.shared.protocol.AdminEnrollRequest
import app.hovanki.shared.protocol.AdminEnrollment
import app.hovanki.shared.protocol.AdminLoginRequest
import app.hovanki.shared.protocol.AdminLoginResponse
import app.hovanki.shared.protocol.AdminLoginStep
import app.hovanki.shared.protocol.AdminMe
import app.hovanki.shared.protocol.AdminReasonRequest
import app.hovanki.shared.protocol.AdminReport
import app.hovanki.shared.protocol.AdminReports
import app.hovanki.shared.protocol.AdminTotpRequest
import app.hovanki.shared.protocol.AdminUserCard
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ReportAction
import app.hovanki.shared.protocol.ResolveReportRequest
import app.hovanki.shared.protocol.SanctionRequest
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.totp.Totp
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream

/** The admin refused (docs/adr/0008-admin.md): [error] is its answer. */
class AdminRejected(val status: Int, val error: ApiError?) : Exception("HTTP $status: ${error?.message}")

/**
 * A staff member at the /admin page (docs/adr/0008-admin.md), in Kotlin: the same requests the page sends, the header
 * against cross-site requests and the session cookie included. The authenticator app is a [Totp] on the secret the
 * server showed at the first login; the enrollment code comes from the [observer]'s mailbox.
 */
class StaffConsole(serverUrl: String, private val observer: Observer) : AutoCloseable {
    private val baseUrl = serverUrl.trimEnd('/')
    private val client = createHttpClient(OkHttp.create(), logRequests = false)
    private var authenticator: Totp? = null
    private var lastStep = -1L

    /**
     * The session cookie. Set by hand: it is `Secure`, and cookie jars keep those away from the plain HTTP of the
     * test server.
     */
    private var session: String? = null

    /** Logs in with [account]'s password and the authenticator; the first time, sets the authenticator up. */
    suspend fun logIn(account: BotAccount): AdminMe {
        val known = observer.emails(account.email)
        val login = call<AdminLoginResponse>(ApiRoutes.ADMIN_LOGIN, AdminLoginRequest(account.email, account.password))
        if (login.next == AdminLoginStep.ENROLL) {
            val code = checkNotNull(observer.awaitEmail(account.email, EmailPurpose.STAFF_ENROLL, known).code)
            val enrollment = call<AdminEnrollment>(ApiRoutes.ADMIN_ENROLL, AdminEnrollRequest(login.challenge, code))
            authenticator = Totp(base32(enrollment.secret))
            return call(ApiRoutes.ADMIN_ENROLL_CONFIRM, AdminTotpRequest(login.challenge, nextCode()))
        }
        return call(ApiRoutes.ADMIN_LOGIN_TOTP, AdminTotpRequest(login.challenge, nextCode()))
    }

    suspend fun me(): AdminMe = get(ApiRoutes.ADMIN_ME)

    suspend fun openReports(): List<AdminReport> = get<AdminReports>(ApiRoutes.ADMIN_REPORTS).reports

    suspend fun resolve(reportId: Long, action: ReportAction, reason: String, days: Int? = null): AdminReport =
        call(ApiRoutes.adminReportResolve(reportId), ResolveReportRequest(action, reason, days))

    suspend fun card(userId: UserId): AdminUserCard = get(ApiRoutes.adminUser(userId))

    suspend fun ban(userId: UserId, days: Int?, reason: String): AdminUserCard =
        call(ApiRoutes.adminUser(userId, "ban"), SanctionRequest(days, reason))

    suspend fun unmute(userId: UserId, reason: String): AdminUserCard =
        call(ApiRoutes.adminUser(userId, "unmute"), AdminReasonRequest(reason))

    /**
     * The code of the authenticator app. Each time step logs in once: a second login within the same 30 seconds waits
     * for the next code, like a person does.
     */
    private suspend fun nextCode(): String {
        val totp = checkNotNull(authenticator) { "No authenticator set up" }
        while (totp.stepAt(System.currentTimeMillis()) <= lastStep) delay(STEP_POLL_MILLIS)
        val now = System.currentTimeMillis()
        lastStep = totp.stepAt(now)
        return totp.codeAt(now)
    }

    private suspend inline fun <reified T> get(path: String): T = read(client.get(baseUrl + path) { admin() })

    private suspend inline fun <reified T> call(path: String, body: Any): T = read(
        client.post(baseUrl + path) {
            admin()
            contentType(ContentType.Application.Json)
            setBody(body)
        },
    )

    private fun HttpRequestBuilder.admin() {
        header(ApiRoutes.ADMIN_HEADER, "1")
        session?.let { header(HttpHeaders.Cookie, "${ApiRoutes.ADMIN_COOKIE}=$it") }
    }

    private suspend inline fun <reified T> read(response: HttpResponse): T {
        response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
            .firstOrNull { it.startsWith("${ApiRoutes.ADMIN_COOKIE}=") }
            ?.let { session = it.substringAfter('=').substringBefore(';').ifEmpty { null } }
        if (!response.status.isSuccess()) {
            throw AdminRejected(response.status.value, runCatching { response.body<ApiError>() }.getOrNull())
        }
        return response.body()
    }

    override fun close() = client.close()

    private companion object {
        const val STEP_POLL_MILLIS = 500L

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
