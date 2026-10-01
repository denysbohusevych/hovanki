package app.hovanki.server.game

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.lab.ServerFields
import app.hovanki.shared.lab.ServerKinds
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Carry
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.DeviceReport
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.FeatureMode
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.NearbySighting
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.rules.RadarToken
import app.hovanki.shared.rules.shrinkingZone
import app.hovanki.shared.totp.catchCodeTotp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The game's events for the field log and the rules in the shadow (docs/adr/0018-field-test-build.md §3.3,
 * docs/field-test.md step 3): a field game collects them, an ordinary one nothing; the shadow never changes an outcome.
 */
class GameFieldLogTest {
    private val center = GeoPoint(50.4501, 30.5234)
    private val features = GameFeatures(radar = FeatureMode.OPTIONAL, hiderSense = true)
    private val settings = GameSettings(
        zone = shrinkingZone(center, steps = 0),
        hidingSeconds = 60,
        seekingSeconds = 600,
        glowEverySeconds = 120,
        glowForSeconds = 20,
        features = features,
    )
    private val seeker = PlayerId("seeker")
    private val hider = PlayerId("hider")
    private val other = PlayerId("other")
    private var now = 1_700_000_000_000L
    private var secretsGiven = 0

    /** Everything the game collected for the field log, in order. */
    private val collected = ArrayList<FieldEvent>()

    private fun game(settings: GameSettings = this.settings, fieldLog: Boolean = true): Game =
        Game(GameId("g"), "ABC234", seeker, settings, now).apply {
            this.fieldLog = fieldLog
            addPlayer(seeker, "Seeker", now)
            addPlayer(hider, "Hider", now)
            addPlayer(other, "Other", now)
        }

    private fun newSecret(): String = "%040x".format(++secretsGiven)

    private fun Game.take() {
        takeFieldEvents()?.let { collected += it.list }
    }

    private fun Game.begin() {
        start(seeker, setOf(seeker), ::newSecret, now)
        now += 60_000
        advance(now)
        check(phase == GamePhase.SEEKING)
        take()
    }

    private fun Game.tick(seconds: Int) {
        now += seconds * 1000L
        advance(now)
        take()
    }

    private fun Game.report(id: PlayerId, point: GeoPoint) {
        recordLocations(id, listOf(LocationSample(point, 5.0, now)), now)
        advance(now)
        take()
    }

    private fun Game.device(id: PlayerId, carry: Carry = Carry.UNKNOWN) {
        recordDevice(id, DeviceReport(Platform.ANDROID, BluetoothState.ON, carry = carry), now)
        take()
    }

    private fun Game.hears(observer: PlayerId, heard: PlayerId, rssi: Int) {
        val secret = checkNotNull(debugState(now).players.single { it.id == heard }.radarSecret)
        recordSightings(observer, listOf(NearbySighting(RadarToken.at(secret, now), rssi, now)), now)
        advance(now)
        take()
    }

    private fun Game.claim(catchId: String, code: String? = null, target: PlayerId = hider): GameException? = try {
        claimCatch(seeker, target, CatchId(catchId), now, code)
        null
    } catch (e: GameException) {
        e
    } finally {
        take()
    }

    private fun events(kind: String) = collected.filter { it.kind == kind }

    private fun FieldEvent.text(key: String): String? = (fields[key] as? JsonPrimitive)?.content

    private fun FieldEvent.number(key: String): Double? = (fields[key] as? JsonPrimitive)?.doubleOrNull

    private fun FieldEvent.flag(key: String): Boolean? = (fields[key] as? JsonPrimitive)?.booleanOrNull

    @Test
    fun anOrdinaryGameCollectsNothing() {
        val game = game(fieldLog = false)
        game.begin()
        game.device(seeker)
        game.device(hider)
        game.report(seeker, center)
        game.report(hider, center.moveBy(500.0, 0.0))
        game.hears(seeker, hider, -55)
        assertNotNull(game.claim("c1"))
        game.tick(130)
        assertEquals(emptyList(), collected)
    }

    @Test
    fun aFieldGameLogsItsPhasesClaimsFixesAndCatches() {
        val game = game()
        game.start(seeker, setOf(seeker), ::newSecret, now)
        game.take()
        assertEquals(listOf("HIDING" to "LOBBY"), events(ServerKinds.PHASE).map { it.phaseChange() })
        game.tick(60)
        assertEquals("SEEKING" to "HIDING", events(ServerKinds.PHASE).last().phaseChange())
        assertEquals(now, events(ServerKinds.PHASE).last().atMillis)

        // The fixes of a sync: how many the server took, and why not the others; their own times, no position.
        game.recordLocations(
            seeker,
            listOf(
                LocationSample(center, 5.0, now - 2_000),
                LocationSample(center, 5.0, now - 1_000, isMock = true),
            ),
            now,
        )
        game.take()
        val fixes = events(ServerKinds.FIXES).single()
        assertEquals(seeker.value, fixes.text(ServerFields.PLAYER))
        assertEquals(1.0, fixes.number(ServerFields.ACCEPTED))
        assertEquals(1.0, fixes.number(ServerFields.REFUSED_PREFIX + "MOCK"))
        assertEquals((now - 2_000).toDouble(), fixes.number(ServerFields.FIX_FROM))
        assertNoPositions()

        // GPS refuses: the other hider is far away; the distance GPS allows in meters.
        game.report(seeker, center)
        game.report(other, center.moveBy(500.0, 0.0))
        val far = assertNotNull(game.claim("c1", target = other))
        assertEquals(app.hovanki.shared.protocol.ErrorCode.TOO_FAR, far.code)
        val refused = events(ServerKinds.CLAIM).single()
        assertEquals("TOO_FAR", refused.text(ServerFields.OUTCOME))
        assertNull(refused.text(ServerFields.CATCH), "no claim was opened")
        assertTrue(checkNotNull(refused.number(ServerFields.DISTANCE)) > 400, "$refused")

        // Close enough: the claim opens, the code confirms it.
        game.report(hider, center.moveBy(10.0, 0.0))
        val code = catchCodeTotp(checkNotNull(game.snapshotFor(hider, now).me.catchCodeSecret), settings.rules)
            .codeAt(now)
        assertNull(game.claim("c2", code))
        val opened = events(ServerKinds.CLAIM).last()
        assertEquals("open" to "c2", opened.text(ServerFields.OUTCOME) to opened.text(ServerFields.CATCH))
        val caught = events(ServerKinds.CATCH).single()
        assertEquals("confirmed" to "code", caught.text(ServerFields.OUTCOME) to caught.text(ServerFields.REASON))
        assertTrue(collected.indexOf(opened) < collected.indexOf(caught), "the claim before its outcome")
        assertNoPositions()
    }

    @Test
    fun theProximityRuleAnswersInTheShadowAndChangesNothing() {
        // The rule is off in this game: the claim goes through, the shadow says it would not have.
        val off = game()
        off.begin()
        off.device(seeker)
        off.device(hider)
        off.report(seeker, center)
        off.report(hider, center.moveBy(10.0, 0.0))
        assertNull(off.claim("c1"), "the game's rules decide, not the shadow")
        val shadow = events(ServerKinds.CLAIM).single()
        assertEquals("open", shadow.text(ServerFields.OUTCOME))
        assertEquals(false, shadow.flag(ServerFields.PROXIMITY))
        assertEquals(true, shadow.flag(ServerFields.RADAR))
        assertEquals(false, shadow.flag(ServerFields.SHADOW_ACCEPT))
        assertEquals(0.0, shadow.number(ServerFields.BURNING_SECONDS))

        // The same with the rule on, field log or not: refused alike, and the shadow agrees.
        collected.clear()
        val on = settings.copy(features = features.copy(proximityCatch = true))
        for (fieldLog in listOf(true, false)) {
            val game = game(on, fieldLog)
            game.begin()
            game.device(seeker)
            game.device(hider)
            game.report(seeker, center)
            game.report(hider, center.moveBy(10.0, 0.0))
            assertEquals(ErrorReason.NOT_NEARBY, game.claim("c1")?.reason)
            // They meet: «burning» for the dwell, then the claim goes through in both.
            repeat(settings.rules.nearbyDwellSeconds + 1) {
                game.hears(seeker, hider, -50)
                if (it < settings.rules.nearbyDwellSeconds) game.tick(1)
            }
            game.report(seeker, center)
            game.report(hider, center.moveBy(10.0, 0.0))
            assertNull(game.claim("c2"))
        }
        val claims = events(ServerKinds.CLAIM)
        assertEquals(listOf("NOT_NEARBY", "open"), claims.map { it.text(ServerFields.OUTCOME) })
        assertEquals(listOf(false, true), claims.map { it.flag(ServerFields.SHADOW_ACCEPT) })
        assertTrue(
            checkNotNull(claims.last().number(ServerFields.BURNING_SECONDS)) >= settings.rules.nearbyDwellSeconds,
        )
        assertEquals(0.0, claims.last().number(ServerFields.BURNING_AGO_SECONDS))
    }

    @Test
    fun theBandsOfEveryPairWithThePocketStealthInTheShadow() {
        // The game itself goes by its rules alone: what both phones feel is the same with the field log as without.
        val felt = listOf(false, true).map { fieldLog ->
            val game = game(fieldLog = fieldLog)
            game.begin()
            game.device(seeker)
            game.device(hider, Carry.IN_POCKET)
            // −70 dBm from a pocket: «burning» with the pocket evened out, only «hot» with the stealth on top.
            game.hears(seeker, hider, -70)
            listOf(seeker, hider).map { id -> game.snapshotFor(id, now).me.radar?.contacts?.map { it.band } }
        }
        assertEquals(felt[0], felt[1])
        assertEquals(listOf(RadarBand.BURNING), felt[1][0])
        val band = events(ServerKinds.BAND).single()
        assertEquals(seeker.value to hider.value, band.text(ServerFields.OBSERVER) to band.text(ServerFields.HEARD))
        assertEquals(
            RadarBand.BURNING.name to RadarBand.NONE.name,
            band.text(ServerFields.BAND) to band.text(ServerFields.FROM),
        )
        assertEquals(RadarBand.HOT.name, band.text(ServerFields.SHADOW_BAND))
        assertEquals(false, band.flag(ServerFields.STEALTH))
    }

    @Test
    fun revealsAndGlowsWithTheirTimes() {
        val game = game()
        game.begin()
        val seekingStart = now
        // Every hider keeps reporting but Other: the stale signal reveals Other after 45 s.
        repeat(5) {
            game.report(hider, center)
            game.tick(10)
        }
        val stale = events(ServerKinds.REVEAL).single { it.text(ServerFields.PLAYER) == other.value }
        assertEquals("start" to "STALE_SIGNAL", stale.text(ServerFields.EVENT) to stale.text(ServerFields.REASON))
        assertFalse(events(ServerKinds.REVEAL).any { it.text(ServerFields.PLAYER) == hider.value })

        // The first glow, after 120 s of the search, for 20 s: at its own times.
        while (now < seekingStart + 150_000) {
            game.report(hider, center)
            game.tick(5)
        }
        val glows = events(ServerKinds.GLOW)
        assertEquals(listOf("start", "end"), glows.map { it.text(ServerFields.EVENT) })
        assertEquals(listOf(seekingStart + 120_000, seekingStart + 140_000), glows.map { it.atMillis })
        val hiderReveals = events(ServerKinds.REVEAL).filter { it.text(ServerFields.PLAYER) == hider.value }
        assertEquals(listOf("start", "end"), hiderReveals.map { it.text(ServerFields.EVENT) })
        assertEquals("GLOW", hiderReveals.first().text(ServerFields.REASON))
        assertTrue(
            checkNotNull(hiderReveals.last().number(ServerFields.SECONDS)) in 19.0..21.0,
            "${hiderReveals.last()}",
        )

        // The round's end ends every reveal.
        game.endNow(now)
        game.advance(now)
        game.take()
        assertEquals("FINISHED", events(ServerKinds.PHASE).last().text(ServerFields.PHASE))
        val ended = events(ServerKinds.REVEAL).last { it.text(ServerFields.PLAYER) == other.value }
        assertEquals("end", ended.text(ServerFields.EVENT))
    }

    private fun FieldEvent.phaseChange() = text(ServerFields.PHASE) to text(ServerFields.FROM)

    /** Nothing in the field events says where somebody was: no coordinates under any name. */
    private fun assertNoPositions() {
        for (event in collected) {
            for (key in event.fields.keys) {
                assertFalse(key in setOf("lat", "lon", "point", "latitude", "longitude"), "$event has $key")
            }
        }
    }
}
