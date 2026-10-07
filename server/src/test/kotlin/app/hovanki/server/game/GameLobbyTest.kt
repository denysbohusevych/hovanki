package app.hovanki.server.game

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.rules.shrinkingZone
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The lobby: roles everybody sees, the host's setup, leaving for good (docs/adr/0009-game-setup-glow-streets.md). */
class GameLobbyTest {
    private val center = GeoPoint(50.4501, 30.5234)
    private val settings =
        GameSettings(zone = shrinkingZone(center, steps = 0), hidingSeconds = 60, seekingSeconds = 600)
    private val host = PlayerId("host")
    private val anna = PlayerId("anna")
    private val boris = PlayerId("boris")
    private var now = 1_700_000_000_000L
    private val game = Game(GameId("g"), "ABC234", host, settings, now).apply {
        addPlayer(host, "Host", now)
        addPlayer(anna, "Anna", now)
        addPlayer(boris, "Boris", now)
    }

    private fun roles(viewer: PlayerId = anna): Map<PlayerId, Role> =
        game.snapshotFor(viewer, now).players.associate { it.id to it.role }

    @Test
    fun everybodySeesTheRolesTheHostPicked() {
        game.setRoles(host, setOf(boris), now)

        assertEquals(mapOf(host to Role.HIDER, anna to Role.HIDER, boris to Role.SEEKER), roles(anna))
        assertEquals(Role.SEEKER, game.snapshotFor(boris, now).me.role)
    }

    @Test
    fun onlyTheHostPicksRoles() {
        val error = assertFailsWith<GameException> { game.setRoles(anna, setOf(anna), now) }
        assertEquals(ErrorCode.FORBIDDEN, error.code)
        assertFailsWith<GameException> { game.setRoles(host, setOf(PlayerId("nobody")), now) }
    }

    @Test
    fun theServerDrawsTheSeekersForEverybody() {
        now += 1_000
        game.drawRoles(host, 1, Random(7), now)

        val seekers = roles().filterValues { it == Role.SEEKER }.keys
        assertEquals(1, seekers.size)
        assertEquals(now, game.snapshotFor(boris, now).rolesDrawnAtMillis)
        // The same seed, the same draw: the dice are the server's, not the phone's.
        val again = Game(GameId("h"), "ABC235", host, settings, now).apply {
            addPlayer(host, "Host", now)
            addPlayer(anna, "Anna", now)
            addPlayer(boris, "Boris", now)
            drawRoles(host, 1, Random(7), now)
        }
        assertEquals(
            seekers,
            again.snapshotFor(host, now).players.filter {
                it.role == Role.SEEKER
            }.map { it.id }.toSet(),
        )
    }

    @Test
    fun aDrawLeavesAtLeastOneHider() {
        assertFailsWith<GameException> { game.drawRoles(host, 3, Random(1), now) }
        assertFailsWith<GameException> { game.drawRoles(host, 0, Random(1), now) }
    }

    @Test
    fun leavingTheLobbyForGood() {
        game.leave(anna, now)

        assertEquals(listOf(host, boris), game.snapshotFor(host, now).players.map { it.id })
        assertFailsWith<GameException> { game.snapshotFor(anna, now) }
    }

    @Test
    fun theNextPlayerTakesOverFromALeavingHost() {
        assertFalse(game.leave(host, now))

        assertEquals(anna, game.hostId)
        game.setRoles(anna, setOf(boris), now)
        assertTrue(game.leave(anna, now) == false && game.leave(boris, now), "the last one out empties the lobby")
    }

    @Test
    fun whoIsConnected() {
        now += 5_000
        game.snapshotFor(anna, now)

        val players = game.snapshotFor(host, now + 1_000).players.associateBy { it.id }
        assertEquals(now, players.getValue(anna).lastSeenMillis)
        assertEquals(now + 1_000, players.getValue(host).lastSeenMillis)
        assertNull(players.getValue(boris).lastSeenMillis)
    }

    @Test
    fun theHostChangesTheSetupInTheLobby() {
        val longer = settings.copy(hidingSeconds = 120, glowEverySeconds = 300, glowForSeconds = 5)

        assertFalse(game.updateSettings(host, longer, now), "the same zone: nothing to load again")

        val snapshot = game.snapshotFor(anna, now)
        assertEquals(120, snapshot.settings.hidingSeconds)
        assertEquals(300, snapshot.settings.glowEverySeconds)
        assertEquals(0, snapshot.mapRevision)
    }

    @Test
    fun aNewZoneLoadsItsMapAgain() {
        game.onBuildingsLoaded(emptyList(), emptyList())
        val larger = settings.copy(zone = shrinkingZone(center, initialRadiusMeters = 800.0, steps = 0))

        assertTrue(game.updateSettings(host, larger, now))

        assertEquals(1, game.mapRevision)
        assertEquals(app.hovanki.shared.protocol.BuildingsState.LOADING, game.buildingsState)
        // Buildings of the old zone arriving late change nothing.
        game.onBuildingsLoaded(emptyList(), emptyList(), revision = 0)
        assertEquals(app.hovanki.shared.protocol.BuildingsState.LOADING, game.buildingsState)
        game.onBuildingsLoaded(emptyList(), emptyList(), revision = 1)
        assertEquals(app.hovanki.shared.protocol.BuildingsState.READY, game.buildingsState)
    }

    /** docs/adr/0014-settings-lobby-redesign-open-buildings.md: the zone's center on the map. */
    @Test
    fun theHostMovesTheZoneToTheNextParkNotToAnotherTown() {
        val nextPark = settings.copy(zone = shrinkingZone(center.moveBy(1_500.0, 800.0), steps = 0))

        assertTrue(game.updateSettings(host, nextPark, now), "a new place loads its map")
        assertEquals(center.moveBy(1_500.0, 800.0), game.settings.zone.initial.center)

        val anotherTown = settings.copy(zone = shrinkingZone(center.moveBy(0.0, 3_500.0), steps = 0))
        assertFailsWith<GameException> { game.updateSettings(host, anotherTown, now) }
    }

    @Test
    fun theThresholdsStayAsCreated() {
        game.updateSettings(host, settings.copy(rules = GameRules(catchMaxDistanceMeters = 500.0)), now)

        assertEquals(GameRules().catchMaxDistanceMeters, game.settings.rules.catchMaxDistanceMeters)
    }

    @Test
    fun onlyTheHostAndOnlyInTheLobby() {
        assertFailsWith<GameException> { game.updateSettings(anna, settings, now) }
        game.start(host, setOf(anna), { SECRET }, now)
        assertFailsWith<GameException> { game.updateSettings(host, settings, now) }
        assertFailsWith<GameException> { game.setRoles(host, setOf(boris), now) }
    }

    @Test
    fun aHiderWhoLeavesTheRoundIsOut() {
        game.start(host, setOf(anna), { SECRET }, now)

        game.leave(boris, now)

        val out = game.snapshotFor(host, now).players.single { it.id == boris }
        assertEquals(PlayerStatus.ELIMINATED, out.status)
        assertTrue(out.left)
        assertEquals(GamePhase.HIDING, game.phase, "the host still hides")
        assertFalse(game.isPlaying(boris))
        assertTrue(game.isPlaying(host))
    }

    @Test
    fun leavingDuringAClaimIsBeingCaught() {
        game.start(host, setOf(anna), { SECRET }, now)
        now += 60_000
        game.advance(now)
        report(anna, center)
        report(boris, center)
        game.claimCatch(anna, boris, CatchId("c"), now)

        game.leave(boris, now)

        val snapshot = game.snapshotFor(anna, now)
        assertEquals(CatchStatus.CONFIRMED, snapshot.catches.single().status)
        assertEquals(PlayerStatus.CAUGHT, snapshot.players.single { it.id == boris }.status)
    }

    @Test
    fun theRoundEndsWhenTheLastSeekerLeaves() {
        game.start(host, setOf(anna), { SECRET }, now)

        game.leave(anna, now)

        assertEquals(GamePhase.FINISHED, game.phase)
    }

    @Test
    fun theRoundEndsWhenTheLastHiderLeaves() {
        game.start(host, setOf(anna), { SECRET }, now)

        game.leave(host, now)
        game.leave(boris, now)

        assertEquals(GamePhase.FINISHED, game.phase)
    }

    @Test
    fun theHostOpensTheNextGameOnceTheRoundIsOver() {
        val open = Game(GameId("o"), "ABC237", host, settings.copy(openGame = true), now).apply {
            addPlayer(host, "Host", now)
            addPlayer(anna, "Anna", now)
        }
        assertFailsWith<GameException> { open.playAgainAsk(host) }
        open.start(host, setOf(anna), { SECRET }, now)
        open.leave(anna, now)
        assertEquals(GamePhase.FINISHED, open.phase)

        val ask = open.playAgainAsk(host)
        assertTrue(ask.isHost)
        assertEquals("Host", ask.name)
        assertEquals(settings.copy(openBuildings = emptyList()), ask.settings, "the same setup, not open to spectators")
        assertNull(ask.next)
        val next = NextGame(GameId("n"), "XYZ234")
        assertFailsWith<GameException> { open.openPlayAgain(anna, next) }
        open.takePokes()
        open.openPlayAgain(host, next)

        assertEquals(next, open.playAgainAsk(host).next)
        assertEquals("XYZ234", open.snapshotFor(host, now).playAgain?.joinCode)
        assertTrue(open.takePokes() != null, "everybody's results hear of it soon")
    }

    @Test
    fun aZoneByStreetsMustBeThereBeforeTheStart() {
        val streets = Game(GameId("s"), "ABC236", host, settings.copy(zoneShape = ZoneShape.STREETS), now).apply {
            addPlayer(host, "Host", now)
            addPlayer(anna, "Anna", now)
        }

        val error = assertFailsWith<GameException> { streets.start(host, setOf(anna), { SECRET }, now) }

        assertEquals(app.hovanki.shared.protocol.ErrorReason.ZONE_NOT_READY, error.reason)
        streets.onStreetZoneUnavailable()
        streets.start(host, setOf(anna), { SECRET }, now)
    }

    private fun report(player: PlayerId, point: GeoPoint) {
        game.recordLocations(player, listOf(LocationSample(point, 5.0, now)), now)
    }

    private companion object {
        const val SECRET = "00112233445566778899aabbccddeeff00112233"
    }
}
