package app.hovanki.e2e.scenarios

import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.VisibilityReason
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Scenario 10. Every response of every bot is audited anyway (SnapshotAudit); this game provokes every reveal
 * and then checks that seekers saw exactly those, and hiders nothing at all.
 */
class PrivacyTest {
    @Test
    fun onlyRevealedHidersAreEverVisible() = scenario("Privacy through a whole game") {
        val sam = player("Sam", at = PARK)
        val tom = player("Tom", at = PARK.offset(eastMeters = 10.0))
        val dana = player("Dana", at = PARK)
        val mia = player("Mia", at = PARK)
        val olga = player("Olga", at = PARK)
        val pete = player("Pete", at = PARK)
        val hiders = listOf(dana, mia, olga, pete)

        sam.createsGame(GameSetups.fixedZone(150.0))
        join(tom, dana, mia, olga, pete)
        sam.startsGame(seekers = listOf(sam, tom))
        awaitReveal(tom, VisibilityReason.TEAMMATE, to = sam, within = 5.seconds)
        awaitReveal(sam, VisibilityReason.TEAMMATE, to = tom, within = 5.seconds)
        dana.walksTo(PARK.offset(northMeters = 60.0), speed = 4.0)
        mia.walksTo(PARK.offset(northMeters = -60.0), speed = 4.0)
        olga.walksTo(PARK.offset(eastMeters = -60.0), speed = 4.0)
        pete.walksTo(PARK.offset(eastMeters = 60.0), speed = 4.0)
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        pete.arrives()

        mia.startsMockingLocation()
        olga.turnsGpsOff()
        pete.walksTo(PARK.offset(eastMeters = 220.0), speed = 4.0)
        awaitReveal(mia, VisibilityReason.MOCK_LOCATION, to = sam, within = 10.seconds)
        awaitReveal(olga, VisibilityReason.STALE_SIGNAL, to = tom, within = 25.seconds)
        awaitReveal(pete, VisibilityReason.OUT_OF_ZONE, to = sam, within = 40.seconds)
        awaitStatus(pete, PlayerStatus.ELIMINATED, within = 20.seconds)
        mia.stopsMockingLocation()
        olga.turnsGpsOn()

        sam.catches(dana)
        tom.catches(mia)
        sam.catches(olga)
        awaitPhase(GamePhase.FINISHED)
        awaitThat("every phone got the results") { players.all { it.snapshot?.phase == GamePhase.FINISHED } }

        check(hiders.all { it.revealsSeen.isEmpty() }, "hiders never received a position")
        val seen = sam.revealsSeen + tom.revealsSeen
        val expected = setOf(
            sam.id to VisibilityReason.TEAMMATE,
            tom.id to VisibilityReason.TEAMMATE,
            mia.id to VisibilityReason.MOCK_LOCATION,
            olga.id to VisibilityReason.STALE_SIGNAL,
            pete.id to VisibilityReason.OUT_OF_ZONE,
        )
        check(seen == expected, "seekers saw exactly the revealed players: $seen")
    }
}
