package app.hovanki.e2e.scenario

import app.hovanki.shared.debug.DebugCatch
import app.hovanki.shared.debug.DebugGameState
import app.hovanki.shared.debug.DebugPlayer
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameInvariantsTest {
    private val sam = PlayerId("sam")
    private val anna = PlayerId("anna")
    private val boris = PlayerId("boris")

    @Test
    fun aGameByTheRulesHasNoViolations() {
        val invariants = GameInvariants(graceMillis = 10_000)

        invariants.check(state(1_000, GamePhase.SEEKING))
        invariants.check(state(2_000, GamePhase.SEEKING, catches = listOf(claim(CatchStatus.AWAITING_CODE))))
        invariants.check(state(3_000, GamePhase.SEEKING, annaWarnedAt = 3_000))
        invariants.check(
            state(
                14_000,
                GamePhase.FINISHED,
                anna = PlayerStatus.ELIMINATED,
                boris = PlayerStatus.CAUGHT,
                catches = listOf(claim(CatchStatus.CONFIRMED)),
                finishedAt = 14_000,
            ),
        )

        assertEquals(emptyList(), invariants.violations)
        assertEquals(4, invariants.checks)
    }

    @Test
    fun everyBrokenRuleIsReported() {
        val invariants = GameInvariants(graceMillis = 10_000)

        invariants.check(state(1_000, GamePhase.SEEKING, annaWarnedAt = 1_000))
        invariants.check(
            state(
                5_000,
                GamePhase.SEEKING,
                anna = PlayerStatus.ELIMINATED,
                boris = PlayerStatus.CAUGHT,
                catches = listOf(claim(CatchStatus.AWAITING_CODE), claim(CatchStatus.DISPUTED, id = "c2")),
            ),
        )
        val stillDisputed = listOf(claim(CatchStatus.DISPUTED))
        invariants.check(state(6_000, GamePhase.FINISHED, finishedAt = 6_000, catches = stillDisputed))
        invariants.check(state(7_000, GamePhase.FINISHED, finishedAt = 6_500))
        invariants.check(state(8_000, GamePhase.SEEKING))

        val found = invariants.violations
        assertTrue(found.any { "Anna is eliminated 4000 ms after the warning" in it }, "$found")
        assertTrue(found.any { "Boris is caught without a confirmed claim" in it }, "$found")
        assertTrue(found.any { "Sam is in 2 open claims" in it }, "$found")
        assertTrue(found.any { "a finished game has open claims" in it }, "$found")
        assertTrue(found.any { "the end moved from 6000 to 6500" in it }, "$found")
        assertTrue(found.any { "phase went back from FINISHED to SEEKING" in it }, "$found")
    }

    @Test
    fun anEliminationWithoutAWarningIsReported() {
        val invariants = GameInvariants(graceMillis = 10_000)

        invariants.check(state(20_000, GamePhase.SEEKING, anna = PlayerStatus.ELIMINATED))

        assertEquals(listOf("at 20000: Anna is eliminated without a warning"), invariants.violations)
    }

    private fun claim(status: CatchStatus, id: String = "c1") = DebugCatch(
        id = CatchId(id),
        seekerId = sam,
        hiderId = boris,
        status = status,
        createdAtMillis = 0,
        deadlineMillis = 10_000,
        failedAttempts = 0,
    )

    private fun state(
        now: Long,
        phase: GamePhase,
        anna: PlayerStatus = PlayerStatus.ACTIVE,
        boris: PlayerStatus = PlayerStatus.ACTIVE,
        annaWarnedAt: Long? = null,
        catches: List<DebugCatch> = emptyList(),
        finishedAt: Long? = null,
    ) = DebugGameState(
        gameId = GameId("g"),
        joinCode = "ABC234",
        hostId = sam,
        phase = phase,
        settings = GameSetups.fast(),
        serverTimeMillis = now,
        phaseStartedAtMillis = 0,
        finishedAtMillis = finishedAt,
        players = listOf(
            DebugPlayer(sam, "Sam", Role.SEEKER, PlayerStatus.ACTIVE),
            DebugPlayer(this.anna, "Anna", Role.HIDER, anna, outOfZoneSinceMillis = annaWarnedAt),
            DebugPlayer(this.boris, "Boris", Role.HIDER, boris),
        ),
        catches = catches,
    )
}
