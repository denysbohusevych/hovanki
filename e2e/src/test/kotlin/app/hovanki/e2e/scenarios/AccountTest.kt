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
 * Accounts (docs/adr/0004-accounts-friends-chat.md) through the app's AccountManager: sign-up, an account that works
 * right away while confirming the email with the emailed code is optional, login by nickname or email on other phones,
 * logout, fixing a mistyped email, password reset, deletion. The codes come from the emails the server sent
 * (observer), as a person reads them in their inbox.
 */
class AccountTest {
    @Test
    fun signUpAndConfirmTheEmailLater() = scenario("Sign up, confirm the email later") {
        val anna = player("Anna", at = PARK)
        val account = newAccount("Anna")

        requireOk(anna.register(account, language = "uk-UA"), "Anna registers")
        check(anna.accountState.hasUnconfirmedEmail, "Anna is logged in, her email not confirmed")
        check(anna.user?.nickname == account.nickname && anna.user?.email == account.email, "her nickname and email")
        eventually("the app loads Anna's friends right away: the account works unconfirmed") { anna.friends }
        requireOk(anna.refreshInbox(), "and her inbox")
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
            "Anna's email in capitals is taken, confirmed or not",
        )
        check(!bob.accountState.isLoggedIn, "Bob stays logged out")

        val wrong = anna.verifyEmail(wrongCode(firstCode))
        expectRejected(wrong, ErrorCode.INVALID_CODE, "a mistyped code")
        check((wrong as CommandResult.Rejected).reason == null, "wrong, not expired")
        check(anna.accountState.hasUnconfirmedEmail, "the email is still not confirmed")

        val inbox = observer.emails(account.email)
        requireOk(anna.resendCode(), "Anna asks for the code again")
        val secondCode = emailedCode(account.email, EmailPurpose.VERIFY_EMAIL, known = inbox)
        if (secondCode != firstCode) {
            expectRejected(anna.verifyEmail(firstCode), ErrorCode.INVALID_CODE, "the new code replaced the first one")
        }
        requireOk(anna.verifyEmail(secondCode), "Anna enters the new code")
        check(anna.accountState.hasConfirmedEmail, "Anna's email is confirmed")
        requireOk(anna.refreshAccount(), "Anna's app reloads the profile")
        check(anna.user?.emailVerified == true, "the server has it confirmed too")
    }

    @Test
    fun anAccountPlaysBeforeConfirmingItsEmail() = scenario("An account plays before confirming its email") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK)
        val account = sam.signsUp()
        check(sam.accountState.hasUnconfirmedEmail, "Sam has not confirmed his email")

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

        // Whenever he likes, e.g. on the results screen, with the code emailed at registration.
        sam.confirmsEmail()
        check(sam.accountState.hasConfirmedEmail, "Sam's email is confirmed now")
        check(sam.snapshot?.phase == GamePhase.FINISHED && sam.state.lastError == null, "his game didn't notice")
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
        check(
            tablet.userId == anna.userId && tablet.accountState.hasUnconfirmedEmail,
            "by nickname: Anna's account, its email still unconfirmed",
        )
        laptop.logsIn(account, login = account.email.uppercase())
        check(laptop.userId == anna.userId, "by email (not confirmed): Anna's account")

        laptop.killApp()
        laptop.launchApp()
        awaitThat("the relaunched app is logged in again", 10.seconds) {
            laptop.accountState.isLoggedIn && laptop.userId == anna.userId
        }

        requireOk(tablet.logOut(), "Anna logs out on the tablet")
        check(!tablet.accountState.isLoggedIn, "the tablet is logged out")
        check(tablet.storage.read("account") == null, "no account saved on the tablet")
        requireOk(anna.refreshAccount(), "Anna's phone still works")
        requireOk(laptop.refreshAccount(), "the laptop still works")
        check(anna.accountState.isLoggedIn && laptop.accountState.isLoggedIn, "both are still logged in")
    }

    @Test
    fun changeAMistypedEmail() = scenario("Change a mistyped email") {
        val anna = player("Anna", at = PARK)
        val bob = player("Bob", at = PARK)
        val account = newAccount("Anna")
        val mistyped = newAccount("Typo").email
        requireOk(anna.register(account.copy(email = mistyped)), "Anna registers with a typo in her email")
        val firstCode = emailedCode(mistyped, EmailPurpose.VERIFY_EMAIL)
        val bobAccount = bob.signsUp()

        expectRejected(
            anna.changeEmail(account.email, "not-her-password"),
            ErrorReason.WRONG_CREDENTIALS,
            "fixing it without her password",
        )
        expectRejected(anna.changeEmail("anna.example", account.password), ErrorReason.INVALID_EMAIL, "not an address")
        expectRejected(anna.changeEmail(bobAccount.email, account.password), ErrorReason.EMAIL_TAKEN, "Bob's address")
        check(anna.user?.email == mistyped, "the email is still the mistyped one")

        requireOk(anna.changeEmail(account.email, account.password), "Anna fixes her email, with her password")
        check(
            anna.user?.email == account.email && anna.accountState.hasUnconfirmedEmail,
            "the new address, unconfirmed",
        )
        val code = emailedCode(account.email, EmailPurpose.VERIFY_EMAIL)
        check(observer.emails(mistyped).size == 1, "nothing more went to the mistyped address")
        if (code != firstCode) {
            expectRejected(anna.verifyEmail(firstCode), ErrorCode.INVALID_CODE, "the code sent to the typo is void")
        }
        requireOk(anna.verifyEmail(code), "Anna confirms the new address with the code sent there")
        check(anna.accountState.hasConfirmedEmail, "Anna's email is confirmed")
        anna.newPhone().logsIn(account, login = account.email)

        val confirmed = anna.changeEmail(newAccount("Anna").email, account.password)
        expectRejected(confirmed, ErrorCode.WRONG_STATE, "changing a confirmed email")
        check((confirmed as CommandResult.Rejected).reason == null, "not possible (yet), whatever the address")
        check(anna.user?.email == account.email, "the confirmed email stays")
    }

    @Test
    fun passwordResetLogsTheOtherPhonesOut() = scenario("Password reset") {
        val anna = player("Anna", at = PARK)
        val account = anna.signsUp()
        val tablet = anna.newPhone("Anna's tablet")
        tablet.logsIn(account)
        val newPhone = anna.newPhone()
        check(anna.accountState.hasUnconfirmedEmail, "Anna never confirmed her email")

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
        check(newPhone.userId == anna.userId, "logged in with the new password")
        check(newPhone.accountState.hasConfirmedEmail, "the code from her inbox confirmed the email too")

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
        check(anna.accountState.isLoggedIn, "nothing deleted, Anna is still logged in")
        requireOk(anna.deleteAccount(account.password), "Anna deletes her account (email never confirmed)")
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

    /** A code that is not [code]. */
    private fun wrongCode(code: String): String = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
}
