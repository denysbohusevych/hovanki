package app.hovanki.e2e.scenarios

import app.hovanki.e2e.OWN_SERVER
import app.hovanki.e2e.bot.CommandResult
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.e2e.scenarioOnOwnServer
import app.hovanki.shared.protocol.ErrorReason
import kotlinx.coroutines.delay
import org.junit.jupiter.api.parallel.ResourceLock
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Rate limits, off in the `e2e` profile (the bots all come from one address), on here on a server of its own with
 * short windows: the app gets 429 `TOO_MANY_REQUESTS` with the seconds to wait, and after them it works again.
 */
@ResourceLock(OWN_SERVER)
class RateLimitTest {
    @Test
    fun rateLimitsOn() = scenarioOnOwnServer(
        "Rate limits on",
        properties = mapOf(
            "hovanki.rate-limits.enabled" to "true",
            "hovanki.rate-limits.login-per-login.count" to "3",
            "hovanki.rate-limits.login-per-login.window" to "15s",
            "hovanki.rate-limits.email-per-minute.window" to "10s",
        ),
    ) {
        val anna = player("Anna", at = PARK)
        val account = anna.signsUp()

        // Signing up sent the first code: another one only after the window.
        val tooSoon = anna.resendCode()
        expectRejected(tooSoon, ErrorReason.TOO_MANY_REQUESTS, "a new code right after the first one")
        val waitForCode = checkNotNull((tooSoon as CommandResult.Rejected).retryAfterSeconds)
        check(waitForCode in 1..10, "the app is told to wait $waitForCode s")
        delay((waitForCode + 1).seconds)
        requireOk(anna.resendCode(), "a new code once the wait is over")

        val phone = anna.newPhone()
        repeat(3) { n ->
            val wrong = phone.logIn(account.nickname, "not the password $n")
            expectRejected(wrong, ErrorReason.WRONG_CREDENTIALS, "wrong password ${n + 1}")
        }
        val locked = phone.logIn(account.nickname, account.password)
        expectRejected(locked, ErrorReason.TOO_MANY_REQUESTS, "even the right password, for now")
        val waitForLogin = checkNotNull((locked as CommandResult.Rejected).retryAfterSeconds)
        check(waitForLogin in 1..15, "the app is told to wait $waitForLogin s")
        delay((waitForLogin + 1).seconds)
        requireOk(phone.logIn(account.nickname, account.password), "the right password once the wait is over")
    }
}
