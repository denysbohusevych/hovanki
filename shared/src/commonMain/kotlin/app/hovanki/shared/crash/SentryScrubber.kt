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
    private const val MAX_EMAIL_LOCAL_PART = 64

    /**
     * How much of a text is scrubbed at most: what is left after [MAX_TEXT_LENGTH] is cut anyway, and Kotlin/Native's
     * regular expressions are slow on a huge text (100 000 characters took over 5 seconds on the iOS simulator).
     */
    private const val MAX_SCANNED = 4096

    /** The longest secret a cut in the middle of a word could leave half of: a token, an email, a coordinate. */
    private const val MAX_PIECE = 256

    private val bearer = Regex("""\bbearer\s+[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE)

    // `token=abc`, `"password": "abc"`, `Authorization: Bearer abc`: the name stays, the value goes.
    private val secretPair = Regex(
        """\b(token|password|passwd|pwd|secret|authorization|api[_-]?key|cookie)\b("?\s*[=:]\s*)""" +
            """("[^"]*"|'[^']*'|(?:bearer\s+)?[^\s,;&}\])"']+)""",
        RegexOption.IGNORE_CASE,
    )

    // The local part is bounded (64 is the longest there is): an open `+` makes the search quadratic in the length of
    // a long run of such characters, and an exception's text can be one (10 000 characters take half a second).
    private val email = Regex("""[A-Za-z0-9._%+-]{1,$MAX_EMAIL_LOCAL_PART}@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+""")
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
        var out = if (raw.length > MAX_SCANNED) bounded(raw) else raw
        out = secretPair.replace(out) { it.groupValues[1] + it.groupValues[2] + REDACTED }
        out = bearer.replace(out) { "Bearer $TOKEN" }
        out = email.replace(out, EMAIL)
        out = hexToken.replace(out, TOKEN)
        out = longToken.replace(out) { if (looksLikeToken(it.value)) TOKEN else it.value }
        out = namedCoordinate.replace(out) { it.groupValues[1] + it.groupValues[2] + COORDINATE }
        out = decimalCoordinate.replace(out, COORDINATE)
        return if (out.length > MAX_TEXT_LENGTH) cut(out) else out
    }

    /**
     * The first [MAX_SCANNED] characters of [raw] without the word the cut tore (its piece could be half of a token or an
     * email the patterns no longer recognise); a run longer than [MAX_PIECE] stays, the patterns read it as a whole.
     */
    private fun bounded(raw: String): String {
        val head = raw.take(MAX_SCANNED)
        val torn = head.takeLastWhile { !it.isWhitespace() && it !in ",;:()[]{}<>\"'" }
        return if (torn.length in 1..MAX_PIECE && torn.length < head.length) head.dropLast(torn.length) else head
    }

    /** The first characters of [text] and the ellipsis; a pair of surrogates (an emoji) is never torn in two. */
    private fun cut(text: String): String {
        val head = text.take(MAX_TEXT_LENGTH - 1)
        return (if (head.last().isHighSurrogate()) head.dropLast(1) else head) + ELLIPSIS
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
