package app.hovanki.e2e.scenarios

import app.hovanki.client.session.ConnectionStatus
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The account and the game are two sessions: what happens to the account during a game (a new password on another
 * phone, logging out, deleting it) leaves the game alone, and an account goes from one game to the next.
 */
class AccountInGameTest {
    /**
     * A new password on another phone ends the account's other sessions, the game phone's too. The game goes on there:
     * the game has its own token. The game phone learns about the account at its next account call.
     */
    @Test
    fun passwordChangedOnAnotherPhone() = scenario("Password changed on another phone mid-game") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -60.0))
        val account = anna.signsUp()

        sam.createsGame(GameSetups.fast())
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        val phone = anna.newPhone()
        phone.logsIn(account)
        val newPassword = "${account.password} and more"
        requireOk(phone.changePassword(account.password, newPassword), "Anna changes her password on another phone")
        requireOk(phone.refreshAccount(), "that phone stays logged in")

        val before = checkNotNull(anna.onServer().lastFixReceivedMillis)
        awaitThat("the game phone keeps playing") {
            checkNotNull(anna.onServer().lastFixReceivedMillis) > before &&
                anna.state.connectionStatus == ConnectionStatus.ONLINE
        }
        expectRejected(anna.refreshFriends(), ErrorCode.UNAUTHORIZED, "the friends list on the game phone")
        check(!anna.accountState.isLoggedIn && anna.accountState.sessionExpired, "the game phone is logged out")
        check(anna.state.session?.playerId == anna.id, "but still in the game")
        sam.claimsCatch(anna)
        sam.entersCodeShownBy(anna)
        awaitCatch(anna, CatchStatus.CONFIRMED)

        val third = anna.newPhone("Anna (third phone)")
        val oldPassword = third.logIn(account.nickname, account.password)
        expectRejected(oldPassword, ErrorReason.WRONG_CREDENTIALS, "the old password")
        requireOk(third.logIn(account.nickname, newPassword), "the new password works")
    }

    /** Logging out in the middle of a game leaves the game: the player plays on, only the account is gone. */
    @Test
    fun loggedOutMidGame() = scenario("Logged out mid-game") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -60.0))
        sam.signsUp()
        anna.signsUp()
        val annaUserId = checkNotNull(anna.userId)

        sam.createsGame(GameSetups.fast())
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        requireOk(anna.logOut(), "Anna logs out")
        check(!anna.accountState.isLoggedIn, "Anna's phone is logged out")
        holdsFor("Anna plays on", 3.seconds) {
            anna.state.session?.playerId == anna.id && anna.state.connectionStatus == ConnectionStatus.ONLINE
        }
        expectRejected(
            anna.sendFriendRequest(checkNotNull(sam.userId)),
            ErrorReason.ACCOUNT_REQUIRED,
            "a friend request from the game without an account",
        )
        check(state().players.single { it.id == anna.id }.userId == annaUserId, "the game still knows her account")
        sam.claimsCatch(anna)
        sam.entersCodeShownBy(anna)
        awaitCatch(anna, CatchStatus.CONFIRMED)
    }

    /**
     * Anna deletes her account in the middle of a game: she plays on to the end, nobody can befriend the deleted
     * account, and her chat message can still be reported.
     */
    @Test
    fun accountDeletedMidGame() = scenario("Account deleted mid-game") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -60.0))
        sam.signsUp()
        val account = anna.signsUp()
        val annaUserId = checkNotNull(anna.userId)

        sam.createsGame(GameSetups.fast())
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        requireOk(anna.sendChat("catch me if you can"), "Anna teases")
        val message = eventually("Sam reads it") { sam.chat.firstOrNull { it.text == "catch me if you can" } }

        requireOk(anna.deleteAccount(account.password), "Anna deletes her account")
        check(!anna.accountState.isLoggedIn, "Anna's phone is logged out")
        holdsFor("Anna plays on", 3.seconds) { anna.state.session?.playerId == anna.id }
        expectRejected(sam.sendFriendRequest(annaUserId), ErrorReason.USER_NOT_FOUND, "a friend request to her")
        requireOk(sam.reportChat(message.seq), "Sam reports her message")
        check(observer.reports().any { it.messageSeq == message.seq && it.gameId == gameId }, "the report is kept")

        sam.claimsCatch(anna)
        sam.entersCodeShownBy(anna)
        awaitCatch(anna, CatchStatus.CONFIRMED)
        sam.catches(boris)
        awaitPhase(GamePhase.FINISHED)
        awaitThat("Anna's phone shows the results") { anna.snapshot?.phase == GamePhase.FINISHED }
    }

    /** From the results of one game straight into the next, without closing them: the phone follows the new game. */
    @Test
    fun twoGamesInARow() = scenario("Two games in a row") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -20.0))
        anna.signsUp()

        sam.createsGame(GameSetups.fast())
        join(anna)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        sam.catches(anna)
        awaitPhase(GamePhase.FINISHED)
        val first = gameId
        awaitThat("Anna's phone shows the results") { anna.snapshot?.phase == GamePhase.FINISHED }

        boris.createsGame(GameSetups.fast())
        join(anna)
        check(gameId != first && anna.state.session?.gameId == gameId, "Anna's phone is in the new game")
        check(state().players.single { it.id == anna.id }.userId == anna.userId, "with her account")
        holdsFor("her phone shows only the new game", 3.seconds) { anna.snapshot?.gameId == gameId }
        check(anna.storage.read("session")?.contains(gameId.value) == true, "the new game is saved to come back to")

        boris.startsGame(seekers = listOf(boris))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        boris.catches(anna)
        awaitPhase(GamePhase.FINISHED)
    }
}
