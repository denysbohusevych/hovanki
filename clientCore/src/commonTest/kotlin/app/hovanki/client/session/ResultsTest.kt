package app.hovanki.client.session

import app.hovanki.client.network.testSnapshot
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameRecording
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PlayerCounts
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerTrack
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.RecordedPlayer
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.TrackPoint
import app.hovanki.shared.protocol.TracksResponse
import app.hovanki.shared.rules.shrinkingZone
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResultsTest {
    private val searchFrom = 1_000_000L
    private val sam = PlayerId("sam")
    private val yura = PlayerId("yura")
    private val anna = PlayerId("anna")
    private val boris = PlayerId("boris")
    private val vera = PlayerId("vera")

    private fun seeker(id: PlayerId) = PlayerView(id, id.value, Role.SEEKER, PlayerStatus.ACTIVE)

    private fun caught(id: PlayerId, by: PlayerId, afterSeconds: Int) = PlayerView(
        id,
        id.value,
        Role.HIDER,
        PlayerStatus.CAUGHT,
        outAtMillis = searchFrom + afterSeconds * 1000L,
        caughtBy = by,
    )

    private fun finished(vararg players: PlayerView, endSeconds: Int = 1_800) = testSnapshot(
        phase = GamePhase.FINISHED,
        players = players.toList(),
    ).copy(zoneStartedAtMillis = searchFrom, finishedAtMillis = searchFrom + endSeconds * 1000L)

    @Test
    fun everybodyFound() {
        val game = finished(
            seeker(sam),
            seeker(yura),
            caught(anna, by = sam, afterSeconds = 300),
            caught(boris, by = yura, afterSeconds = 120),
            caught(vera, by = sam, afterSeconds = 900),
            endSeconds = 900,
        )

        assertEquals(
            listOf(
                Award(AwardKind.FIRST_CATCH, yura, 120_000),
                Award(AwardKind.HUNTER, sam, 2),
                Award(AwardKind.LAST_STANDING, vera, 900_000),
            ),
            game.awards(),
        )
        assertEquals(2, game.catchesBy(sam))
        assertEquals(300_000, game.searchMillisAt(searchFrom + 300_000))
    }

    @Test
    fun survivorsAndTies() {
        val eliminated = PlayerView(boris, "Boris", Role.HIDER, PlayerStatus.ELIMINATED, outAtMillis = searchFrom + 5)
        val game = finished(
            seeker(sam),
            seeker(yura),
            caught(anna, by = sam, afterSeconds = 300),
            eliminated,
            PlayerView(vera, "Vera", Role.HIDER, PlayerStatus.ACTIVE),
        )

        assertEquals(
            listOf(Award(AwardKind.FIRST_CATCH, sam, 300_000), Award(AwardKind.SURVIVOR, vera, 1_800_000)),
            game.awards(),
            "one catch is no hunt; with a survivor there is no «last standing»",
        )
        assertEquals(emptyList(), game.copy(phase = GamePhase.SEEKING).awards(), "only when the game is over")
    }

    @Test
    fun aBigGameGivesOnlyTheSurvivorsBadge() {
        val game = finished(
            seeker(sam),
            caught(anna, by = sam, afterSeconds = 300),
            PlayerView(vera, "Vera", Role.HIDER, PlayerStatus.ACTIVE),
        ).copy(counts = PlayerCounts(players = 500, seekers = 20, hidersActive = 100, hidersCaught = 380))

        assertEquals(listOf(Award(AwardKind.SURVIVOR, vera, 1_800_000)), game.awards())
        assertEquals(HiderTally(caught = 380, survived = 100, eliminated = 0), game.hiderTally(), "the server's counts")
    }

    @Test
    fun theTallyOfAnOrdinaryGameIsItsList() {
        val game = finished(
            seeker(sam),
            caught(anna, by = sam, afterSeconds = 300),
            PlayerView(vera, "Vera", Role.HIDER, PlayerStatus.ACTIVE),
            PlayerView(boris, "Boris", Role.HIDER, PlayerStatus.ELIMINATED),
        )

        assertEquals(HiderTally(caught = 1, survived = 1, eliminated = 1), game.hiderTally())
    }

    private fun walk(player: PlayerId, meters: Int): PlayerTrack {
        val start = GeoPoint(50.45, 30.52)
        val points = (0..meters step 50).map { TrackPoint(start.lat, start.moveBy(it.toDouble(), 0.0).lon, it * 100L) }
        return PlayerTrack(player, points)
    }

    @Test
    fun theLongestWayWithTheTracks() {
        val game = finished(seeker(sam), PlayerView(vera, "Vera", Role.HIDER, PlayerStatus.ACTIVE))
        val tracks = TracksResponse(listOf(walk(sam, 1_000), walk(vera, 400)))

        val marathon = game.awards(tracks).single { it.kind == AwardKind.MARATHON }
        assertEquals(sam, marathon.playerId)
        assertTrue(abs(marathon.value - 1_000) <= 1, "${marathon.value} m")
        val stayedHome = TracksResponse(listOf(walk(sam, 150)))
        assertTrue(game.awards(stayedHome).none { it.kind == AwardKind.MARATHON }, "too short to count")
    }

    @Test
    fun theReplayRunsFromHidingToTheEnd() {
        val game = finished(seeker(sam), caught(anna, by = sam, afterSeconds = 60), endSeconds = 60)
            .let { it.copy(settings = it.settings.copy(hidingSeconds = 120)) }
        val a = GeoPoint(50.45, 30.52)
        val b = a.moveBy(100.0, 0.0)
        val annaTrack = PlayerTrack(
            anna,
            listOf(
                TrackPoint(a.lat, a.lon, searchFrom - 100_000),
                TrackPoint(b.lat, b.lon, searchFrom - 50_000),
            ),
        )
        val tracks = TracksResponse(listOf(annaTrack, PlayerTrack(sam, emptyList()), PlayerTrack(vera, listOf())))

        val replay = Replay.of(game, tracks)!!
        assertEquals(searchFrom - 120_000, replay.startMillis, "the start of hiding")
        assertEquals(searchFrom + 60_000, replay.endMillis)
        assertEquals(listOf(anna), replay.lines.map { it.player.id }, "only players with a track")

        val line = replay.lines.single()
        assertNull(line.positionAt(searchFrom - 110_000), "no fix yet")
        val halfway = line.positionAt(searchFrom - 75_000)!!
        assertTrue(abs(halfway.lon - (a.lon + b.lon) / 2) < 1e-9)
        assertEquals(listOf(a, halfway), line.pathUntil(searchFrom - 75_000))
        assertEquals(b, line.positionAt(searchFrom + 10_000), "stays where the track ends")
        assertEquals(listOf(a, b), line.pathUntil(replay.endMillis))
        assertNull(Replay.of(game.copy(phase = GamePhase.SEEKING), tracks))
        assertNull(Replay.of(game, null))
    }

    @Test
    fun aRecordingFromTheHistoryIsReplayedLikeTheResults() {
        val a = GeoPoint(50.45, 30.52)
        val b = a.moveBy(100.0, 0.0)
        val recording = GameRecording(
            gameId = GameId("g"),
            zone = shrinkingZone(a),
            startedAtMillis = searchFrom - 120_000,
            zoneStartedAtMillis = searchFrom,
            finishedAtMillis = searchFrom + 60_000,
            players = listOf(
                RecordedPlayer(
                    anna,
                    "Anna",
                    Role.HIDER,
                    PlayerStatus.CAUGHT,
                    outAtMillis = searchFrom + 30_000,
                    caughtBy = sam,
                    points = listOf(
                        TrackPoint(a.lat, a.lon, searchFrom - 100_000),
                        TrackPoint(b.lat, b.lon, searchFrom),
                    ),
                ),
                RecordedPlayer(sam, "Sam", Role.SEEKER, PlayerStatus.ACTIVE, isMe = true),
            ),
            expiresAtMillis = searchFrom + 90L * 24 * 3600 * 1000,
        )

        val replay = Replay.of(recording)!!
        assertEquals(searchFrom - 120_000, replay.startMillis)
        assertEquals(searchFrom + 60_000, replay.endMillis)
        val line = replay.lines.single()
        assertEquals("Anna", line.player.name)
        assertEquals(PlayerStatus.CAUGHT, line.player.status)
        assertEquals(sam, line.player.caughtBy)
        assertEquals(b, line.positionAt(replay.endMillis))
        assertNull(Replay.of(recording.copy(players = recording.players.map { it.copy(points = emptyList()) })))
    }
}
