package app.hovanki.e2e.scenarios

import app.hovanki.client.session.AwardKind
import app.hovanki.client.session.awards
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerStatus
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class ReplayTest {
    /**
     * The results screen: when each hider was out and who found them, and the replay of everybody's way. The tracks
     * are secret while the round runs, even for a modified app; afterwards every phone gets the same ones, from the
     * start of hiding to the end, a hider's until they were out.
     */
    @Test
    fun resultsAndReplay() = scenario("Results and replay") {
        val sam = player("Sam", at = PARK)
        val yura = player("Yura", at = PARK)
        val anna = player("Anna", at = PARK)
        val boris = player("Boris", at = PARK)
        val vera = player("Vera", at = PARK)

        sam.createsGame(GameSetups.fast())
        join(yura, anna, boris, vera)
        sam.startsGame(seekers = listOf(sam, yura))
        val hiding = awaitPhase(GamePhase.HIDING)
        anna.walksTo(PARK.offset(eastMeters = 40.0), speed = 3.0)
        boris.walksTo(PARK.offset(eastMeters = -40.0), speed = 3.0)
        vera.walksTo(PARK.offset(northMeters = 30.0), speed = 3.0)
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        expectRejected(sam.requestsTracks(), ErrorCode.WRONG_STATE, "the tracks while the round runs")
        check(sam.tracks == null, "the app does not ask for them before the end")

        anna.arrives()
        sam.catchesUpWith(anna)
        anna.opensMyCode()
        requireOk(sam.scansCodeOf(anna), "Sam scans Anna's code")
        val annaOut = awaitStatus(anna, PlayerStatus.CAUGHT)

        boris.arrives()
        yura.catches(boris)
        val borisOut = awaitStatus(boris, PlayerStatus.CAUGHT)

        // Vera runs out of the zone and doesn't come back.
        vera.walksTo(PARK.offset(northMeters = 450.0), speed = 6.0)
        val veraOut = awaitStatus(vera, PlayerStatus.ELIMINATED, within = 90.seconds)
        val end = awaitPhase(GamePhase.FINISHED)
        check(end.finishedAtMillis == veraOut.outAtMillis, "the round ended when Vera was out")
        check(annaOut.caughtBy == sam.id && borisOut.caughtBy == yura.id && veraOut.caughtBy == null, "who found whom")

        awaitThat("every phone shows when each hider was out") {
            players.all { phone ->
                val results = phone.snapshot ?: return@all false
                results.finishedAtMillis == end.finishedAtMillis &&
                    results.players.single { it.id == anna.id }.outAtMillis == annaOut.outAtMillis &&
                    results.players.single { it.id == vera.id }.outAtMillis == veraOut.outAtMillis
            }
        }
        awaitThat("every phone loaded the replay") { players.all { it.tracks != null } }
        val tracks = checkNotNull(sam.tracks)
        check(players.all { it.tracks == tracks }, "everybody sees the same replay")
        check(tracks.tracks.map { it.playerId }.toSet() == players.map { it.id }.toSet(), "a track per player")
        val roundStart = hiding.phaseStartedAtMillis
        for (player in players) {
            val points = tracks.tracks.single { it.playerId == player.id }.points
            check(points.isNotEmpty(), "${player.name} has a way")
            check(points.first().atMillis >= roundStart, "${player.name}'s way starts with the round, not in the lobby")
            check(points.zipWithNext().all { (a, b) -> b.atMillis > a.atMillis }, "${player.name}'s way in order")
            val out = end.players.single { it.id == player.id }.outAtMillis ?: checkNotNull(end.finishedAtMillis)
            check(points.last().atMillis < out, "${player.name}'s way ends when they were out")
        }
        check(state().players.all { it.replayPoints > 0 }, "the server kept them all")
        requireOk(sam.requestsTracks(), "after the round, asking is fine")

        val awards = checkNotNull(sam.snapshot).awards(tracks)
        check(awards.single { it.kind == AwardKind.FIRST_CATCH }.playerId == sam.id, "Sam found somebody first")
        check(awards.none { it.kind == AwardKind.HUNTER }, "nobody found two")
        check(awards.single { it.kind == AwardKind.LAST_STANDING }.playerId == vera.id, "Vera was out last")
        check(lastClaimOn(anna)?.status == CatchStatus.CONFIRMED, "Anna's catch stands")
    }
}
