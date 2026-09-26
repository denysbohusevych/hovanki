package app.hovanki.server.mail

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionSynchronizationUtils
import java.time.Clock
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MailerTest {
    private val recorder = RecordingEmailSender(Clock.systemUTC())
    private val mailer = Mailer(recorder)

    @AfterTest
    fun stop() = mailer.destroy()

    @Test
    fun sendsOnlyAfterTheCommit() {
        // A transaction that ends without a commit (rolled back): nothing goes out.
        inTransaction { mailer.sendAfterCommit(email("rolled-back@example.com")) }
        inTransaction {
            mailer.sendAfterCommit(email("bob@example.com"))
            assertTrue(recorder.sentTo("bob@example.com").isEmpty())
            TransactionSynchronizationUtils.triggerAfterCommit()
        }
        // Outside a transaction: right away.
        mailer.sendAfterCommit(email("alice@example.com"))

        mailer.destroy() // waits for the queue
        assertTrue(recorder.sentTo("rolled-back@example.com").isEmpty())
        assertEquals(1, recorder.sentTo("BOB@example.com").size)
        assertEquals(1, recorder.sentTo("alice@example.com").size)
    }

    @Test
    fun aFailedEmailIsDroppedQuietly() {
        val failing = Mailer { throw MailSendException("550 <bob@example.com>: recipient rejected") }
        failing.sendAfterCommit(email("bob@example.com"))
        failing.destroy()
    }

    @Test
    fun smtpNeedsAServerAndAFromAddress() {
        val clock = Clock.systemUTC()
        val smtp = MailProperties(sender = MailProperties.Sender.SMTP, from = "Hovanki <noreply@example.com>")
        val noServer = StaticListableBeanFactory().getBeanProvider(JavaMailSender::class.java)
        val server = StaticListableBeanFactory(mapOf("mailSender" to JavaMailSenderImpl()))
            .getBeanProvider(JavaMailSender::class.java)

        val withoutServer = assertFailsWith<IllegalStateException> { MailConfig().emailSender(smtp, noServer, clock) }
        assertContains(withoutServer.message.orEmpty(), "SPRING_MAIL_HOST")
        val withoutFrom = assertFailsWith<IllegalStateException> {
            MailConfig().emailSender(smtp.copy(from = " "), server, clock)
        }
        assertContains(withoutFrom.message.orEmpty(), "HOVANKI_MAIL_FROM")
        assertIs<SmtpEmailSender>(MailConfig().emailSender(smtp, server, clock))
    }

    private fun inTransaction(block: () -> Unit) {
        TransactionSynchronizationManager.initSynchronization()
        try {
            block()
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    private fun email(to: String) =
        EmailTemplates.render(to, EmailPurpose.VERIFY_EMAIL, "en", "123456", Duration.ofMinutes(15))
}

/** `hovanki.mail.sender=smtp` with the SMTP settings of application.yaml, as in production. */
@SpringBootTest(
    properties = [
        "hovanki.mail.sender=smtp",
        "hovanki.mail.from=Hovanki <noreply@example.com>",
        "spring.mail.host=smtp.example.com",
    ],
)
class SmtpConfigTest(@Autowired private val context: ApplicationContext) {
    @Test
    fun smtpWithStartTlsAndTimeouts() {
        assertIs<SmtpEmailSender>(context.getBean(EmailSender::class.java))
        val properties = context.getBean(JavaMailSenderImpl::class.java).javaMailProperties
        assertEquals("true", properties["mail.smtp.auth"])
        assertEquals("true", properties["mail.smtp.starttls.required"])
        assertEquals("10000", properties["mail.smtp.timeout"])
        // No health check that connects to the SMTP server.
        assertFalse(context.containsBean("mailHealthContributor"))
    }
}
