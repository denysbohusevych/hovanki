package app.hovanki.server.social

import app.hovanki.shared.rules.GroupRules

/**
 * How many friends and requests one account may have (docs/adr/0004-accounts-friends-chat.md); over a limit the server
 * answers 409 `LIMIT_REACHED`. The group limits are [GroupRules] in `:shared`: the app shows them in its forms.
 */
object SocialLimits {
    /** Friends per account, checked for both sides when a friendship starts. */
    const val MAX_FRIENDS = 500

    /** Unanswered friend requests one account may have sent. */
    const val MAX_OUTGOING_REQUESTS = 100
}
