package app.hovanki.e2e.observer

import app.hovanki.client.network.createHttpClient
import app.hovanki.e2e.scenario.E2eTransport
import app.hovanki.shared.debug.DebugEmail
import app.hovanki.shared.debug.DebugEmails
import app.hovanki.shared.debug.DebugGameList
import app.hovanki.shared.debug.DebugGameState
import app.hovanki.shared.debug.DebugReport
import app.hovanki.shared.debug.DebugReportList
import app.hovanki.shared.debug.DebugRoutes
import app.hovanki.shared.debug.DebugSetFeatures
import app.hovanki.shared.debug.DebugSetRole
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserRole
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the server's truth from its debug endpoints (Spring profile `e2e`): the full game state that scenarios check
 * the players' views against, the emails the server sent (a person reads the code there) and the reported chat
 * messages.
 */
class Observer(serverUrl: String) : AutoCloseable {
    private val baseUrl = serverUrl.trimEnd('/')
    private val client = createHttpClient(OkHttp.create(), logRequests = false)

    suspend fun games(): DebugGameList = get(DebugRoutes.GAMES)

    suspend fun game(id: GameId): DebugGameState = get(DebugRoutes.game(id))

    /** Emails sent to [email] so far, oldest first. */
    suspend fun emails(email: String): List<DebugEmail> =
        get<DebugEmails>(DebugRoutes.emails(email.encodeURLPathPart())).emails

    /** The newest email sent to [email], if any; sending is asynchronous, see [awaitEmail]. */
    suspend fun lastEmail(email: String): DebugEmail? = emails(email).lastOrNull()

    /**
     * Waits for a new email with a code to [email] for [purpose]: one that is not among [known] (what the inbox held
     * before the action that sends it). The server sends after its transaction commits, on its own threads, so the
     * email arrives a moment after the response.
     */
    suspend fun awaitEmail(
        email: String,
        purpose: EmailPurpose,
        known: List<DebugEmail> = emptyList(),
        within: Duration = 10.seconds,
    ): DebugEmail {
        val deadline = System.currentTimeMillis() + within.inWholeMilliseconds
        while (true) {
            val new = emails(email).firstOrNull { it !in known && it.purpose == purpose.name && it.code != null }
            if (new != null) return new
            if (System.currentTimeMillis() > deadline) throw AssertionError("No $purpose email to $email in $within")
            delay(POLL)
        }
    }

    /** Reported chat messages, newest first. */
    suspend fun reports(): List<DebugReport> = get<DebugReportList>(DebugRoutes.REPORTS).reports

    /**
     * Turns exactly [names] on for the whole server (every other feature off), as the admin would one by one. A run on
     * the live channel (`-Pe2e.transport=socket`, [E2eTransport]) keeps [ServerFeature.LIVE_SOCKET] on whatever the
     * names, unless [keepLiveSocket] is false: a scenario that switches the channel itself.
     */
    suspend fun setFeatures(names: List<String>, keepLiveSocket: Boolean = E2eTransport.isSocket) {
        val wanted = if (keepLiveSocket) names + ServerFeature.LIVE_SOCKET.name else names
        switchFeatures(DebugSetFeatures(wanted.distinct()))
    }

    /** Turns [names] on, every other feature as it is: the scenarios share the server. */
    suspend fun enableFeatures(names: List<String>) = switchFeatures(DebugSetFeatures(names, keepOthers = true))

    private suspend fun switchFeatures(request: DebugSetFeatures) {
        val response = client.post(baseUrl + DebugRoutes.FEATURES) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        check(response.status.isSuccess()) { "Observer: setting the features: HTTP ${response.status}" }
    }

    /** Makes [userId] staff (docs/adr/0008-admin.md), as the operator does with SQL on a real server. */
    suspend fun setRole(userId: UserId, role: UserRole) {
        val response = client.post(baseUrl + DebugRoutes.userRole(userId)) {
            contentType(ContentType.Application.Json)
            setBody(DebugSetRole(role))
        }
        check(response.status.isSuccess()) { "Observer: setting the role of $userId: HTTP ${response.status}" }
    }

    private suspend inline fun <reified T> get(path: String): T {
        val response = client.get(baseUrl + path)
        if (response.status == HttpStatusCode.NotFound && path == DebugRoutes.GAMES) {
            error("$baseUrl has no observer endpoint: start the server with the Spring profile 'e2e'")
        }
        check(response.status.isSuccess()) { "Observer GET $path: HTTP ${response.status}" }
        return response.body()
    }

    override fun close() = client.close()

    private companion object {
        val POLL = 200.milliseconds
    }
}

/** What an email the server sends is for ([DebugEmail.purpose]). */
enum class EmailPurpose { VERIFY_EMAIL, RESET_PASSWORD, STAFF_ENROLL }
