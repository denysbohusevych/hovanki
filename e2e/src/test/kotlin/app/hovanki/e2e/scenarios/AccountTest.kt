package app.hovanki.e2e.scenarios

import app.hovanki.e2e.bot.CommandResult
import app.hovanki.e2e.observer.EmailPurpose
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Accounts (docs/adr/0004-accounts-friends-chat.md) through the app's AccountManager: sign-up with the emailed code,
 * login by nickname or email on other phones, logout, password reset, deletion. The codes come from the emails the
 * server sent (observer), as a person reads them in their inbox.
 */
class AccountTest {
    @Test
    fun signUpWithAnEmailedCode() = scenario("Sign up with an emailed code") {
        val anna = player("Anna", at = PARK)
        val account = newAccount("Anna")

        requireOk(anna.register(account, language = "uk-UA"), "Anna registers")
        check(anna.accountState.needsEmailVerification, "Anna is logged in, her email not confirmed yet")
        check(anna.user?.nickname == account.nickname && anna.user?.email == account.email, "her nickname and email")
        val email = observer.awaitEmail(account.email, EmailPurpose.VERIFY_EMAIL)
        check(email.language == "uk", "the email is in the app's language (${email.language})")
        val firstCode = checkNotNull(email.code)

        val bob = player("Bob", at = PARK)
        expectRejected(
            bob.register(newAccount("Bob").copy(nickname = account.nickname.uppercase())),
            ErrorReason.NICKNAME_TAKEN,
            "Anna's nickname in capitals is taken",
        )
        expectRejected(
            bob.register(newAccount("Bob").copy(email = account.email.uppercase())),
            ErrorReason.EMAIL_TAKEN,
            "Anna's email in capitals is taken",
        )
        check(!bob.accountState.isLoggedIn, "Bob stays logged out")

        val wrong = anna.verifyEmail(wrongCode(firstCode))
        expectRejected(wrong, ErrorCode.INVALID_CODE, "a mistyped code")
        check((wrong as CommandResult.Rejected).reason == null, "wrong, not expired")
        check(anna.accountState.needsEmailVerification, "the email is still not confirmed")

        val inbox = observer.emails(account.email)
        requireOk(anna.resendCode(), "Anna asks for the code again")
        val secondCode = emailedCode(account.email, EmailPurpose.VERIFY_EMAIL, known = inbox)
        if (secondCode != firstCode) {
            expectRejected(anna.verifyEmail(firstCode), ErrorCode.INVALID_CODE, "the new code replaced the first one")
        }
        requireOk(anna.verifyEmail(secondCode), "Anna enters the new code")
        check(anna.accountState.isVerified, "Anna's email is confirmed")
        requireOk(anna.refreshAccount(), "Anna's app reloads the profile")
        check(anna.user?.emailVerified == true, "the server has it confirmed too")
    }

    @Test
    fun logInByNicknameAndByEmail() = scenario("Log in by nickname and by email") {
        val anna = player("Anna", at = PARK)
        val account = anna.signsUp()
        val tablet = anna.newPhone("Anna's tablet")
        val laptop = anna.newPhone("Anna's laptop")

        expectRejected(
            tablet.logIn(account.nickname, "not-her-password"),
            ErrorReason.WRONG_CREDENTIALS,
            "a wrong password",
        )
        expectRejected(
            tablet.logIn(newAccount("Nobody").nickname, account.password),
            ErrorReason.WRONG_CREDENTIALS,
            "an unknown nickname: the same answer",
        )
        check(!tablet.accountState.isLoggedIn, "the tablet is still logged out")

        tablet.logsIn(account, login = account.nickname.lowercase())
        check(tablet.userId == anna.userId && tablet.accountState.isVerified, "by nickname: Anna's confirmed account")
        laptop.logsIn(account, login = account.email.uppercase())
        check(laptop.userId == anna.userId && laptop.accountState.isVerified, "by email: Anna's confirmed account")

        laptop.killApp()
        laptop.launchApp()
        awaitThat("the relaunched app is logged in again", 10.seconds) {
            laptop.accountState.isVerified && laptop.userId == anna.userId
        }

        requireOk(tablet.logOut(), "Anna logs out on the tablet")
        check(!tablet.accountState.isLoggedIn, "the tablet is logged out")
        check(tablet.storage.read("account") == null, "no account saved on the tablet")
        requireOk(anna.refreshAccount(), "Anna's phone still works")
        requireOk(laptop.refreshAccount(), "the laptop still works")
        check(anna.accountState.isVerified && laptop.accountState.isVerified, "both are still logged in")
    }

    @Test
    fun passwordResetLogsTheOtherPhonesOut() = scenario("Password reset") {
        val anna = player("Anna", at = PARK)
        val account = anna.signsUp()
        val tablet = anna.newPhone("Anna's tablet")
        tablet.logsIn(account)
        val newPhone = anna.newPhone()

        val unknown = newAccount("Nobody").email
        requireOk(newPhone.requestPasswordReset(unknown), "a reset for an address without an account looks the same")

        val inbox = observer.emails(account.email)
        requireOk(newPhone.requestPasswordReset(account.email), "Anna forgot her password")
        val code = emailedCode(account.email, EmailPurpose.RESET_PASSWORD, known = inbox)
        check(observer.emails(unknown).isEmpty(), "nothing was sent to the unknown address")
        val newPassword = "a-new-password-7"
        expectRejected(
            newPhone.resetPassword(account.email, wrongCode(code), newPassword),
            ErrorCode.INVALID_CODE,
            "a mistyped code",
        )
        check(!newPhone.accountState.isLoggedIn, "not logged in with a wrong code")
        requireOk(newPhone.resetPassword(account.email, code, newPassword), "Anna sets a new password")
        check(newPhone.userId == anna.userId && newPhone.accountState.isVerified, "logged in with the new password")

        val laptop = anna.newPhone("Anna's laptop")
        expectRejected(
            laptop.logIn(account.nickname, account.password),
            ErrorReason.WRONG_CREDENTIALS,
            "the old password no longer works",
        )
        laptop.logsIn(account.withPassword(newPassword))

        expectRejected(anna.refreshAccount(), ErrorReason.SESSION_EXPIRED, "Anna's old phone: session revoked")
        check(
            !anna.accountState.isLoggedIn && anna.accountState.sessionExpired,
            "the old phone is logged out and asks to log in again",
        )
        tablet.killApp()
        tablet.launchApp()
        awaitThat("the tablet finds out at its next start", 10.seconds) {
            tablet.accountState.isRestored && !tablet.accountState.isLoggedIn && tablet.accountState.sessionExpired
        }
        requireOk(laptop.refreshAccount(), "the phones logged in after the reset stay")
        requireOk(newPhone.refreshAccount(), "the phone that reset the password stays")
    }

    @Test
    fun deleteTheAccount() = scenario("Delete the account") {
        val anna = player("Anna", at = PARK)
        val account = anna.signsUp()
        val annaId = checkNotNull(anna.userId)
        val tablet = anna.newPhone("Anna's tablet")
        tablet.logsIn(account)

        expectRejected(anna.deleteAccount("not-her-password"), ErrorReason.WRONG_CREDENTIALS, "a wrong password")
        check(anna.accountState.isVerified, "nothing deleted, Anna is still logged in")
        requireOk(anna.deleteAccount(account.password), "Anna deletes her account")
        check(!anna.accountState.isLoggedIn, "Anna is logged out")
        check(anna.storage.read("account") == null, "no account saved on her phone")

        expectRejected(tablet.refreshAccount(), ErrorReason.SESSION_EXPIRED, "the tablet's session went with it")
        check(!tablet.accountState.isLoggedIn, "the tablet is logged out")
        val phone = anna.newPhone()
        expectRejected(
            phone.logIn(account.nickname, account.password),
            ErrorReason.WRONG_CREDENTIALS,
            "no login by nickname",
        )
        expectRejected(phone.logIn(account.email, account.password), ErrorReason.WRONG_CREDENTIALS, "no login by email")

        val bob = player("Bob", at = PARK)
        requireOk(bob.register(account.withPassword("bobs-password-1")), "Bob takes the free nickname and email")
        check(bob.userId != null && bob.userId != annaId, "a new account, not Anna's")
    }

    @Test
    fun loggedInPlayerAndGuestInOneGame() = scenario("A logged-in player and a guest in one game") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK)
        val account = sam.signsUp()

        sam.createsGame(GameSetups.fast())
        join(anna)
        val lobby = state().players
        check(
            lobby.single { it.id == sam.id }.let { it.name == account.nickname && it.userId == sam.userId },
            "Sam plays under his nickname, with his account",
        )
        check(lobby.single { it.id == anna.id }.let { it.name == "Anna" && it.userId == null }, "Anna is a guest")
        awaitThat("Anna's phone shows Sam's nickname and account") {
            anna.snapshot?.players?.any { it.id == sam.id && it.name == account.nickname && it.userId == sam.userId } ==
                true
        }
        check(anna.snapshot?.players?.single { it.id == anna.id }?.userId == null, "and Anna as a guest")

        sam.startsGame(seekers = listOf(sam))
        anna.walksTo(PARK.offset(eastMeters = 30.0))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        sam.catches(anna)
        awaitPhase(GamePhase.FINISHED)
        awaitThat("the results show Sam under his nickname") {
            anna.snapshot?.phase == GamePhase.FINISHED &&
                anna.snapshot?.players?.single { it.id == sam.id }?.name == account.nickname
        }
    }

    /** A code that is not [code]. */
    private fun wrongCode(code: String): String = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
}
