package app.hovanki.server.mail

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Sends emails after the surrounding transaction commits (a rolled-back registration sends nothing), on a small pool
 * of its own, so a request never waits for the SMTP server. A failed email is logged (never with the address or the
 * code) and dropped: the user asks for a new code.
 */
@Component
class Mailer(private val sender: EmailSender) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val pool = ThreadPoolExecutor(
        POOL_SIZE,
        POOL_SIZE,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUE_SIZE),
    ) { task -> thread(start = false, isDaemon = true, name = "mail") { task.run() } }

    fun sendAfterCommit(email: OutgoingEmail) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return submit(email)
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = submit(email)
            },
        )
    }

    private fun submit(email: OutgoingEmail) {
        try {
            pool.execute { deliver(email) }
        } catch (e: RejectedExecutionException) {
            log.warn("Mail queue full, dropped a {} email", email.purpose)
        }
    }

    private fun deliver(email: OutgoingEmail) {
        try {
            sender.send(email)
        } catch (e: Exception) {
            // SMTP errors often quote the recipient.
            log.warn("Could not send a {} email: {}: {}", email.purpose, e.javaClass.simpleName, redact(e.message))
        }
    }

    /** Lets queued emails go out on shutdown, for a few seconds. */
    override fun destroy() {
        pool.shutdown()
        pool.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val POOL_SIZE = 2
        const val QUEUE_SIZE = 500
        const val SHUTDOWN_SECONDS = 10L
        val emailAddress = Regex("""[^\s<>"',;:()\[\]]+@[^\s<>"',;:()\[\]]+""")

        fun redact(message: String?): String = message.orEmpty().replace(emailAddress, "<address>")
    }
}

/** Picks the [EmailSender] named by `hovanki.mail.sender`. */
@Configuration(proxyBeanMethods = false)
class MailConfig {
    @Bean
    fun emailSender(
        properties: MailProperties,
        javaMailSender: ObjectProvider<JavaMailSender>,
        clock: Clock,
    ): EmailSender = when (properties.sender) {
        MailProperties.Sender.SMTP -> {
            val mailSender = checkNotNull(javaMailSender.ifAvailable) {
                "hovanki.mail.sender=smtp needs an SMTP server: set spring.mail.host (SPRING_MAIL_HOST), " +
                    "spring.mail.port, spring.mail.username and spring.mail.password"
            }
            val from = checkNotNull(properties.from?.takeIf { it.isNotBlank() }) {
                "hovanki.mail.sender=smtp needs a From address: set hovanki.mail.from (HOVANKI_MAIL_FROM)"
            }
            SmtpEmailSender(mailSender, from)
        }

        MailProperties.Sender.LOG -> LogEmailSender()

        MailProperties.Sender.RECORDING -> RecordingEmailSender(clock, properties.recordingLimit)
    }
}
