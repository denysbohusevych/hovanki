package app.hovanki.e2e.scenarios

import app.hovanki.client.session.SessionError
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A logged-in player comes back into a running game on another phone (a reinstalled app, a second phone): joining
 * with the code while logged in gives back the same player, and the old phone's session ends. Guests can't.
 */
class AccountRejoinTest {
    @Test
    fun newPhoneMidRoundIsTheSamePlayer() = scenario("A new phone in the middle of a round") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(northMeters = 30.0))
        val boris = player("Boris", at = PARK.offset(northMeters = -40.0))
        sam.signsUp()
        val account = anna.signsUp()

        sam.createsGame(GameSetups.fast())
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        requireOk(anna.sendChat("hiding by the fountain", team = true), "Anna tells the hiders")
        val annaId = anna.id

        val phone = anna.newPhone()
        phone.logsIn(account)
        join(phone)
        check(phone.id == annaId, "the new phone plays Anna's player")
        val me = checkNotNull(phone.snapshot).me
        check(me.role == Role.HIDER && me.status == PlayerStatus.ACTIVE, "still an active hider")
        check(me.catchCodeSecret != null, "the new phone can show the catch code")
        awaitThat("the new phone got the chat so far", 10.seconds) {
            phone.chat.any { it.text == "hiding by the fountain" && it.isMine }
        }
        awaitThat("the new phone tracks in the background", 10.seconds) { phone.backgroundTracker.isRunning }

        awaitThat("the old phone's session ends on its next poll", 10.seconds) {
            anna.state.session == null && anna.state.lastError == SessionError.SessionLost
        }
        check(!anna.backgroundTracker.isRunning, "the old phone stops tracking")
        check(anna.storage.read("session") == null, "the old phone forgets the game")
        check(anna.accountState.isVerified, "the old phone stays logged in")
        check(state().players.size == 3, "no new player in the game")

        phone.walksToAndArrives(PARK.offset(northMeters = 15.0, eastMeters = 25.0), speed = 4.0)
        eventually("the server follows the new phone", within = 10.seconds) {
            phone.onServer().latestFix?.takeIf { it.point.distanceTo(phone.gps.truePosition) < 15.0 }
        }
        check(state().phase == GamePhase.SEEKING, "the round goes on")
        check(boris.onServer().status == PlayerStatus.ACTIVE, "for everybody")
        sam.catches(phone)
        awaitStatus(anna, PlayerStatus.CAUGHT)
    }

    @Test
    fun aGuestCannotComeBackOnAnotherPhone() = scenario("A guest can't take over a player") {
        val sam = player("Sam", at = PARK)
        val boris = player("Boris", at = PARK.offset(eastMeters = 30.0))

        sam.createsGame(GameSetups.fast())
        join(boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.HIDING)

        val phone = boris.newPhone()
        expectRejected(phone.join(joinCode), ErrorCode.WRONG_STATE, "joining the running game again as a guest")
        check(phone.state.session == null, "the new phone stays on the start screen")
        check(state().players.size == 2, "no new player")
        holdsFor("the old phone plays on", 3.seconds) {
            boris.state.session != null && boris.state.lastError == null
        }
    }
}
