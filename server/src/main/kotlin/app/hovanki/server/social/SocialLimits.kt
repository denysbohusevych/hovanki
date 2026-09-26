package app.hovanki.server.social

import app.hovanki.shared.rules.GroupRules
import java.time.Duration

/**
 * How many friends and requests one account may have (docs/adr/0004-accounts-friends-chat.md); over a limit the server
 * answers 409 `LIMIT_REACHED`. The group limits are [GroupRules] in `:shared`: the app shows them in its forms. Also
 * how long game invitations live and how many the server keeps ([InviteRegistry]).
 */
object SocialLimits {
    /** Friends per account, checked for both sides when a friendship starts. */
    const val MAX_FRIENDS = 500

    /** Unanswered friend requests one account may have sent. */
    const val MAX_OUTGOING_REQUESTS = 100

    /** A game invitation is gone after this, or as soon as its game leaves the lobby. */
    val INVITE_TTL: Duration = Duration.ofMinutes(30)

    /** Invitations one account keeps (one per game); a new one beyond it pushes out the oldest. */
    const val MAX_INVITES_PER_USER = 50

    /** Memory bound of all invitations; beyond it, the oldest go. */
    const val MAX_INVITES = 100_000
}
