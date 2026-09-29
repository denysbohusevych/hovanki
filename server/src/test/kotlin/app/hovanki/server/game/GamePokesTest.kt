package app.hovanki.server.game

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.DeviceReport
import app.hovanki.shared.protocol.FeatureMode
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.rules.shrinkingZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whom a change concerns (docs/adr/0015-websockets.md, sections 5 and 6): the players whose phones are poked to sync
 * now, and the moments the game is due by the clock.
 */
class GamePokesTest {
    private val center = GeoPoint(50.4501, 30.5234)
    private val settings = GameSettings(
        zone = shrinkingZone(center, steps = 0),
        hidingSeconds = 60,
        seekingSeconds = 600,
        glowEverySeconds = 120,
        glowForSeconds = 20,
    )
    private val host = PlayerId("host")
    private val seeker = PlayerId("seeker")
    private val hider = PlayerId("hider")

    private var now = 1_700_000_000_000L
    private val game = Game(GameId("g"), "ABC234", host, settings, now)

    private fun lobby() {
        game.addPlayer(host, "Host", now)
        game.addPlayer(seeker, "Seeker", now)
        game.addPlayer(hider, "Hider", now)
        game.takePokes()
    }

    private fun seeking() {
        lobby()
        game.start(host, setOf(seeker), { "00112233445566778899aabbccddeeff00112233" }, now)
        tick(60)
        assertEquals(GamePhase.SEEKING, game.phase)
        game.takePokes()
    }

    private fun tick(seconds: Int) {
        now += seconds * 1000L
        game.advance(now)
    }

    private fun report(player: PlayerId, point: GeoPoint) {
        game.recordLocations(player, listOf(LocationSample(point, 5.0, now)), now)
        game.advance(now)
    }

    @Test
    fun theLobbyPokesEverybody() {
        lobby()
        game.addPlayer(PlayerId("late"), "Late", now)
        assertTrue(assertNotNull(game.takePokes()).everyone, "a new player")
        assertNull(game.takePokes(), "taken once")

        game.setRoles(host, setOf(seeker), now)
        assertTrue(assertNotNull(game.takePokes()).everyone, "the roles")

        // What a phone can do shows in the lobby: a change pokes, the same report again doesn't.
        val phone = DeviceReport(platform = Platform.ANDROID, bluetooth = BluetoothState.ON)
        game.recordDevice(hider, phone, now)
        assertTrue(assertNotNull(game.takePokes()).everyone)
        game.recordDevice(hider, phone, now + 3_000)
        assertNull(game.takePokes(), "nothing new")
        game.recordLocations(hider, listOf(LocationSample(center, 5.0, now)), now)
        assertNull(game.takePokes(), "a position is no poke")
    }

    @Test
    fun theEndOfAPhasePokesEverybody() {
        lobby()
        game.start(host, setOf(seeker), { "00112233445566778899aabbccddeeff00112233" }, now)
        assertTrue(assertNotNull(game.takePokes()).everyone)
        tick(59)
        assertNull(game.takePokes(), "still hiding")
        tick(1)
        assertEquals(GamePhase.SEEKING, game.phase)
        assertTrue(assertNotNull(game.takePokes()).everyone)
    }

    @Test
    fun aTeamMessagePokesTheTeamButTheSender() {
        seeking()
        game.sendChat(seeker, "anyone near the fountain?", team = true, now)
        assertNull(game.takePokes(), "the only seeker wrote to their own team")
        game.sendChat(host, "I'm behind the kiosk", team = true, now)
        val pokes = assertNotNull(game.takePokes())
        assertFalse(pokes.everyone)
        assertTrue(pokes.concerns(hider), "the other hider reads it")
        assertFalse(pokes.concerns(host), "the sender has the answer")
        assertFalse(pokes.concerns(seeker), "the other team can't read it")

        game.sendChat(hider, "good luck", team = false, now)
        val all = assertNotNull(game.takePokes())
        assertTrue(all.concerns(seeker) && all.concerns(host) && !all.concerns(hider))
    }

    @Test
    fun aClaimsTimeOutPokesEverybody() {
        seeking()
        report(seeker, center)
        report(hider, center.moveBy(10.0, 0.0))
        game.takePokes()
        game.claimCatch(seeker, hider, CatchId("c1"), now)
        game.takePokes()
        val due = assertNotNull(game.nextDueMillis(now))
        assertEquals(now + settings.rules.catchCodeTimeoutSeconds * 1000L, due)
        now = due
        game.advance(now)
        assertTrue(assertNotNull(game.takePokes()).everyone, "the hider is caught")
    }

    @Test
    fun theClockBringsTheNextMoment() {
        lobby()
        assertNull(game.nextDueMillis(now), "nothing is due in the lobby")
        val start = now
        game.start(host, setOf(seeker), { "00112233445566778899aabbccddeeff00112233" }, now)
        assertEquals(start + 60_000, game.nextDueMillis(now), "the end of hiding")
        tick(60)
        val seekingStart = start + 60_000
        // The first glow starts a full interval into the search, before the search's end.
        assertEquals(seekingStart + 120_000, game.nextDueMillis(now))
        game.takePokes()

        now = seekingStart + 120_000
        game.advance(now)
        game.pokeDue(now)
        assertTrue(assertNotNull(game.takePokes()).everyone, "the hiders glow")
        assertEquals(seekingStart + 140_000, game.nextDueMillis(now), "the glow's end")
        game.pokeDue(now)
        assertNull(game.takePokes(), "a moment pokes once")
    }

    @Test
    fun aRevealByTheClockPokesTheSeekersAndThatHider() {
        seeking()
        // The radar is required and the hider turns Bluetooth off.
        val radar = Game(
            GameId("r"),
            "RAD234",
            host,
            settings.copy(
                features = settings.features.copy(radar = FeatureMode.REQUIRED),
            ),
            now,
        )
        val on = DeviceReport(platform = Platform.ANDROID, bluetooth = BluetoothState.ON)
        for (id in listOf(host, seeker, hider)) {
            radar.addPlayer(id, id.value, now)
            radar.recordDevice(id, on, now)
        }
        radar.start(host, setOf(seeker), { "00112233445566778899aabbccddeeff00112233" }, now)
        now += 60_000
        radar.advance(now)
        radar.takePokes()
        radar.pokeDue(now)
        radar.takePokes()

        radar.recordDevice(hider, on.copy(bluetooth = BluetoothState.OFF), now)
        val warned = assertNotNull(radar.takePokes())
        assertTrue(warned.concerns(hider) && !warned.concerns(seeker), "the hider is warned")
        val reveal = now + settings.rules.radarOffRevealSeconds * 1000L
        assertEquals(reveal, radar.nextDueMillis(now))

        now = reveal
        radar.advance(now)
        radar.pokeDue(now)
        val seen = assertNotNull(radar.takePokes())
        assertTrue(seen.concerns(seeker) && seen.concerns(hider) && !seen.concerns(host) && !seen.everyone, "$seen")
    }
}
