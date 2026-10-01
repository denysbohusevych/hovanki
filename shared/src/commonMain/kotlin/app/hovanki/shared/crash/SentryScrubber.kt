package app.hovanki.shared.crash

/**
 * What a crash report may carry out of the phone or the server (docs/adr/0018-field-test-build.md §7): the field
 * test build (`preview`) and the staging server report errors to Sentry, and every text goes through here first. The
 * same filter on both sides, so it lives in `:shared`; the SDK glue (Android, Swift, Spring) only calls it.
 *
 * It finds what looks like a secret or a place: bearer tokens, `token=…` style pairs, long hex strings (the game and
 * account tokens are 64 hex digits) and long base64-like strings, email addresses, and coordinates (a decimal with four
 * or more digits after the point, or a `lat=`/`lon=` pair). It can not find what has no shape: a nickname or a chat
 * message in an exception's text. Against those the glue drops the event's user, request, extras and tags altogether,
 * keeps breadcrumbs to screen names ([screenName]) and [text] cuts a text short ([MAX_TEXT_LENGTH]).
 */
object SentryScrubber {
    /** The longest text a report keeps; the rest is cut after scrubbing, never before: a cut could hide a token. */
    const val MAX_TEXT_LENGTH = 300

    /** The breadcrumbs the app adds itself, the screens, carry this category; the glue drops every other one. */
    const val SCREEN_CATEGORY = "screen"

    /** What a token, an email address, a coordinate and a secret's value are replaced with. */
    const val TOKEN = "[token]"
    const val EMAIL = "[email]"
    const val COORDINATE = "[coord]"
    const val REDACTED = "[redacted]"

    private const val ELLIPSIS = "…"
    private const val MIN_BASE64_TOKEN = 24
    private const val MAX_SCREEN_NAME = 40

    private val bearer = Regex("""\bbearer\s+[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE)

    // `token=abc`, `"password": "abc"`, `Authorization: Bearer abc`: the name stays, the value goes.
    private val secretPair = Regex(
        """\b(token|password|passwd|pwd|secret|authorization|api[_-]?key|cookie)\b("?\s*[=:]\s*)""" +
            """("[^"]*"|'[^']*'|(?:bearer\s+)?[^\s,;&}\])"']+)""",
        RegexOption.IGNORE_CASE,
    )
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+""")
    private val hexToken = Regex("""[0-9a-fA-F]{16,}""")
    private val longToken = Regex("""[A-Za-z0-9_-]{$MIN_BASE64_TOKEN,}""")

    // `lat=55.75`, `"lon": 37.6`: a coordinate named as one, whatever its precision.
    private val namedCoordinate = Regex(
        """\b(lat|lon|lng|latitude|longitude)\b("?\s*[=:]\s*)-?\d+(?:\.\d+)?""",
        RegexOption.IGNORE_CASE,
    )

    // 55.751244, -37.6173: four digits after the point are about 10 meters.
    private val decimalCoordinate = Regex("""-?\d{1,3}\.\d{4,}""")

    private val screen = Regex("""[a-z][a-z0-9_.-]{0,${MAX_SCREEN_NAME - 1}}""")

    /** [raw] without secrets and coordinates, at most [MAX_TEXT_LENGTH] characters. */
    fun text(raw: String): String {
        var out = raw
        out = secretPair.replace(out) { it.groupValues[1] + it.groupValues[2] + REDACTED }
        out = bearer.replace(out) { "Bearer $TOKEN" }
        out = email.replace(out, EMAIL)
        out = hexToken.replace(out, TOKEN)
        out = longToken.replace(out) { if (looksLikeToken(it.value)) TOKEN else it.value }
        out = namedCoordinate.replace(out) { it.groupValues[1] + it.groupValues[2] + COORDINATE }
        out = decimalCoordinate.replace(out, COORDINATE)
        return if (out.length > MAX_TEXT_LENGTH) out.take(MAX_TEXT_LENGTH - 1) + ELLIPSIS else out
    }

    /** [text] for a value that may be missing. */
    fun textOrNull(raw: String?): String? = raw?.let(::text)

    /**
     * [raw] if it is a screen's name (lowercase words with dots, dashes or underscores, no spaces) and nothing else:
     * a breadcrumb's message is never free text. `null` otherwise.
     */
    fun screenName(raw: String?): String? = raw?.takeIf { screen.matches(it) }

    /** Base64 or base64url of random bytes has digits and both cases of letters; a long class name rarely has all. */
    private fun looksLikeToken(candidate: String): Boolean =
        candidate.any { it in '0'..'9' } && candidate.any { it in 'a'..'z' } && candidate.any { it in 'A'..'Z' }
}
