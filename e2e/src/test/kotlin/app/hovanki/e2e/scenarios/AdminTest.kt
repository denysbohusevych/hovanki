package app.hovanki.e2e.scenarios

import app.hovanki.e2e.admin.AdminRejected
import app.hovanki.e2e.admin.StaffConsole
import app.hovanki.e2e.bot.CommandResult
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.ReportAction
import app.hovanki.shared.protocol.SanctionKind
import app.hovanki.shared.protocol.UserRole
import kotlin.test.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class AdminTest {
    /**
     * The moderation loop end to end (docs/adr/0008-admin.md): a player reports an insult, a moderator logs in to the
     * admin with the password and an authenticator app, sees the message and bans its author from the chat; later
     * bans him. The phones get the refusals with the end dates, the banned one is logged out and can't log in.
     */
    @Test
    fun aReportEndsInABan() = scenario("A report ends in a ban") {
        val sam = player("Sam", at = PARK)
        val troll = player("Troll", at = PARK.offset(eastMeters = 20.0))
        val mila = player("Mila", at = PARK.offset(eastMeters = -40.0))
        sam.signsUp()
        val trollAccount = troll.signsUp()
        val milaAccount = mila.signsUp()
        mila.confirmsEmail()
        observer.setRole(checkNotNull(mila.userId), UserRole.MODERATOR)
        val trollId = checkNotNull(troll.userId)

        sam.createsGame(GameSetups.fast())
        join(troll)
        requireOk(troll.sendChat("you all stink"), "Troll insults everybody")
        val message = eventually("Sam reads it") { sam.chat.firstOrNull { it.text == "you all stink" } }
        requireOk(sam.reportChat(message.seq), "Sam reports the message")

        StaffConsole(serverUrl, observer).use { console ->
            // Not staff yet: the same answer as a wrong password.
            val notStaff = runCatching { StaffConsole(serverUrl, observer).use { it.logIn(trollAccount) } }
            check(
                (notStaff.exceptionOrNull() as? AdminRejected)?.error?.reason == ErrorReason.WRONG_CREDENTIALS,
                "a player can't log in to the admin",
            )

            check(console.logIn(milaAccount).role == UserRole.MODERATOR, "Mila sets up her authenticator and logs in")
            val report = console.openReports().single { it.gameId == gameId && it.messageSeq == message.seq }
            check(
                report.text == "you all stink" && report.authorId == trollId,
                "the report shows the message and its author",
            )
            note("Mila reads the report: ${report.authorReports} report(s) on ${report.authorName}")

            val muted = console.resolve(report.id, ReportAction.MUTE, reason = "insults", days = 1)
            check(muted.resolvedAtMillis != null, "the report is handled")
            val refused = troll.sendChat("you can't stop me")
            expectRejected(refused, ErrorReason.CHAT_MUTED, "Troll's next message")
            val until = checkNotNull((refused as CommandResult.Rejected).untilMillis) { "the chat ban has an end" }
            val left = until - System.currentTimeMillis()
            check(left > 23.hours.inWholeMilliseconds && left <= 1.days.inWholeMilliseconds, "a day long")
            holdsFor("Troll plays on", 2.seconds) { troll.state.session != null }

            // He goes on in other ways: Mila bans him for a week.
            val card = console.ban(trollId, days = 7, reason = "insults again")
            check(card.sanctions.any { it.kind == SanctionKind.BAN }, "the card shows the ban")
            expectRejected(troll.refreshAccount(), ErrorReason.SESSION_EXPIRED, "Troll's app reloads the profile")
            check(!troll.accountState.isLoggedIn, "the ban logs Troll's phone out")
            val login = troll.logIn(trollAccount.nickname, trollAccount.password)
            expectRejected(login, ErrorReason.ACCOUNT_BANNED, "Troll logs in again")
            check((login as CommandResult.Rejected).untilMillis != null, "the phone learns when the ban ends")
            val wrongPassword = troll.logIn(trollAccount.nickname, "not my password")
            expectRejected(wrongPassword, ErrorReason.WRONG_CREDENTIALS, "a wrong password tells nothing about the ban")

            // Moderators ban for 30 days at most.
            val forever = runCatching { console.ban(trollId, days = null, reason = "forever") }.exceptionOrNull()
            check((forever as? AdminRejected)?.error?.code == ErrorCode.FORBIDDEN, "only admins ban forever")
        }
    }
}
