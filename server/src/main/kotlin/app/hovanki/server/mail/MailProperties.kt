package app.hovanki.server.mail

import org.springframework.boot.context.properties.ConfigurationProperties

/** `hovanki.mail.*`; the SMTP server itself is `spring.mail.*` (`SPRING_MAIL_HOST`, ...). */
@ConfigurationProperties("hovanki.mail")
data class MailProperties(
    val sender: Sender = Sender.LOG,
    /** The From address with `smtp`, e.g. `Hovanki <noreply@hovanki.app>` (`HOVANKI_MAIL_FROM`). */
    val from: String? = null,
    /** How many emails the `recording` sender keeps. */
    val recordingLimit: Int = 1000,
) {
    enum class Sender {
        /** Local development: the code goes to the log ([LogEmailSender]). */
        LOG,

        /** Production ([SmtpEmailSender]); the server does not start without `spring.mail.host` and [from]. */
        SMTP,

        /** Tests and the `e2e` profile: kept in memory ([RecordingEmailSender]), read by the debug endpoint. */
        RECORDING,
    }
}
