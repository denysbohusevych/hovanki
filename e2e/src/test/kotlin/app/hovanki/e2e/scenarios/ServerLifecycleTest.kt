package app.hovanki.e2e.scenarios

import app.hovanki.client.session.SessionError
import app.hovanki.e2e.OWN_SERVER
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.e2e.scenarioOnOwnServer
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import org.junit.jupiter.api.parallel.ResourceLock
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * What happens to the phones when the server loses a game: it restarts (games live in memory) or deletes a finished
 * or abandoned one. Each scenario on a server of its own ([scenarioOnOwnServer]): a restart and games deleted within
 * seconds would disturb the other scenarios.
 */
@ResourceLock(OWN_SERVER)
class ServerLifecycleTest {
    /**
     * The server restarts in the middle of a game: the running apps learn their game is gone and go back to the start
     * screen, an app killed before learns it when relaunched. Accounts live in the database and survive.
     */
    @Test
    fun serverRestartMidGame() = scenarioOnOwnServer("Server restart mid-game", properties = emptyMap()) { server ->
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 30.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -30.0))
        sam.signsUp()

        sam.createsGame(GameSetups.fast())
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        boris.killApp()

        server.restart()
        note("the server restarted")
        awaitThat("the running apps learn the game is gone", 30.seconds) {
            listOf(sam, anna).all { it.state.session == null && it.state.lastError == SessionError.SessionLost }
        }
        check(listOf(sam, anna).none { it.storage.read("session") != null }, "and forget it")
        boris.launchApp()
        awaitThat("the app killed before goes back to the start screen", 20.seconds) {
            boris.state.session == null && boris.state.lastError == SessionError.SavedGameGone
        }

        check(sam.accountState.isLoggedIn, "Sam's phone is still logged in")
        requireOk(sam.refreshAccount(), "Sam's account works: it lives in the database")
        sam.createsGame(GameSetups.fast())
        join(anna)
        check(state().players.size == 2, "a new game after the restart")
    }

    /**
     * A finished game is deleted after `finished-retention` (here 5 s) while a phone still shows its results: the
     * results stay without an error until the player closes them. A phone killed on the results screen comes back
     * to the start screen: the finished game was not kept to resume.
     */
    @Test
    fun aFinishedGameIsDeleted() = scenarioOnOwnServer(
        "A finished game is deleted",
        properties = mapOf("hovanki.games.finished-retention" to "5s", "hovanki.games.cleanup-interval" to "1s"),
    ) {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))

        sam.createsGame(GameSetups.fast())
        join(anna)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        sam.catches(anna)
        awaitPhase(GamePhase.FINISHED)
        requireOk(anna.sendChat("gg"), "Anna writes on the results screen")
        awaitThat("Sam reads it") { sam.chat.any { it.text == "gg" } }
        anna.killApp()

        eventually("the server deletes the finished game", within = 20.seconds) {
            observer.games().games.none { it.gameId == gameId }.takeIf { it }
        }
        holdsFor("Sam's results stay, without an error", 5.seconds) {
            sam.state.session != null && sam.snapshot?.phase == GamePhase.FINISHED && sam.state.lastError == null
        }
        requireOk(sam.leave(), "Sam closes the results")
        check(sam.state.session == null, "Sam is on the start screen")

        anna.launchApp()
        awaitThat("Anna's relaunched app is on the start screen, nothing to resume", 10.seconds) {
            anna.state.session == null && !anna.state.isResuming
        }
        check(anna.state.lastError == null, "and no error: the game was over before")
    }

    /**
     * A lobby nobody polls any more is deleted after `idle-retention` (here 10 s): its code no longer works, the
     * invitation into it is gone, and a phone that had it saved hears it is gone. A lobby still in use stays.
     */
    @Test
    fun anAbandonedLobbyIsDeleted() = scenarioOnOwnServer(
        "An abandoned lobby is deleted",
        properties = mapOf("hovanki.games.idle-retention" to "10s", "hovanki.games.cleanup-interval" to "1s"),
    ) {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK)
        val boris = player("Boris", at = PARK)
        val carl = player("Carl", at = PARK)
        val dave = player("Dave", at = PARK)
        sam.signsUp()
        anna.signsUp()
        sam.befriends(anna)

        requireOk(carl.createGame(GameSetups.fast()), "Carl opens a lobby and stays in it")
        val kept = checkNotNull(carl.snapshot).gameId
        sam.createsGame(GameSetups.fast())
        join(boris)
        requireOk(sam.invite(listOf(checkNotNull(anna.userId))), "Sam invites Anna")
        requireOk(anna.refreshInbox(), "Anna's inbox")
        check(anna.inbox.invites.any { it.gameId == gameId }, "Anna has the invite")

        sam.killApp()
        boris.killApp()
        eventually("the server deletes the abandoned lobby", within = 30.seconds) {
            observer.games().games.none { it.gameId == gameId }.takeIf { it }
        }
        check(observer.games().games.any { it.gameId == kept }, "Carl's lobby stays: his app keeps polling")
        expectRejected(dave.join(joinCode), ErrorCode.NOT_FOUND, "the deleted lobby's code")
        requireOk(anna.refreshInbox(), "Anna's inbox")
        check(anna.inbox.invites.none { it.gameId == gameId }, "the invite is gone")

        boris.launchApp()
        awaitThat("Boris's relaunched app hears the game is gone", 20.seconds) {
            boris.state.session == null && boris.state.lastError == SessionError.SavedGameGone
        }
    }
}
