package app.hovanki.server.game

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.SpectatorId
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.rules.shrinkingZone
import app.hovanki.shared.totp.catchCodeTotp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spectators of open games and the game's recording (docs/adr/0011-spectators-and-recordings.md). */
class SpectatorTest {
    private val center = GeoPoint(50.4501, 30.5234)
    private val settings = GameSettings(
        zone = shrinkingZone(center, steps = 0),
        hidingSeconds = 60,
        seekingSeconds = 600,
        openGame = true,
        spectatorDelaySeconds = 60,
    )
    private val host = PlayerId("host")
    private val seeker = PlayerId("seeker")
    private val hider = PlayerId("hider")
    private val alice = UserId("alice")
    private val bob = UserId("bob")
    private val fan = UserId("fan")
    private val secret = "00112233445566778899aabbccddeeff00112233"

    private var now = 1_700_000_000_000L

    private fun game(settings: GameSettings = this.settings) = Game(GameId("g"), "ABC234", host, settings, now).apply {
        addPlayer(host, "alice", now, alice)
        addPlayer(seeker, "bob", now, bob)
        addPlayer(hider, "Guest", now)
    }

    private fun Game.tick(seconds: Int) {
        now += seconds * 1000L
        advance(now)
    }

    private fun Game.report(player: PlayerId, point: GeoPoint) {
        recordLocations(player, listOf(LocationSample(point, 5.0, now)), now)
        advance(now)
    }

    private fun Game.watching(): SpectatorId = watch(SpectatorId("s1"), fan, now)

    @Test
    fun onlyOpenGamesAreWatched() {
        val closed = game(settings.copy(openGame = false))
        val error = assertFailsWith<GameException> { closed.watching() }
        assertEquals(ErrorReason.GAME_NOT_OPEN, error.reason)
        assertFailsWith<GameException> { closed.liveView(now) }
    }

    @Test
    fun playersNeverWatchTheirOwnGame() {
        val game = game()
        val error = assertFailsWith<GameException> { game.watch(SpectatorId("s1"), alice, now) }
        assertEquals(ErrorReason.PLAYING_THIS_GAME, error.reason)

        // A spectator who joins stops watching: a player never sees everybody.
        val spectator = game.watching()
        game.addPlayer(PlayerId("fan"), "fan", now, fan)
        val gone = assertFailsWith<GameException> { game.spectatorSnapshot(spectator, now) }
        assertEquals(ErrorCode.NOT_FOUND, gone.code)
    }

    @Test
    fun oneAccountIsOneSpectatorAndThePlayersSeeHowManyWatch() {
        val game = game()
        val first = game.watching()
        assertEquals(first, game.watch(SpectatorId("s2"), fan, now), "a second phone of the same account")
        assertEquals(1, game.snapshotFor(host, now).spectators)
        assertEquals(1, game.adminView(now).spectators)
        game.tick(31)
        assertEquals(0, game.snapshotFor(host, now).spectators, "not asked for half a minute: gone")
        game.spectatorSnapshot(first, now)
        assertEquals(1, game.snapshotFor(host, now).spectators)

        assertEquals(listOf(first), game.dropSpectators())
        assertFailsWith<GameException> { game.spectatorSnapshot(first, now) }
    }

    @Test
    fun spectatorsSeeTheGameTheDelayBehind() {
        val game = game()
        val spectator = game.watching()
        game.start(host, setOf(seeker), { secret }, now)
        val hidingStart = now
        // The hider walks east, a point every 5 s.
        repeat(12) { step ->
            game.report(hider, center.moveBy(step * 10.0, 0.0))
            game.tick(5)
        }
        assertEquals(GamePhase.SEEKING, game.phase)
        val seekingStart = hidingStart + 60_000

        // A minute behind: still hiding, and the hider where they were a minute ago.
        var view = game.spectatorSnapshot(spectator, now)
        assertEquals(now - 60_000, view.atMillis)
        assertEquals(60, view.delaySeconds)
        assertEquals(GamePhase.HIDING, view.phase)
        assertEquals(seekingStart, view.phaseEndsAtMillis)
        assertNull(view.zoneStartedAtMillis, "the search had not started then")
        val shownHider = view.players.single { it.id == hider }
        assertEquals(hidingStart, shownHider.location?.atMillis, "their first point, not a newer one")
        assertEquals(center, shownHider.location?.point)
        assertNull(view.players.single { it.id == seeker }.location, "no fixes")
        assertTrue(view.players.all { it.status == PlayerStatus.ACTIVE })

        game.tick(30)
        view = game.spectatorSnapshot(spectator, now)
        val location = assertNotNull(view.players.single { it.id == hider }.location)
        assertTrue(location.atMillis <= view.atMillis, "nothing newer than the moment shown")
        assertTrue(location.atMillis > view.atMillis - 5_000)
        val trail = view.players.single { it.id == hider }.trail
        assertTrue(trail.isNotEmpty() && trail.all { it.atMillis in view.atMillis - 60_000..view.atMillis })

        // Caught now: the spectators see it a minute later.
        game.report(seeker, center.moveBy(110.0, 0.0))
        game.claimCatch(seeker, hider, CatchId("c1"), now)
        game.confirmCatch(CatchId("c1"), seeker, catchCodeTotp(secret, settings.rules).codeAt(now), now)
        assertEquals(PlayerStatus.CAUGHT, game.snapshotFor(host, now).players.single { it.id == hider }.status)
        val caughtAt = now
        view = game.spectatorSnapshot(spectator, now)
        assertEquals(PlayerStatus.ACTIVE, view.players.single { it.id == hider }.status)
        assertNull(view.players.single { it.id == hider }.outAtMillis)
        game.tick(60)
        view = game.spectatorSnapshot(spectator, now)
        assertEquals(GamePhase.SEEKING, view.phase)
        val caught = view.players.single { it.id == hider }
        assertEquals(PlayerStatus.CAUGHT, caught.status)
        assertEquals(caughtAt, caught.outAtMillis)
        assertEquals(seeker, caught.caughtBy)
    }

    @Test
    fun adminsSeeOpenGamesLive() {
        val game = game()
        game.start(host, setOf(seeker), { secret }, now)
        game.report(hider, center)
        game.tick(5)
        val live = game.liveView(now)
        assertEquals(now, live.serverTimeMillis)
        assertEquals(GamePhase.HIDING, live.phase)
        assertNotNull(live.players.single { it.id == hider }.location, "no delay")
        assertNull(live.zoneNow, "no zone before the search")
        game.tick(60)
        assertEquals(settings.zone.initial, game.liveView(now).zoneNow)
    }

    @Test
    fun theRecordingHasEverybodysWayGuestsToo() {
        val game = game()
        game.start(host, setOf(seeker), { secret }, now)
        repeat(4) { step ->
            game.report(hider, center.moveBy(step * 10.0, 0.0))
            game.report(seeker, center.moveBy(0.0, step * 10.0))
            game.tick(5)
        }
        game.tick(settings.hidingSeconds + settings.seekingSeconds)
        assertEquals(GamePhase.FINISHED, game.phase)

        val record = assertNotNull(game.takeFinishedRecord())
        val tracks = record.recording.associateBy { it.playerId }
        assertEquals(setOf(host, seeker, hider), tracks.keys)
        assertEquals(4, tracks.getValue(hider).points.size)
        assertNull(tracks.getValue(hider).userId, "a guest")
        assertEquals(bob, tracks.getValue(seeker).userId)
        assertEquals(PlayerStatus.ACTIVE, tracks.getValue(hider).status)
        assertEquals(0, tracks.getValue(host).points.size, "no fixes, no way")
    }
}
