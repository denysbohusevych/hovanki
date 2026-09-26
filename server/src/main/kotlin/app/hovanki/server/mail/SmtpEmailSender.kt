package app.hovanki.server.mail

import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender

/** Production: SMTP through Spring's [JavaMailSender] (`spring.mail.*`, `SPRING_MAIL_*` in the environment). */
class SmtpEmailSender(private val mailSender: JavaMailSender, private val from: String) : EmailSender {
    override fun send(email: OutgoingEmail) {
        val message = SimpleMailMessage()
        message.from = from
        message.setTo(email.to)
        message.subject = email.subject
        message.text = email.text
        mailSender.send(message)
    }
}
