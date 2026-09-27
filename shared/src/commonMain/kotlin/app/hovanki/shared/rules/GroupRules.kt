package app.hovanki.shared.rules

/** Groups of friends ("компании"): the server enforces the limits, the app uses them for form hints. */
object GroupRules {
    const val NAME_MAX_LENGTH = 40
    const val MAX_MEMBERS = 30

    /** Groups one user may own. */
    const val MAX_OWNED_GROUPS = 20

    /** The name as stored: trimmed, inner whitespace runs (including line breaks) collapsed into one space. */
    fun normalizeName(name: String): String = name.trim().split(WHITESPACE).joinToString(" ")

    /** 1..40 characters after [normalizeName], no control characters. */
    fun isValidName(name: String): Boolean {
        val normalized = normalizeName(name)
        return normalized.length in 1..NAME_MAX_LENGTH && normalized.none { it.isISOControl() }
    }

    private val WHITESPACE = Regex("\\s+")
}
