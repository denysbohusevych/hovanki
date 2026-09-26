package app.hovanki.server.account

import app.hovanki.shared.rules.AccountRules
import app.hovanki.shared.totp.toHex
import java.security.MessageDigest
import java.text.Normalizer

/**
 * The server's side of [AccountRules]: Unicode NFKC for nicknames (common Kotlin has none), so a fullwidth `ｂｏｂ` is
 * the same nickname as `bob`; lookup keys; hashes of secrets.
 */
object AccountKeys {
    /** The nickname as stored: trimmed and NFKC-normalized. [AccountRules.isValidNickname] applies to the result. */
    fun normalizeNickname(nickname: String): String = Normalizer.normalize(nickname.trim(), Normalizer.Form.NFKC)

    /** `users.nickname_key`: every spelling of one nickname (case, compatibility forms) has the same key. */
    fun nicknameKey(nickname: String): String = AccountRules.nicknameKey(normalizeNickname(nickname))

    /** `users.email_key`. */
    fun emailKey(email: String): String = AccountRules.emailKey(email)

    /** A login is an email if it has an `@` (a nickname can't), else a nickname. Rate limits count per this key. */
    fun loginKey(login: String): String =
        (if ('@' in login) emailKey(login) else nicknameKey(login)).take(AccountRules.EMAIL_MAX_LENGTH)

    /** What is stored instead of an account token (`account_sessions.token_hash`). */
    fun tokenHash(token: String): String = sha256(token)

    /** What is stored instead of an emailed code: bound to the user and the purpose. */
    fun codeHash(userId: String, purpose: String, code: String): String = sha256("$userId:$purpose:$code")

    /** Compares two hashes in constant time. */
    fun sameHash(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()
}
