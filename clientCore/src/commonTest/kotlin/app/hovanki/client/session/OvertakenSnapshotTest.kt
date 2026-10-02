package app.hovanki.client.session

import app.hovanki.client.network.testSnapshot
import app.hovanki.shared.protocol.GamePhase
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A slow poll overtaken by a command's answer never takes the phone back. */
class OvertakenSnapshotTest {
    @Test
    fun anEarlierAnswerIsOvertaken() {
        val shown = testSnapshot(serverTimeMillis = 2_000, phase = GamePhase.SEEKING)
        assertTrue(isOvertaken(testSnapshot(serverTimeMillis = 1_999, phase = GamePhase.SEEKING), shown))
        assertFalse(isOvertaken(testSnapshot(serverTimeMillis = 2_001, phase = GamePhase.SEEKING), shown))
    }

    @Test
    fun aLobbyPollInTheStartsMillisecondDoesNotUndoTheStart() {
        val started = testSnapshot(serverTimeMillis = 2_000, phase = GamePhase.SEEKING)
        assertTrue(isOvertaken(testSnapshot(serverTimeMillis = 2_000, phase = GamePhase.LOBBY), started))
        assertFalse(isOvertaken(testSnapshot(serverTimeMillis = 2_000, phase = GamePhase.SEEKING), started))
        assertFalse(isOvertaken(testSnapshot(serverTimeMillis = 2_000, phase = GamePhase.FINISHED), started))
    }

    @Test
    fun theFirstAnswerIsAlwaysTaken() {
        assertFalse(isOvertaken(testSnapshot(phase = GamePhase.LOBBY), null))
    }
}
