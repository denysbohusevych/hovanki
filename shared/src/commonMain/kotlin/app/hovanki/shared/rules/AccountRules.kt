package app.hovanki.shared.rules

/**
 * What a nickname, email and password may look like: the server enforces it, the app uses it for form hints.
 * Lookup keys make the uniqueness case-insensitive. The server applies Unicode NFKC to the nickname before
 * [isValidNickname] and [nicknameKey] (fullwidth and other compatibility forms become plain letters); Kotlin common
 * code has no NFKC, so the app checks the nickname as typed.
 */
object AccountRules {
    const val NICKNAME_MIN_LENGTH = 3
    const val NICKNAME_MAX_LENGTH = 20
    const val EMAIL_MAX_LENGTH = 254
    const val PASSWORD_MIN_LENGTH = 8
    const val PASSWORD_MAX_LENGTH = 64

    /** BCrypt ignores everything after 72 bytes: a longer password would only look stronger. */
    const val PASSWORD_MAX_BYTES = 72
    const val CODE_LENGTH = 6

    /** Languages of the emails; anything else gets [DEFAULT_LANGUAGE]. */
    val LANGUAGES = setOf("en", "ru", "uk")
    const val DEFAULT_LANGUAGE = "en"

    private const val NICKNAME_PUNCTUATION = "_.-"

    /** 3..20 characters: letters of any alphabet, digits and `_ . -`, starting with a letter or a digit. */
    fun isValidNickname(nickname: String): Boolean = nickname.length in NICKNAME_MIN_LENGTH..NICKNAME_MAX_LENGTH &&
        nickname.first().isLetterOrDigit() &&
        nickname.all { it.isLetterOrDigit() || it in NICKNAME_PUNCTUATION }

    /** Case-insensitive key of an (NFKC-normalized) nickname: two nicknames with the same key can't coexist. */
    fun nicknameKey(nickname: String): String = nickname.lowercase()

    /** The email as stored: trimmed. */
    fun normalizeEmail(email: String): String = email.trim()

    /** Deliberately loose (the emailed code is the real check): one `@`, something before it, a dot in the domain. */
    fun isValidEmail(email: String): Boolean {
        val normalized = normalizeEmail(email)
        if (normalized.length > EMAIL_MAX_LENGTH || normalized.any { it.isWhitespace() || it.isISOControl() }) {
            return false
        }
        val at = normalized.indexOf('@')
        if (at <= 0 || normalized.indexOf('@', at + 1) >= 0) return false
        val domain = normalized.substring(at + 1)
        return '.' in domain && !domain.startsWith('.') && !domain.endsWith('.') && ".." !in domain
    }

    /** Case-insensitive key of a normalized email: one account per address. */
    fun emailKey(email: String): String = normalizeEmail(email).lowercase()

    /** 8..64 characters and at most 72 bytes of UTF-8. */
    fun isValidPassword(password: String): Boolean = password.length in PASSWORD_MIN_LENGTH..PASSWORD_MAX_LENGTH &&
        password.encodeToByteArray().size <= PASSWORD_MAX_BYTES

    /** An emailed code: exactly 6 digits, spaces around it ignored. */
    fun normalizeCode(code: String): String = code.filterNot { it.isWhitespace() }

    fun isCodeFormat(code: String): Boolean =
        normalizeCode(code).let { it.length == CODE_LENGTH && it.all { c -> c in '0'..'9' } }

    /** `ru-RU`, `uk`, `EN` → a supported language, else English. */
    fun language(tag: String?): String {
        val language = tag.orEmpty().trim().lowercase().substringBefore('-').substringBefore('_')
        return if (language in LANGUAGES) language else DEFAULT_LANGUAGE
    }
}
