package app.hovanki.server.game

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.AreaNorms
import app.hovanki.shared.protocol.BigGameId
import app.hovanki.shared.protocol.BigGameInfo
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.rules.shrinkingZone
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A big game's round (docs/adr/0010-big-games.md): hosted by the server, a poll brings only what the viewer needs, the
 * GPS rule decides disputes.
 */
class GameBigGameTest {
    private val center = GeoPoint(50.4501, 30.5234)
    private val settings = GameSettings(
        zone = shrinkingZone(center, initialRadiusMeters = 500.0, steps = 0),
        hidingSeconds = 0,
        seekingSeconds = 600,
    )
    private var now = 1_700_000_000_000L
    private val info = BigGameInfo(BigGameId("saturday"), "Saturday", now + 60_000, "Europe/Kyiv", signedUp = 40)
    private val game = Game(GameId("g"), "ABC234", Game.SERVER_HOST, settings, now, AreaNorms(), info, maxPlayers = 40)
    private val players = (1..40).map { PlayerId("p$it") }

    init {
        players.forEachIndexed { i, id -> game.addPlayer(id, "P$i", now, UserId("u$i")) }
    }

    private fun at(eastMeters: Double) = center.moveBy(eastMeters, 0.0)

    private fun report(player: PlayerId, point: GeoPoint) {
        game.recordLocations(player, listOf(LocationSample(point, 5.0, now)), now)
    }

    @Test
    fun theLobbyShowsMeMyFriendsAndHowManyThereAre() {
        game.setFriends(players[0], setOf(UserId("u5"), UserId("u7"), UserId("nobody")))

        val snapshot = game.snapshotFor(players[0], now)

        assertEquals(setOf(players[0], players[5], players[7]), snapshot.players.map { it.id }.toSet())
        assertEquals(40, snapshot.counts?.players)
        assertEquals(Game.SERVER_HOST, snapshot.hostId)
        assertEquals("Saturday", snapshot.bigGame?.title)
    }

    @Test
    fun anOrdinaryGameShowsEverybody() {
        val ordinary = Game(GameId("o"), "ABC235", PlayerId("p1"), settings, now).apply {
            addPlayer(PlayerId("p1"), "P1", now)
            addPlayer(PlayerId("p2"), "P2", now)
        }

        val snapshot = ordinary.snapshotFor(PlayerId("p1"), now)

        assertEquals(2, snapshot.players.size)
        assertNull(snapshot.counts)
    }

    @Test
    fun theServerStartsItWithTheSeekersItDraws() {
        val refused = assertFailsWith<GameException> { game.start(players[0], setOf(players[1]), { SECRET }, now) }
        assertEquals(ErrorCode.FORBIDDEN, refused.code)

        game.startByServer(seekers = 5, random = Random(3), newCatchCodeSecret = { SECRET }, nowMillis = now)

        assertEquals(GamePhase.HIDING, game.phase)
        val counts = assertNotNull(game.snapshotFor(players[0], now).counts)
        assertEquals(5, counts.seekers)
        assertEquals(35, counts.hidersActive)
    }

    @Test
    fun aSeekerSeesTheOtherSeekersAndTheRevealedHidersOnly() {
        game.startByServer(seekers = 3, random = Random(1), newCatchCodeSecret = { SECRET }, nowMillis = now)
        game.advance(now)
        val roles = players.associateWith { id -> game.snapshotFor(id, now).me.role }
        val seekers = roles.filterValues { it == Role.SEEKER }.keys.toList()
        val hiders = roles.filterValues { it == Role.HIDER }.keys.toList()
        // One hider far out of the zone (from the first fix: a jump there would be too fast to be true), revealed once
        // the server is sure: every fix of the last 20 s out.
        for (player in players) report(player, if (player == hiders[0]) at(900.0) else center)
        repeat(12) {
            now += 2_000
            for (player in players) report(player, if (player == hiders[0]) at(900.0) else center)
            game.advance(now)
        }

        val seen = game.snapshotFor(seekers[0], now).players

        assertEquals(seekers.toSet() + hiders[0], seen.map { it.id }.toSet())
        assertEquals(VisibilityReason.OUT_OF_ZONE, seen.single { it.id == hiders[0] }.location?.exactReason)
        val hider = game.snapshotFor(hiders[1], now)
        assertEquals(listOf(hiders[1]), hider.players.map { it.id }, "a hider sees nobody")
        assertEquals(40, hider.counts?.players)
    }

    @Test
    fun aDisputeIsDecidedByGpsAtOnce() {
        game.startByServer(seekers = 1, random = Random(1), newCatchCodeSecret = { SECRET }, nowMillis = now)
        game.advance(now)
        val seeker = players.first { game.snapshotFor(it, now).me.role == Role.SEEKER }
        val hider = players.first { it != seeker }
        for (player in players) report(player, center)
        game.advance(now)

        game.claimCatch(seeker, hider, CatchId("c"), now)
        assertTrue(game.snapshotFor(seeker, now).players.any { it.id == hider }, "the claim shows its hider")
        game.disputeCatch(CatchId("c"), hider, now)

        val claim = game.snapshotFor(seeker, now).catches.single()
        assertEquals(CatchStatus.CONFIRMED, claim.status, "5 m apart: the rule says caught, nobody votes")
        assertEquals(PlayerStatus.CAUGHT, game.snapshotFor(hider, now).me.status)
    }

    @Test
    fun theReplayOfMeAndMyFriends() {
        game.setFriends(players[0], setOf(UserId("u1")))
        game.startByServer(seekers = 1, random = Random(1), newCatchCodeSecret = { SECRET }, nowMillis = now)
        game.advance(now)
        game.endNow(now + 1_000)

        val tracks = game.tracks(players[0])

        assertEquals(setOf(players[0], players[1]), tracks.tracks.map { it.playerId }.toSet())
    }

    @Test
    fun anEmptyLobbyWaits() {
        for (player in players) assertEquals(false, game.leave(player, now), "never removed")
        assertEquals(Game.SERVER_HOST, game.snapshotForEmpty())
    }

    /** The host after everybody left: still the server (a snapshot needs a player, so the admin's view). */
    private fun Game.snapshotForEmpty(): PlayerId = if (adminView(now).hostName ==
        Game.SERVER_HOST_NAME
    ) {
        Game.SERVER_HOST
    } else {
        PlayerId("")
    }

    private companion object {
        const val SECRET = "00112233445566778899aabbccddeeff00112233"
    }
}
