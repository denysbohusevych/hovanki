package app.hovanki.server.mail

import org.slf4j.LoggerFactory

/** Local development without SMTP: the email goes to the log, code included. Never in production. */
class LogEmailSender : EmailSender {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        log.warn(
            "DEV ONLY: hovanki.mail.sender=log writes emails with their codes to this log instead of sending them. " +
                "Production needs hovanki.mail.sender=smtp.",
        )
    }

    override fun send(email: OutgoingEmail) {
        log.warn("DEV ONLY, not sent: {} email to {}, code {}", email.purpose, email.to, email.code)
    }
}
