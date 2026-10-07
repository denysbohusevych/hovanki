package app.hovanki.client.ui.results

import app.hovanki.client.session.Award
import app.hovanki.client.session.AwardKind
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerTrack
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.TrackPoint
import app.hovanki.shared.protocol.TracksResponse
import app.hovanki.shared.rules.GameSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryCardTest {
    private val anna = PlayerId("anna")
    private val bob = PlayerId("bob")
    private val cleo = PlayerId("cleo")
    private val seeker = PlayerId("sam")
    private val searchFrom = 100_000L
    private val players = listOf(
        PlayerView(
            anna,
            "Anna",
            Role.HIDER,
            PlayerStatus.CAUGHT,
            outAtMillis = searchFrom + 300_000,
            caughtBy = seeker,
        ),
        PlayerView(bob, "Bob", Role.HIDER, PlayerStatus.ACTIVE),
        PlayerView(cleo, "Cleo", Role.HIDER, PlayerStatus.CAUGHT, outAtMillis = searchFrom + 60_000, caughtBy = seeker),
        PlayerView(seeker, "Sam", Role.SEEKER, PlayerStatus.ACTIVE),
    )

    private fun snapshot(me: PlayerId, players: List<PlayerView> = this.players): GameSnapshot {
        val mine = players.first { it.id == me }
        return GameSnapshot(
            gameId = GameId("g1"),
            joinCode = "ABCD",
            hostId = seeker,
            phase = GamePhase.FINISHED,
            settings = GameSetup().settings(center),
            serverTimeMillis = searchFrom + 900_000,
            players = players,
            me = MyState(me, mine.role, mine.status),
            zoneStartedAtMillis = searchFrom,
            finishedAtMillis = searchFrom + 900_000,
        )
    }

    @Test
    fun aHiderLastsUntilTheyWereFoundAndIsPlacedByIt() {
        val result = snapshot(anna).myResult()!!
        assertEquals(300_000L, result.lastedMillis)
        assertEquals(2, result.place)
        assertEquals(3, result.hiders)

        val survivor = snapshot(bob).myResult()!!
        assertEquals(900_000L, survivor.lastedMillis)
        assertEquals(1, survivor.place)
    }

    @Test
    fun aSeekerCountsFinds() {
        val result = snapshot(seeker).myResult()!!
        assertEquals(2, result.finds)
        assertNull(result.lastedMillis)
    }

    private val center = GeoPoint(50.4501, 30.5234)

    private fun track(player: PlayerId, vararg eastNorth: Pair<Double, Double>, from: Long = searchFrom) = PlayerTrack(
        player,
        eastNorth.mapIndexed { i, (east, north) ->
            val point = center.moveBy(east, north)
            TrackPoint(point.lat, point.lon, from + i * 60_000L)
        },
    )

    private val tracks = TracksResponse(
        listOf(
            track(anna, 0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0, 200.0 to 100.0, 200.0 to 200.0, 300.0 to 200.0),
            track(bob, -100.0 to 0.0, -100.0 to -100.0),
            track(
                seeker,
                0.0 to -300.0,
                100.0 to -200.0,
                200.0 to -100.0,
                300.0 to 0.0,
                300.0 to 100.0,
                300.0 to 200.0,
                300.0 to 300.0,
            ),
        ),
    )

    @Test
    fun theStoryKeepsTheViewersFirstAwardAndWhoFoundThem() {
        val awards = listOf(
            Award(AwardKind.SURVIVOR, bob, 900_000),
            Award(AwardKind.MARATHON, anna, 500),
            Award(AwardKind.FIRST_CATCH, anna, 1),
        )
        val story = snapshot(anna).story(awards, tracks)!!
        assertEquals(AwardKind.MARATHON, story.award?.kind)
        assertEquals("Sam", story.foundBy)
        assertEquals(4, story.players)
        assertEquals(900_000L, story.roundMillis)
        assertTrue(story.distanceMeters!! > 499.0)
        assertNull(snapshot(bob).story(awards, tracks)!!.foundBy)
    }

    @Test
    fun theSchemeIsRelativeToTheZoneAndKeepsTheWholeWayInside() {
        val zone = snapshot(anna).settings.zone.initial.radiusMeters
        val scheme = snapshot(anna).scheme(tracks)!!
        val mine = scheme.paths.single { it.kind == SchemeLine.MINE }
        // The zone's center is the scheme's; north is up.
        assertEquals(0f, scheme.start.x, 1e-3f)
        assertEquals(0f, scheme.start.y, 1e-3f)
        val last = mine.points.last()
        assertTrue(last.x > 0 && last.y < 0)
        val farthest = mine.points.maxOf { kotlin.math.hypot(it.x, it.y) }
        assertTrue(farthest <= 1.0001f)
        assertEquals(minOf(1.0, zone / kotlin.math.hypot(300.0, 200.0)).toFloat(), scheme.zoneRadius, 1e-3f)
    }

    @Test
    fun theSeekerWhoFoundTheHiderStopsWhenTheyWereFoundAndTheCrossIsWhereTheWayEnds() {
        val scheme = snapshot(anna).scheme(tracks)!!
        val found = scheme.paths.single { it.kind == SchemeLine.FOUND_ME }
        // Anna was out 300 s into the search: six points of the seeker's minutes.
        assertEquals(6, found.points.size)
        assertEquals(scheme.paths.single { it.kind == SchemeLine.MINE }.points.last(), scheme.marks.single())
        assertEquals(listOf(SchemeLine.HIDER, SchemeLine.FOUND_ME, SchemeLine.MINE), scheme.paths.map { it.kind })
    }

    @Test
    fun aSeekerSeesCrossesWhereTheyFoundSomebody() {
        val scheme = snapshot(seeker).scheme(tracks)!!
        // Anna's way ends where she was found; Cleo has no way.
        assertEquals(1, scheme.marks.size)
        assertTrue(scheme.paths.none { it.kind == SchemeLine.FOUND_ME })
    }

    @Test
    fun noSchemeWithoutTheViewersWay() {
        assertNull(snapshot(cleo).scheme(tracks))
        assertNull(snapshot(anna).scheme(null))
        assertNull(snapshot(cleo).story(emptyList(), tracks)!!.scheme)
    }

    @Test
    fun aLongWayIsThinned() {
        val long = TracksResponse(listOf(track(anna, *Array(1_000) { it.toDouble() to 0.0 })))
        val mine = snapshot(anna).scheme(long)!!.paths.single()
        assertEquals(SCHEME_POINTS, mine.points.size)
    }

    @Test
    fun noStoryForSomebodyOutOfTheList() {
        val snapshot = snapshot(anna).let { it.copy(players = it.players.filter { p -> p.id != anna }) }
        assertNull(snapshot.story(emptyList()))
    }
}
