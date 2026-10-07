package app.hovanki.client.ui.results

import app.hovanki.client.session.Award
import app.hovanki.client.session.AwardKind
import app.hovanki.client.session.HiderTally
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.rules.GameSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            settings = GameSetup().settings(GeoPoint(50.4501, 30.5234)),
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

    @Test
    fun theStoryKeepsOnlyTheViewersAwards() {
        val awards = listOf(
            Award(AwardKind.FIRST_CATCH, seeker, 60_000),
            Award(AwardKind.SURVIVOR, bob, 900_000),
        )
        val story = snapshot(seeker).story(awards)!!
        assertEquals(listOf(AwardKind.FIRST_CATCH), story.awards.map { it.kind })
        assertEquals(900_000L, story.roundMillis)
        assertEquals(HiderTally(caught = 2, survived = 1, eliminated = 0), story.tally)
        assertTrue(story.hidersWin)
    }

    @Test
    fun theSeekersWinWhenNobodySurvived() {
        val allFound = players.map { if (it.id == bob) it.copy(status = PlayerStatus.ELIMINATED) else it }
        assertFalse(snapshot(seeker, allFound).story(emptyList())!!.hidersWin)
    }

    @Test
    fun atMostThreeAwards() {
        val many = AwardKind.entries.map { Award(it, seeker, 1) }
        assertEquals(STORY_AWARDS, snapshot(seeker).story(many)!!.awards.size)
    }

    @Test
    fun noStoryForSomebodyOutOfTheList() {
        val snapshot = snapshot(anna).let { it.copy(players = it.players.filter { p -> p.id != anna }) }
        assertNull(snapshot.story(emptyList()))
    }
}
