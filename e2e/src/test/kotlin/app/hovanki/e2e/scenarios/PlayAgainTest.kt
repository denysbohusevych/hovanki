package app.hovanki.e2e.scenarios

import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * «Play again» on the results: the host opens the next lobby with the setup of the game just played, the others see
 * it on their results and come in with one tap, under the names and accounts they played with, and the next round
 * starts as usual. The finished game stays as it was.
 */
class PlayAgainTest {
    @Test
    fun theSamePlayersPlayAgain() = scenario("Play again") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -20.0))
        anna.signsUp()
        val setup = GameSetups.fast()

        sam.createsGame(setup)
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        sam.catches(anna)
        sam.catches(boris)
        awaitPhase(GamePhase.FINISHED)
        val first = gameId

        check(boris.snapshot?.playAgain == null, "nothing to come into before the host opens it")
        expectRejected(boris.playsAgain(), ErrorCode.WRONG_STATE, "Boris plays again before the host")
        requireOk(sam.playsAgain(), "Sam plays again")
        val next = checkNotNull(sam.snapshot)
        check(next.gameId != first && next.phase == GamePhase.LOBBY, "a new lobby")
        check(next.hostId == sam.id, "Sam hosts it")
        check(next.settings == setup.copy(openBuildings = emptyList()), "with the setup just played")
        useGame(next.gameId, next.joinCode)

        awaitThat("Anna's and Boris's results offer the next game", within = 15.seconds) {
            listOf(anna, boris).all { it.snapshot?.playAgain?.joinCode == next.joinCode }
        }
        requireOk(anna.playsAgain(), "Anna plays again")
        requireOk(boris.playsAgain(), "Boris plays again")
        awaitThat("everybody is in the next lobby") {
            players.all { phone ->
                val lobby = phone.snapshot ?: return@all false
                lobby.gameId == next.gameId && lobby.phase == GamePhase.LOBBY && lobby.players.size == 3
            }
        }
        val names = checkNotNull(sam.snapshot).players.associate { it.id to it.name }
        check(names[boris.id] == "Boris", "a guest keeps the name they played with")
        check(
            checkNotNull(sam.snapshot).players.single {
                it.id == anna.id
            }.userId == anna.userId,
            "an account its own",
        )

        sam.startsGame(seekers = listOf(boris))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        check(observer.game(first).phase == GamePhase.FINISHED, "the first game stays finished")
    }
}
