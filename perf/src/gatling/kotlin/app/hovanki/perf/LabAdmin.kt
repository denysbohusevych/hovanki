package app.hovanki.perf

import app.hovanki.shared.debug.DebugEmails
import app.hovanki.shared.debug.DebugRoutes
import app.hovanki.shared.debug.DebugSetRole
import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.AdminBigGame
import app.hovanki.shared.protocol.AdminBigGameRequest
import app.hovanki.shared.protocol.AdminBigGames
import app.hovanki.shared.protocol.AdminEnrollRequest
import app.hovanki.shared.protocol.AdminEnrollment
import app.hovanki.shared.protocol.AdminFeatureRequest
import app.hovanki.shared.protocol.AdminFeatures
import app.hovanki.shared.protocol.AdminLoginRequest
import app.hovanki.shared.protocol.AdminLoginResponse
import app.hovanki.shared.protocol.AdminLoginStep
import app.hovanki.shared.protocol.AdminReasonRequest
import app.hovanki.shared.protocol.AdminTotpRequest
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.BigGameId
import app.hovanki.shared.protocol.BigGameStatus
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.UserRole
import app.hovanki.shared.protocol.VerifyEmailRequest
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.totp.Totp
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * What a simulation prepares before its players come (Gatling's `before`), outside the measurement: an admin of the
 * lab's server and what only an admin makes, a big game. Plain blocking calls, as a person at the /admin page makes
 * them; the codes a person reads in their email come from the observer of the `e2e` profile.
 */
class LabAdmin private constructor(private val email: String) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    /** The admin's session cookie: `Secure`, so kept by hand over the lab's plain HTTP. */
    private var session: String? = null

    fun createBigGame(request: AdminBigGameRequest): AdminBigGame =
        decode(send(ApiRoutes.ADMIN_BIG_GAMES, json(request), admin = true))

    /** Waits until the scheduler opens the lobby of [id] (at its next tick, `hovanki.big-games.tick`). */
    fun awaitLobby(id: BigGameId, within: Duration = Duration.ofSeconds(30)) {
        val deadline = System.nanoTime() + within.toNanos()
        while (System.nanoTime() < deadline) {
            val games = decode<AdminBigGames>(send(ApiRoutes.ADMIN_BIG_GAMES, body = null, admin = true)).games
            if (games.single { it.id == id }.status != BigGameStatus.SCHEDULED) return
            Thread.sleep(POLL_MILLIS)
        }
        error("The lobby of big game ${id.value} did not open in $within")
    }

    fun cancelBigGame(id: BigGameId, reason: String) {
        send(ApiRoutes.adminBigGameCancel(id), json(AdminReasonRequest(reason)), admin = true)
    }

    /** Turns [feature] on or off for the whole server, as an admin does on the admin page; returns whether it was on. */
    fun setFeature(feature: ServerFeature, enabled: Boolean, reason: String): Boolean {
        val features = decode<AdminFeatures>(send(ApiRoutes.ADMIN_FEATURES, body = null, admin = true)).features
        val wasOn = features.single { it.feature == feature }.enabled
        if (wasOn != enabled) {
            send(ApiRoutes.adminFeature(feature), json(AdminFeatureRequest(enabled, reason)), admin = true)
        }
        return wasOn
    }

    private fun logIn(password: String) {
        val login = decode<AdminLoginResponse>(
            send(ApiRoutes.ADMIN_LOGIN, json(AdminLoginRequest(email, password)), admin = true),
        )
        check(login.next == AdminLoginStep.ENROLL) { "A new admin enrolls an authenticator first" }
        val code = awaitCode("STAFF_ENROLL")
        val enrollment = decode<AdminEnrollment>(
            send(ApiRoutes.ADMIN_ENROLL, json(AdminEnrollRequest(login.challenge, code)), admin = true),
        )
        val totp = Totp(base32(enrollment.secret)).codeAt(System.currentTimeMillis())
        send(ApiRoutes.ADMIN_ENROLL_CONFIRM, json(AdminTotpRequest(login.challenge, totp)), admin = true)
        checkNotNull(session) { "The admin gave no session" }
    }

    /** The newest code emailed to the admin for [purpose], from the observer's mailbox. */
    private fun awaitCode(purpose: String): String {
        val path = DebugRoutes.emails(URLEncoder.encode(email, Charsets.UTF_8))
        repeat(EMAIL_POLLS) {
            val mail = decode<DebugEmails>(send(path, body = null)).emails.lastOrNull { it.purpose == purpose }
            mail?.code?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        error("No $purpose email to $email: is the server's profile e2e,perf?")
    }

    private fun send(path: String, body: String?, token: String? = null, admin: Boolean = false): String {
        val request = HttpRequest.newBuilder(URI.create(LabConfig.adminUrl + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .apply {
                if (body == null) GET() else POST(HttpRequest.BodyPublishers.ofString(body))
                token?.let { header("Authorization", "${ApiRoutes.AUTH_SCHEME} $it") }
                if (admin) {
                    header(ApiRoutes.ADMIN_HEADER, "1")
                    session?.let { header("Cookie", "${ApiRoutes.ADMIN_COOKIE}=$it") }
                }
            }
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        response.headers().allValues("set-cookie")
            .firstOrNull { it.startsWith("${ApiRoutes.ADMIN_COOKIE}=") }
            ?.let { session = it.substringAfter('=').substringBefore(';').ifEmpty { null } }
        check(response.statusCode() in 200..299) {
            "${if (body == null) "GET" else "POST"} $path: HTTP ${response.statusCode()} ${response.body().take(300)}"
        }
        return response.body()
    }

    companion object {
        private const val POLL_MILLIS = 500L
        private const val EMAIL_POLLS = 20

        /**
         * A new admin: signs up, confirms the email with the emailed code, is made an admin by the observer (as the
         * operator does with SQL on a real server) and logs into the admin, setting up the authenticator.
         */
        fun create(run: String): LabAdmin {
            val nickname = "admin_$run"
            val password = "perf-$run-password"
            val admin = LabAdmin("$nickname@hovanki.test")
            val account = decode<AccountSession>(
                admin.send(ApiRoutes.ACCOUNTS, json(RegisterRequest(nickname, admin.email, password))),
            )
            val code = admin.awaitCode("VERIFY_EMAIL")
            admin.send(ApiRoutes.ME_EMAIL_VERIFY, json(VerifyEmailRequest(code)), token = account.token)
            admin.send(DebugRoutes.userRole(account.user.id), json(DebugSetRole(UserRole.ADMIN)))
            admin.logIn(password)
            return admin
        }

        private inline fun <reified T> decode(text: String): T = protocolJson.decodeFromString(text)

        /** RFC 4648 base32, as an authenticator app reads the secret. */
        private fun base32(text: String): ByteArray {
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
