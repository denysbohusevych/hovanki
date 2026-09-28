package app.hovanki.shared.protocol

/**
 * HTTP API paths. The server maps the templates, the client builds concrete paths with the functions.
 * All mutating calls return the fresh [GameSnapshot] so the UI does not wait for the next poll.
 *
 * Game routes take the game token, account routes (accounts, me, friends, users, groups) the account token, both as
 * `Authorization: Bearer <token>`; creating and joining a game take an optional account token.
 */
object ApiRoutes {
    const val GAMES = "/api/v1/games"
    const val JOIN = "$GAMES/join"
    const val START = "$GAMES/{gameId}/start"
    const val SYNC = "$GAMES/{gameId}/sync"
    const val CATCHES = "$GAMES/{gameId}/catches"
    const val CATCH_CONFIRM = "$CATCHES/{catchId}/confirm"
    const val CATCH_DISPUTE = "$CATCHES/{catchId}/dispute"
    const val CATCH_VOTE = "$CATCHES/{catchId}/vote"

    /** GET: the buildings of the game's zone where hiding is not allowed ([BuildingsResponse]). */
    const val BUILDINGS = "$GAMES/{gameId}/buildings"

    /** GET: the zone by streets, one polygon per stage ([StreetZoneResponse]). */
    const val STREET_ZONE = "$GAMES/{gameId}/street-zone"

    /** POST [RolesRequest]: the host picks or draws the roles in the lobby. */
    const val ROLES = "$GAMES/{gameId}/roles"

    /** POST [SettingsRequest]: the host changes the setup in the lobby. */
    const val SETTINGS = "$GAMES/{gameId}/settings"

    /** POST, no body, 204: the player leaves the game for good; their token stops working. */
    const val LEAVE = "$GAMES/{gameId}/leave"

    /**
     * POST, no body: the host plays anyway in a zone that is too small for the players or has few places to hide; the
     * lobby warns no more in this game ([ZoneCapacity.accepted]).
     */
    const val CROWDING_ACCEPT = "$GAMES/{gameId}/crowding/accept"

    /** GET: every player's track of the round ([TracksResponse]), once the game is FINISHED. */
    const val TRACKS = "$GAMES/{gameId}/tracks"

    /** POST [InviteRequest]: invites friends or a group into the game (lobby, logged-in players only). */
    const val GAME_INVITES = "$GAMES/{gameId}/invites"

    /** POST [SendChatRequest]. */
    const val CHAT = "$GAMES/{gameId}/chat"

    /** POST: reports the chat message with this seq to the moderators. */
    const val CHAT_REPORT = "$CHAT/{seq}/report"

    // Accounts.
    const val ACCOUNTS = "/api/v1/accounts"
    const val LOGIN = "$ACCOUNTS/login"
    const val LOGOUT = "$ACCOUNTS/logout"
    const val PASSWORD_RESET = "$ACCOUNTS/password-reset"
    const val PASSWORD_RESET_CONFIRM = "$PASSWORD_RESET/confirm"

    // The caller's own account.
    const val ME = "/api/v1/me"
    const val ME_EMAIL = "$ME/email"
    const val ME_EMAIL_VERIFY = "$ME_EMAIL/verify"
    const val ME_EMAIL_RESEND = "$ME_EMAIL/resend"
    const val ME_PASSWORD = "$ME/password"
    const val ME_DELETE = "$ME/delete"
    const val INBOX = "$ME/inbox"
    const val INVITE_DISMISS = "$ME/invites/{inviteId}/dismiss"

    // The caller's history (docs/adr/0007-game-history-and-routes.md): only ever their own.

    /** POST [PrivacyRequest]: turns saving routes on or off; answers the [UserProfile]. */
    const val ME_PRIVACY = "$ME/privacy"

    /** GET: [PlayerStats]. */
    const val ME_STATS = "$ME/stats"

    /** GET, optional `?before=<finishedAtMillis>` for the next page: [GameHistoryResponse]. */
    const val ME_GAMES = "$ME/games"

    /** GET: [GameRoute]; 404 when no route of this game is saved. */
    const val ME_GAME_ROUTE = "$ME_GAMES/{gameId}/route"

    /** POST: deletes the saved route of this game (the game stays in the history). */
    const val ME_GAME_ROUTE_DELETE = "$ME_GAME_ROUTE/delete"

    // Friends and blocks.
    const val FRIENDS = "/api/v1/friends"
    const val FRIEND_REQUESTS = "$FRIENDS/requests"
    const val FRIEND_REQUEST_ACCEPT = "$FRIEND_REQUESTS/{userId}/accept"

    /** Declines a request to the caller, or withdraws the caller's own request. */
    const val FRIEND_REQUEST_DECLINE = "$FRIEND_REQUESTS/{userId}/decline"
    const val FRIEND_REMOVE = "$FRIENDS/{userId}/remove"
    const val USERS = "/api/v1/users"
    const val USER_BLOCK = "$USERS/{userId}/block"
    const val USER_UNBLOCK = "$USERS/{userId}/unblock"

    // Groups.
    const val GROUPS = "/api/v1/groups"
    const val GROUP_MEMBERS = "$GROUPS/{groupId}/members"

    /** The owner removes a member; a member removes themselves (leaves the group). */
    const val GROUP_MEMBER_REMOVE = "$GROUP_MEMBERS/{userId}/remove"
    const val GROUP_RENAME = "$GROUPS/{groupId}/rename"
    const val GROUP_DELETE = "$GROUPS/{groupId}/delete"

    // Staff admin (docs/adr/0008-admin.md): the /admin web page. Every route needs the [ADMIN_HEADER] and, after the
    // login, the admin session cookie [ADMIN_COOKIE]; the account token of the app gives no admin rights.
    const val ADMIN = "/api/v1/admin"
    const val ADMIN_LOGIN = "$ADMIN/login"
    const val ADMIN_LOGIN_TOTP = "$ADMIN_LOGIN/totp"
    const val ADMIN_ENROLL = "$ADMIN/enroll"
    const val ADMIN_ENROLL_CONFIRM = "$ADMIN_ENROLL/confirm"
    const val ADMIN_LOGOUT = "$ADMIN/logout"
    const val ADMIN_ME = "$ADMIN/me"
    const val ADMIN_REPORTS = "$ADMIN/reports"
    const val ADMIN_REPORT_RESOLVE = "$ADMIN_REPORTS/{reportId}/resolve"
    const val ADMIN_USERS = "$ADMIN/users"
    const val ADMIN_USERS_BY_EMAIL = "$ADMIN_USERS/find-by-email"
    const val ADMIN_USER = "$ADMIN_USERS/{userId}"
    const val ADMIN_USER_EMAIL = "$ADMIN_USER/email"
    const val ADMIN_USER_BAN = "$ADMIN_USER/ban"
    const val ADMIN_USER_UNBAN = "$ADMIN_USER/unban"
    const val ADMIN_USER_MUTE = "$ADMIN_USER/mute"
    const val ADMIN_USER_UNMUTE = "$ADMIN_USER/unmute"
    const val ADMIN_USER_RENAME = "$ADMIN_USER/rename"
    const val ADMIN_USER_LOGOUT = "$ADMIN_USER/logout"
    const val ADMIN_USER_DELETE = "$ADMIN_USER/delete"
    const val ADMIN_USER_ROLE = "$ADMIN_USER/role"
    const val ADMIN_USER_RESET_TOTP = "$ADMIN_USER/reset-totp"
    const val ADMIN_GAMES = "$ADMIN/games"
    const val ADMIN_GAME_END = "$ADMIN_GAMES/{gameId}/end"
    const val ADMIN_STATS = "$ADMIN/stats"
    const val ADMIN_STAFF = "$ADMIN/staff"
    const val ADMIN_AUDIT = "$ADMIN/audit"

    /** Every admin request carries `X-Hovanki-Admin: 1`: another site can't send it without CORS (CSRF). */
    const val ADMIN_HEADER = "X-Hovanki-Admin"

    /** The admin session: `HttpOnly`, `Secure`, `SameSite=Strict`; `__Host-`: only from this host, path `/`. */
    const val ADMIN_COOKIE = "__Host-hovanki-admin"

    const val AUTH_SCHEME = "Bearer"

    fun start(gameId: GameId): String = START.fill("gameId" to gameId.value)

    fun sync(gameId: GameId): String = SYNC.fill("gameId" to gameId.value)

    fun catches(gameId: GameId): String = CATCHES.fill("gameId" to gameId.value)

    fun catchConfirm(gameId: GameId, catchId: CatchId): String =
        CATCH_CONFIRM.fill("gameId" to gameId.value, "catchId" to catchId.value)

    fun catchDispute(gameId: GameId, catchId: CatchId): String =
        CATCH_DISPUTE.fill("gameId" to gameId.value, "catchId" to catchId.value)

    fun catchVote(gameId: GameId, catchId: CatchId): String =
        CATCH_VOTE.fill("gameId" to gameId.value, "catchId" to catchId.value)

    fun buildings(gameId: GameId): String = BUILDINGS.fill("gameId" to gameId.value)

    fun streetZone(gameId: GameId): String = STREET_ZONE.fill("gameId" to gameId.value)

    fun roles(gameId: GameId): String = ROLES.fill("gameId" to gameId.value)

    fun settings(gameId: GameId): String = SETTINGS.fill("gameId" to gameId.value)

    fun leave(gameId: GameId): String = LEAVE.fill("gameId" to gameId.value)

    fun crowdingAccept(gameId: GameId): String = CROWDING_ACCEPT.fill("gameId" to gameId.value)

    fun tracks(gameId: GameId): String = TRACKS.fill("gameId" to gameId.value)

    fun gameInvites(gameId: GameId): String = GAME_INVITES.fill("gameId" to gameId.value)

    fun chat(gameId: GameId): String = CHAT.fill("gameId" to gameId.value)

    fun chatReport(gameId: GameId, seq: Long): String =
        CHAT_REPORT.fill("gameId" to gameId.value, "seq" to seq.toString())

    fun inviteDismiss(inviteId: InviteId): String = INVITE_DISMISS.fill("inviteId" to inviteId.value)

    /** [ME_GAMES], the page of games that ended before [before] (null: the newest). */
    fun meGames(before: Long? = null): String = if (before == null) ME_GAMES else "$ME_GAMES?before=$before"

    fun meGameRoute(gameId: GameId): String = ME_GAME_ROUTE.fill("gameId" to gameId.value)

    fun meGameRouteDelete(gameId: GameId): String = ME_GAME_ROUTE_DELETE.fill("gameId" to gameId.value)

    fun friendRequestAccept(userId: UserId): String = FRIEND_REQUEST_ACCEPT.fill("userId" to userId.value)

    fun friendRequestDecline(userId: UserId): String = FRIEND_REQUEST_DECLINE.fill("userId" to userId.value)

    fun friendRemove(userId: UserId): String = FRIEND_REMOVE.fill("userId" to userId.value)

    fun userBlock(userId: UserId): String = USER_BLOCK.fill("userId" to userId.value)

    fun userUnblock(userId: UserId): String = USER_UNBLOCK.fill("userId" to userId.value)

    fun adminReportResolve(reportId: Long): String = ADMIN_REPORT_RESOLVE.fill("reportId" to reportId.toString())

    /** [ADMIN_USER] and the actions under it: `adminUser(id)`, `adminUser(id, "ban")`. */
    fun adminUser(userId: UserId, action: String? = null): String {
        val user = ADMIN_USER.fill("userId" to userId.value)
        return if (action == null) user else "$user/$action"
    }

    fun adminGameEnd(gameId: GameId): String = ADMIN_GAME_END.fill("gameId" to gameId.value)

    fun groupMembers(groupId: GroupId): String = GROUP_MEMBERS.fill("groupId" to groupId.value)

    fun groupMemberRemove(groupId: GroupId, userId: UserId): String =
        GROUP_MEMBER_REMOVE.fill("groupId" to groupId.value, "userId" to userId.value)

    fun groupRename(groupId: GroupId): String = GROUP_RENAME.fill("groupId" to groupId.value)

    fun groupDelete(groupId: GroupId): String = GROUP_DELETE.fill("groupId" to groupId.value)

    /** Replaces each `{name}` of the template with its value. */
    private fun String.fill(vararg values: Pair<String, String>): String =
        values.fold(this) { path, (name, value) -> path.replace("{$name}", value) }
}
