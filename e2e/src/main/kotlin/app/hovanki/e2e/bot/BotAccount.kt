package app.hovanki.e2e.bot

import app.hovanki.shared.rules.AccountRules
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a person knows about their account: what they type into the login form. Kept by the scenario, not by the
 * phone: a new phone logs in with the same one.
 */
data class BotAccount(val nickname: String, val email: String, val password: String) {
    /** The same account after a password change or reset. */
    fun withPassword(newPassword: String): BotAccount = copy(password = newPassword)

    override fun toString(): String = "$nickname <$email>"

    companion object {
        /** A password that passes [AccountRules.isValidPassword]. */
        const val DEFAULT_PASSWORD = "hide-and-seek-42"

        private const val DOMAIN = "hovanki.test"
        private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"
        private const val RUN_ID_LENGTH = 5

        /** Differs between runs: an external server (HOVANKI_E2E_SERVER_URL) keeps the accounts of earlier runs. */
        private val run = SecureRandom().let { random ->
            (1..RUN_ID_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        }
        private val counter = AtomicInteger()

        /**
         * A nickname and an email nobody else has, for a player called [name]: scenarios run in parallel against one
         * server, which allows each only once. `Anna` → `Anna_k3x9a1` / `anna_k3x9a1@hovanki.test`.
         */
        fun unique(name: String, password: String = DEFAULT_PASSWORD): BotAccount {
            val suffix = "_" + run + counter.incrementAndGet().toString(ALPHABET.length)
            val base = name.filter { it.isLetterOrDigit() }.take(AccountRules.NICKNAME_MAX_LENGTH - suffix.length)
            val nickname = base.ifEmpty { "bot" } + suffix
            require(AccountRules.isValidNickname(nickname)) { "Not a valid nickname: $nickname" }
            return BotAccount(nickname, "${nickname.lowercase()}@$DOMAIN", password)
        }
    }
}
