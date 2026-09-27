package app.hovanki.server.account

import app.hovanki.shared.rules.AccountRules
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component

/** BCrypt (`hovanki.accounts.bcrypt-strength`). Every check costs the same, whether the account exists or not. */
@Component
class PasswordHasher(properties: AccountProperties) {
    private val encoder = BCryptPasswordEncoder(properties.bcryptStrength)

    /** Checked when there is no account, so an unknown login takes as long as a wrong password. */
    private val dummyHash = hash("not the password of any account")

    fun hash(password: String): String = checkNotNull(encoder.encode(password))

    /** Whether [password] is the one of [hash]; a null [hash] (no such account) is never a match. */
    fun matches(password: String, hash: String?): Boolean {
        // BCrypt refuses more than 72 bytes; such a password was never accepted, so it never matches.
        val usable = password.encodeToByteArray().size <= AccountRules.PASSWORD_MAX_BYTES
        val matches = encoder.matches(if (usable) password else "", hash ?: dummyHash)
        return matches && usable && hash != null
    }
}
