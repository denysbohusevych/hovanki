package app.hovanki.shared.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class SentryScrubberTest {
    private val token = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    @Test
    fun bearerTokenGoesAndTheSchemeStays() {
        // In a header the whole value goes.
        assertEquals(
            "HTTP 401 for Authorization: [redacted] on /api/v1/sync",
            SentryScrubber.text("HTTP 401 for Authorization: Bearer $token on /api/v1/sync"),
        )
        // Anywhere else the scheme stays: it says what kind of failure it was.
        assertEquals(
            "rejected: Bearer [token] on /api/v1/sync",
            SentryScrubber.text("rejected: Bearer $token on /api/v1/sync"),
        )
    }

    @Test
    fun bearerWithAnUnusualAlphabetGoes() {
        val out = SentryScrubber.text("bearer abc.DEF-123_xyz~+/= then more")
        assertEquals("Bearer [token] then more", out)
    }

    @Test
    fun namedSecretsLoseTheirValues() {
        assertEquals("login failed token=[redacted]", SentryScrubber.text("login failed token=abc"))
        assertEquals("""{"password":[redacted]}""", SentryScrubber.text("""{"password":"hunter2 hunter2"}"""))
        assertEquals("Cookie: [redacted]; other", SentryScrubber.text("Cookie: session=1; other"))
        assertEquals("api_key: [redacted]", SentryScrubber.text("api_key: 12345"))
    }

    @Test
    fun longHexTokensGoWhereverTheySit() {
        val out = SentryScrubber.text("GET /games/abc/sync?player=$token failed, again $token")
        assertFalse(token in out)
        assertEquals(2, Regex("""\[token]""").findAll(out).count(), out)
        assertEquals("id 0123456789abcde stays", SentryScrubber.text("id 0123456789abcde stays"))
    }

    @Test
    fun longBase64ishTokensGoButClassNamesStay() {
        val b64 = "q83Zk1XvB7nT0pL4sD9fGh2JwYcR5uAe"
        assertEquals("it was [token]!", SentryScrubber.text("it was $b64!"))
        val message = "Job kotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled"
        assertEquals(message, SentryScrubber.text(message))
        assertEquals("[token]", SentryScrubber.text("a".repeat(26) + "B1"))
        assertEquals("x".repeat(30), SentryScrubber.text("x".repeat(30)))
    }

    @Test
    fun emailAddressesGo() {
        assertEquals(
            "could not send to [email] or [email].",
            SentryScrubber.text("could not send to anna.k+test@mail.example.com or bob@x.io."),
        )
    }

    @Test
    fun coordinatesGoWhetherPairsOrNamedValues() {
        val pair = SentryScrubber.text("outside the zone at (55.751244, 37.617300)")
        assertEquals("outside the zone at ([coord], [coord])", pair)
        assertEquals("negative [coord] and [coord]", SentryScrubber.text("negative -33.8688 and 151.20930"))
        assertEquals("GeoPoint(lat=[coord], lon=[coord])", SentryScrubber.text("GeoPoint(lat=55.75, lon=37.6)"))
        assertEquals("""{"lat":[coord],"lng": [coord]}""", SentryScrubber.text("""{"lat":55,"lng": 37.6}"""))
    }

    @Test
    fun numbersThatAreNotPlacesStay() {
        val message = "Kotlin 2.4.20 on Android 16 (API 36), 3 retries, 0.5 s, port 8080, version 1.12.1"
        assertEquals(message, SentryScrubber.text(message))
    }

    @Test
    fun aTextIsCutAfterScrubbing() {
        val long = "x ".repeat(400)
        val out = SentryScrubber.text(long)
        assertEquals(SentryScrubber.MAX_TEXT_LENGTH, out.length)
        assertTrue(out.endsWith("…"))
        // A token that starts before the cut is gone, not half kept.
        val padded = "a".repeat(SentryScrubber.MAX_TEXT_LENGTH - 10) + " " + token
        assertFalse(token.take(12) in SentryScrubber.text(padded))
    }

    @Test
    fun aCutNeverTearsASurrogatePair() {
        // The emoji (two chars) starts at the last char that would be kept.
        val out = SentryScrubber.text("x".repeat(SentryScrubber.MAX_TEXT_LENGTH - 2) + "\uD83D\uDE00 and more")
        assertTrue(out.endsWith("x…"), out)
        assertFalse(out.any { it in '\uD800'..'\uDFFF' })
    }

    @Test
    fun aHugeTextOfOneKindOfCharactersIsScrubbedAtOnce() {
        // The email search was quadratic in the length of such a run: 50 000 characters took 13 seconds.
        val started = TimeSource.Monotonic.markNow()
        val out = SentryScrubber.text("x".repeat(50_000) + "@" + "y".repeat(50_000))
        assertEquals(SentryScrubber.MAX_TEXT_LENGTH, out.length)
        assertTrue(started.elapsedNow() < 5.seconds, "took ${started.elapsedNow()}")
    }

    @Test
    fun aSecretTornByTheScanLimitDoesNotLeakHalfOfIt() {
        // Mostly tokens: they shrink to [token], so the text past the scan limit would come into the first 300.
        val tokens = (1..200).joinToString(" ") { "AbCdEf0123456789XyZaBcDeF$it" }
        val out = SentryScrubber.text(tokens + " anna@example.com")
        assertTrue("AbCdEf" !in out, out)
        assertTrue("anna" !in out, out)
    }

    @Test
    fun anEmailAfterALongRunOfWordCharactersStillGoes() {
        val out = SentryScrubber.text("x".repeat(100) + " anna@example.com")
        assertTrue("anna@example.com" !in out, out)
        assertTrue(out.endsWith("[email]"), out)
    }

    @Test
    fun missingTextStaysMissing() {
        assertNull(SentryScrubber.textOrNull(null))
        assertEquals("ok", SentryScrubber.textOrNull("ok"))
    }

    @Test
    fun screenNamesAreWordsAndNothingElse() {
        assertEquals("lobby", SentryScrubber.screenName("lobby"))
        assertEquals("main.friends", SentryScrubber.screenName("main.friends"))
        assertEquals("game-results", SentryScrubber.screenName("game-results"))
        assertNull(SentryScrubber.screenName(null))
        assertNull(SentryScrubber.screenName(""))
        assertNull(SentryScrubber.screenName("Lobby of Anna"))
        assertNull(SentryScrubber.screenName("anna@example.com"))
        assertNull(SentryScrubber.screenName("55.7512"))
        assertNull(SentryScrubber.screenName("a".repeat(41)))
    }
}
