package app.hovanki.server.mail

import app.hovanki.shared.rules.AccountRules
import java.time.Clock

/**
 * Tests and the `e2e` profile: keeps the last [limit] emails in memory instead of sending them. The debug endpoint
 * (`DebugRoutes.EMAILS`) and the server's tests read the codes from here.
 */
class RecordingEmailSender(private val clock: Clock, private val limit: Int = 1000) : EmailSender {
    data class Sent(val email: OutgoingEmail, val sentAtMillis: Long)

    private val sent = ArrayDeque<Sent>()

    @Synchronized
    override fun send(email: OutgoingEmail) {
        sent.addLast(Sent(email, clock.millis()))
        while (sent.size > limit) sent.removeFirst()
    }

    /** Emails sent to [address] (case-insensitive), oldest first. */
    @Synchronized
    fun sentTo(address: String): List<Sent> {
        val key = AccountRules.emailKey(address)
        return sent.filter { AccountRules.emailKey(it.email.to) == key }
    }
}
