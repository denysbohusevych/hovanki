package app.hovanki.server.account

import app.hovanki.server.MutableClock
import app.hovanki.server.api.AuthenticatedUser
import app.hovanki.server.mail.EmailPurpose
import app.hovanki.server.mail.RecordingEmailSender
import app.hovanki.shared.protocol.UserId
import org.springframework.boot.test.context.TestComponent
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.fail

/**
 * The context of the account tests (`@Import` it; the same imports share one cached context): a clock the tests move,
 * a [BeforeAccountDeletion] that records its calls, and [TestRoutes] for what the account routes alone can't show.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestRoutes::class)
class AccountTestConfig {
    @Bean
    @Primary
    fun mutableClock(): MutableClock = MutableClock()

    @Bean
    fun deletions(): DeletionRecorder = DeletionRecorder()
}

class DeletionRecorder : BeforeAccountDeletion {
    val deleted = CopyOnWriteArrayList<UserId>()

    /** Makes the next deletions fail inside the transaction. */
    @Volatile
    var failing = false

    override fun beforeDelete(userId: UserId) {
        deleted += userId
        check(!failing) { "Refusing to delete $userId" }
    }
}

/** Routes for tests only; `@TestComponent` keeps them out of the other tests' component scan. */
@TestComponent
@RestController
class TestRoutes {
    /** Needs an account with a confirmed email, like most of the account routes will. */
    @GetMapping(VERIFIED)
    fun verified(user: AuthenticatedUser): String = user.userId.value

    /** Works with or without an account, like creating or joining a game. */
    @GetMapping(OPTIONAL)
    fun optional(user: AuthenticatedUser?): String = user?.userId?.value ?: "guest"

    @GetMapping(NUMBER)
    fun number(@PathVariable n: Long): String = n.toString()

    companion object {
        const val VERIFIED = "/api/v1/test/verified"
        const val OPTIONAL = "/api/v1/test/optional"
        const val NUMBER = "/api/v1/test/numbers/{n}"
    }
}

/** Waits for the [count]th email to [address] (they go out on the mail pool after the commit) and returns its code. */
fun RecordingEmailSender.awaitCode(address: String, count: Int = 1, purpose: EmailPurpose? = null): String {
    val deadline = System.nanoTime() + 5_000_000_000
    while (System.nanoTime() < deadline) {
        val sent = sentTo(address).filter { purpose == null || it.email.purpose == purpose }
        if (sent.size >= count) return sent[count - 1].email.code
        Thread.sleep(10)
    }
    fail("No email #$count ($purpose) to $address")
}

/** Nicknames and emails that no other test (they share one database) uses. */
fun uniqueName(prefix: String = "user"): String = "$prefix${UUID.randomUUID().toString().replace("-", "").take(10)}"
