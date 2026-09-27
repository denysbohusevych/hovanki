package app.hovanki.client.tracking

import app.hovanki.client.network.testPlayer
import app.hovanki.client.network.testSession
import app.hovanki.client.network.testSnapshot
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.CatchView
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HiderAlertsTest {
    private val seeker = PlayerView(PlayerId("seeker1"), "Boris", Role.SEEKER, PlayerStatus.ACTIVE)

    private fun me(status: PlayerStatus = PlayerStatus.ACTIVE, outOfZone: Long? = null, inBuilding: Long? = null) =
        MyState(
            testSession.playerId,
            Role.HIDER,
            status,
            outOfZoneDeadlineMillis = outOfZone,
            insideBuildingRevealAtMillis = inBuilding,
        )

    private fun claim(status: CatchStatus) = CatchView(
        id = CatchId("catch1"),
        seekerId = seeker.id,
        hiderId = testSession.playerId,
        status = status,
        createdAtMillis = 1_000L,
        deadlineMillis = 61_000L,
    )

    @Test
    fun aHiderInTroubleHasAlerts() {
        val snapshot = testSnapshot(phase = GamePhase.SEEKING, players = listOf(testPlayer, seeker)).copy(
            me = me(outOfZone = 40_000L, inBuilding = 50_000L),
            catches = listOf(claim(CatchStatus.AWAITING_CODE)),
        )

        assertEquals(
            listOf(
                HiderAlert(AlertKind.OUT_OF_ZONE, 40_000L),
                HiderAlert(AlertKind.IN_BUILDING, 50_000L),
                HiderAlert(AlertKind.CATCH_CLAIM, 61_000L, seekerName = "Boris"),
            ),
            snapshot.hiderAlerts(),
        )
    }

    @Test
    fun noAlertsOutsideTheRoundOrOnceCaught() {
        val trouble = me(outOfZone = 40_000L)

        assertTrue(testSnapshot(phase = GamePhase.LOBBY).copy(me = trouble).hiderAlerts().isEmpty())
        assertTrue(testSnapshot(phase = GamePhase.FINISHED).copy(me = trouble).hiderAlerts().isEmpty())
        val caught = me(status = PlayerStatus.CAUGHT, outOfZone = 40_000L)
        assertTrue(testSnapshot(phase = GamePhase.SEEKING).copy(me = caught).hiderAlerts().isEmpty())
    }

    @Test
    fun aDisputedClaimIsNoAlert() {
        val snapshot = testSnapshot(phase = GamePhase.SEEKING, players = listOf(testPlayer, seeker)).copy(
            catches = listOf(claim(CatchStatus.DISPUTED)),
        )

        assertTrue(snapshot.hiderAlerts().isEmpty())
    }

    @Test
    fun outOfTheZoneVibratesAtOnceThenEveryTenSeconds() {
        val repeats = AlertRepeats()
        val out = listOf(HiderAlert(AlertKind.OUT_OF_ZONE, 60_000L))

        assertEquals(out, repeats.update(out, 1_000L).buzz, "right away")
        assertTrue(repeats.update(out, 4_000L).buzz.isEmpty())
        assertTrue(repeats.update(out, 10_999L).buzz.isEmpty())
        assertEquals(out, repeats.update(out, 11_000L).buzz, "10 s later")
        assertTrue(repeats.update(out, 14_000L).buzz.isEmpty())
        assertEquals(out, repeats.update(out, 21_500L).buzz)
    }

    @Test
    fun insideABuildingVibratesEveryFifteenSeconds() {
        val repeats = AlertRepeats()
        val inside = listOf(HiderAlert(AlertKind.IN_BUILDING, 60_000L))

        assertEquals(inside, repeats.update(inside, 0L).buzz)
        assertTrue(repeats.update(inside, 10_000L).buzz.isEmpty())
        assertEquals(inside, repeats.update(inside, 15_000L).buzz)
    }

    @Test
    fun aClaimVibratesTwiceAtMost() {
        val repeats = AlertRepeats()
        val claim = listOf(HiderAlert(AlertKind.CATCH_CLAIM, 61_000L, "Boris"))

        assertEquals(claim, repeats.update(claim, 1_000L).buzz)
        assertEquals(claim, repeats.update(claim, 11_000L).buzz, "once more if still not shown")
        assertTrue(repeats.update(claim, 21_000L).buzz.isEmpty())
        assertTrue(repeats.update(claim, 41_000L).buzz.isEmpty())
    }

    @Test
    fun anAlertThatIsOverEndsAndStartsAfreshNextTime() {
        val repeats = AlertRepeats()
        val out = listOf(HiderAlert(AlertKind.OUT_OF_ZONE, 60_000L))
        val inside = listOf(HiderAlert(AlertKind.IN_BUILDING, 70_000L))
        repeats.update(out, 1_000L)

        val back = repeats.update(inside, 3_000L)
        assertEquals(setOf(AlertKind.OUT_OF_ZONE), back.ended)
        assertEquals(inside, back.buzz)

        assertEquals(out, repeats.update(out + inside, 5_000L).buzz, "out again: at once; inside not due yet")
        assertEquals(setOf(AlertKind.OUT_OF_ZONE, AlertKind.IN_BUILDING), repeats.clear())
        assertTrue(repeats.update(emptyList(), 6_000L).ended.isEmpty(), "nothing left after clear()")
    }
}
