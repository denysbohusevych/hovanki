package app.hovanki.shared.protocol

/**
 * HTTP API paths. The server maps the templates, the client builds concrete paths with the functions.
 * All mutating calls return the fresh [GameSnapshot] so the UI does not wait for the next poll.
 *
 * Game routes take the game token, account routes (accounts, me, friends, users, groups) the account token, both as
 * `Authorization: Bearer <token>`; creating and joining a game take an optional account token.
 */
object ApiRoutes {
    /**
     * GET, no token: the server's clock ([ServerTimeResponse]), for measuring a device's clock offset before any game
     * (the debug build's radio lab, docs/radio-lab.md §4.3). Rate limited per client IP.
     */
    const val TIME = "/api/v1/time"

    const val GAMES = "/api/v1/games"
    const val JOIN = "$GAMES/join"
    const val START = "$GAMES/{gameId}/start"
    const val SYNC = "$GAMES/{gameId}/sync"

    /**
     * GET with the WebSocket upgrade and the game token: the live channel ([ClientFrame], [ServerFrame],
     * docs/adr/0015-websockets.md), `sync` as frames plus pokes; only while the server has [ServerFeature.LIVE_SOCKET].
     */
    const val SOCKET = "$GAMES/{gameId}/socket"
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

    /**
     * POST [SettingsPreviewRequest]: what the host's draft makes before it is saved, the zone by streets
     * ([SettingsPreviewResponse]); host only, in the lobby.
     */
    const val SETTINGS_PREVIEW = "$SETTINGS/preview"

    /** POST, no body, 204: the player leaves the game for good; their token stops working. */
    const val LEAVE = "$GAMES/{gameId}/leave"

    /**
     * POST, no body: the host plays anyway in a zone that is too small for the players or has few places to hide; the
     * lobby warns no more in this game ([ZoneCapacity.accepted]).
     */
    const val CROWDING_ACCEPT = "$GAMES/{gameId}/crowding/accept"

    /**
     * POST [PauseRequest]: the host puts the round on pause or lets it go on (docs/adr/0019-pause-and-sos.md); not in a
     * big game, whose host is the server.
     */
    const val PAUSE = "$GAMES/{gameId}/pause"

    /**
     * POST [SosRequest]: a player calls for help, or says they are fine again; the host may end anybody's
     * (docs/adr/0019-pause-and-sos.md). An SOS puts the round on pause and shows everybody where the caller is.
     */
    const val SOS = "$GAMES/{gameId}/sos"

    /** GET: every player's track of the round ([TracksResponse]), once the game is FINISHED. */
    const val TRACKS = "$GAMES/{gameId}/tracks"

    /** POST [InviteRequest]: invites friends or a group into the game (lobby, logged-in players only). */
    const val GAME_INVITES = "$GAMES/{gameId}/invites"

    /** POST [SendChatRequest]. */
    const val CHAT = "$GAMES/{gameId}/chat"

    /** POST: reports the chat message with this seq to the moderators. */
    const val CHAT_REPORT = "$CHAT/{seq}/report"

    // The board (docs/adr/0013-quests-sparks-and-sensors.md).

    /** POST [PlaceItemRequest]: the host places an item on the map, in the lobby. */
    const val ITEMS = "$GAMES/{gameId}/items"

    /** POST, no body: the host removes an item, in the lobby. */
    const val ITEM_REMOVE = "$ITEMS/{itemId}/remove"

    /** POST [ScanCheckpointRequest]: the player scanned a checkpoint's code. */
    const val CHECKPOINT_SCAN = "$GAMES/{gameId}/checkpoints/scan"

    /** POST [UsePerkRequest]: the player uses a perk. */
    const val PERKS = "$GAMES/{gameId}/perks"

    /** POST [CustomQuestRequest]: the host makes up a quest in words, in the lobby. */
    const val QUESTS = "$GAMES/{gameId}/quests"

    /** POST, no body: the player says they did the host's quest. */
    const val QUEST_DONE = "$QUESTS/{questId}/done"

    /** POST [QuestReviewRequest]: the host confirms or rejects. */
    const val QUEST_REVIEW = "$QUESTS/{questId}/review"
    // Watching an open game (docs/adr/0011-spectators-and-recordings.md).

    /** POST [WatchRequest] with the account token: [WatchResponse]; the spectator token is for the routes below. */
    const val WATCH = "$GAMES/watch"

    /** GET with the spectator token: [SpectatorSnapshot], the game as it was the game's delay ago. */
    const val SPECTATE = "$GAMES/{gameId}/spectate"

    /** GET with the spectator token: the zone by streets ([StreetZoneResponse]), once READY. */
    const val SPECTATE_STREET_ZONE = "$SPECTATE/street-zone"

    /** POST, no body, 204: stop watching; the spectator token stops working. */
    const val SPECTATE_LEAVE = "$SPECTATE/leave"

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

    /** GET: [GameRecording], everybody's way through the game; 404 unless the caller played it with an account. */
    const val ME_GAME_RECORDING = "$ME_GAMES/{gameId}/recording"

    // Big games (docs/adr/0010-big-games.md), with the account token.

    /** GET: the big games ahead and going on ([BigGamesResponse]). */
    const val BIG_GAMES = "/api/v1/big-games"

    /** POST, no body: signs the caller up ([BigGameCard]); `/cancel` takes it back. */
    const val BIG_GAME_SIGNUP = "$BIG_GAMES/{bigGameId}/signup"
    const val BIG_GAME_SIGNUP_CANCEL = "$BIG_GAME_SIGNUP/cancel"

    /** POST [JoinBigGameRequest]: into the open lobby of a big game the caller signed up for ([SessionResponse]). */
    const val BIG_GAME_JOIN = "$BIG_GAMES/{bigGameId}/join"

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

    /** POST [AdminReasonRequest]: an admin starts watching an open game live (audit log). */
    const val ADMIN_GAME_WATCH = "$ADMIN_GAMES/{gameId}/watch"

    /** GET: [AdminLiveGame], the open game right now, while the admin watches it. */
    const val ADMIN_GAME_LIVE = "$ADMIN_GAMES/{gameId}/live"
    const val ADMIN_STATS = "$ADMIN/stats"
    const val ADMIN_STAFF = "$ADMIN/staff"
    const val ADMIN_AUDIT = "$ADMIN/audit"

    /** GET: the server features and whether they are on ([AdminFeatures]). */
    const val ADMIN_FEATURES = "$ADMIN/features"

    /** POST [AdminFeatureRequest]: an admin turns a feature on or off for the whole server. */
    const val ADMIN_FEATURE = "$ADMIN_FEATURES/{feature}"
    // Big games (docs/adr/0010-big-games.md): admins only, every change with a reason in the audit log.

    /** GET: [AdminBigGames]; POST [AdminBigGameRequest]: a new big game ([AdminBigGame]). */
    const val ADMIN_BIG_GAMES = "$ADMIN/big-games"

    /** POST [AdminBigGameRequest]: title, time, place, setup, norms, limit; before the round. */
    const val ADMIN_BIG_GAME_UPDATE = "$ADMIN_BIG_GAMES/{bigGameId}/update"

    /** POST [AdminReasonRequest]: the round starts now (the lobby must be open). */
    const val ADMIN_BIG_GAME_START = "$ADMIN_BIG_GAMES/{bigGameId}/start"

    /** POST [AdminReasonRequest]. */
    const val ADMIN_BIG_GAME_CANCEL = "$ADMIN_BIG_GAMES/{bigGameId}/cancel"

    /** POST [AdminZoneEstimateRequest]: the area of a drawn zone and how many players it fits ([AdminZoneEstimate]). */
    const val ADMIN_ZONE_ESTIMATE = "$ADMIN/zone-estimate"

    /** GET: a vector tile of the players' map for the admin's map (the page loads nothing from other hosts). */
    const val ADMIN_TILE = "$ADMIN/tiles/{z}/{x}/{y}"

    // The radio lab's runs (docs/adr/0017-radar-techniques-and-big-run.md §5): debug builds only, and only while the
    // server has [ServerFeature.RADIO_LAB] on (404 otherwise). The phone routes take the device token of the join.
    const val LAB_RUNS = "/api/v1/lab/runs"

    /** POST [LabJoinRequest], no token: [LabJoinResponse]. */
    const val LAB_JOIN = "$LAB_RUNS/join"

    /** GET with the device token: [LabRunStateView]. */
    const val LAB_STATE = "$LAB_RUNS/{runId}/state"

    /** POST [LabAdvanceRequest] with the device token: [LabRunStateView]. */
    const val LAB_ADVANCE = "$LAB_RUNS/{runId}/advance"

    /** POST the log's lines as JSONL ([LabUpload]) with the device token: [LabEventsResponse]. */
    const val LAB_EVENTS = "$LAB_RUNS/{runId}/events"

    /**
     * POST [FieldJoinRequest] with the player's game token: [FieldJoinResponse], the game's field log
     * (docs/adr/0018-field-test-build.md §3.1); the uploads then go to [LAB_EVENTS]. 404 while the server has
     * [ServerFeature.FIELD_LOG] off.
     */
    const val GAME_FIELD_JOIN = "$GAMES/{gameId}/field/join"

    /**
     * POST with the player's game token, no body (204): the phone left its game's field log (the log stopped, or the
     * tester took the consent back); the server's own events of the game stop naming the player
     * (docs/adr/0018-field-test-build.md §3.3). 404 while the server has [ServerFeature.FIELD_LOG] off.
     */
    const val GAME_FIELD_LEAVE = "$GAMES/{gameId}/field/leave"

    /**
     * POST [LabUwbTokenRequest] with the device token: this device's UWB discovery token for the run's other phones
     * (a new one replaces the old); [LabRunStateView] with every device's.
     */
    const val LAB_UWB = "$LAB_RUNS/{runId}/uwb"

    // The radio lab in the admin: admins only, whether the flag is on or off (old reports stay readable).

    /** GET: [AdminLabRuns]; POST [AdminLabRunRequest]: a new run ([AdminLabRun]). */
    const val ADMIN_LAB_RUNS = "$ADMIN/lab/runs"

    /** GET: [AdminLabRunView], the console and the live view. */
    const val ADMIN_LAB_RUN = "$ADMIN_LAB_RUNS/{runId}"

    /** POST [AdminLabAdvanceRequest]: [AdminLabRunView]. */
    const val ADMIN_LAB_RUN_ADVANCE = "$ADMIN_LAB_RUN/advance"

    /** POST [AdminReasonRequest]: the run ends and its report is computed ([AdminLabRunView]). */
    const val ADMIN_LAB_RUN_FINISH = "$ADMIN_LAB_RUN/finish"

    /** GET: the run's report (`app.hovanki.shared.lab.LabReport`); 404 until it is computed. */
    const val ADMIN_LAB_RUN_REPORT = "$ADMIN_LAB_RUN/report"

    /** POST [AdminReasonRequest]: every device's log as a zip (`application/zip`); audited. */
    const val ADMIN_LAB_RUN_RAW = "$ADMIN_LAB_RUN/raw"

    /** POST [AdminReasonRequest], 204: the run goes with everything of it. */
    const val ADMIN_LAB_RUN_DELETE = "$ADMIN_LAB_RUN/delete"

    // The field log's games in the admin (docs/adr/0018-field-test-build.md §6): admins only, every export audited.

    /** GET: [AdminFieldGames], the field runs of games (newest first); the lab's list has the lab's runs only. */
    const val ADMIN_FIELD_GAMES = "$ADMIN/field/games"

    /**
     * A game's field run ([LabRunId] of `LabRunKind.GAME`): GET [AdminFieldGameView] (its run, phones and live view);
     * DELETE [AdminReasonRequest], 204: the run goes with its logs and report. The exports are under it.
     */
    const val ADMIN_FIELD_GAME = "$ADMIN/field/games/{runId}"

    /** GET: the game's stored report (`app.hovanki.shared.lab.FieldReport`, players P1…Pn); 404 until it is computed. */
    const val ADMIN_FIELD_GAME_REPORT = "$ADMIN_FIELD_GAME/report"

    /** POST [AdminFieldMarkRequest], 204: an organizer's mark in the game's log, at the server's time now. */
    const val ADMIN_FIELD_GAME_MARKS = "$ADMIN_FIELD_GAME/marks"

    /** POST [AdminReasonRequest]: the game's report as Markdown (`text/markdown`), players P1…Pn, no coordinates. */
    const val ADMIN_FIELD_GAME_REPORT_MD = "$ADMIN_FIELD_GAME/report.md"

    /** POST [AdminReasonRequest]: `digest.jsonl` (`application/x-ndjson`) for an AI: P1…Pn, no coordinates. */
    const val ADMIN_FIELD_GAME_DIGEST = "$ADMIN_FIELD_GAME/digest.jsonl"

    /** POST [AdminFieldRawRequest]: the raw logs (`application/zip`), all or a slice of devices and time. */
    const val ADMIN_FIELD_GAME_RAW = "$ADMIN_FIELD_GAME/raw.zip"

    /** Every admin request carries `X-Hovanki-Admin: 1`: another site can't send it without CORS (CSRF). */
    const val ADMIN_HEADER = "X-Hovanki-Admin"

    /** The admin session: `HttpOnly`, `Secure`, `SameSite=Strict`; `__Host-`: only from this host, path `/`. */
    const val ADMIN_COOKIE = "__Host-hovanki-admin"

    const val AUTH_SCHEME = "Bearer"

    fun start(gameId: GameId): String = START.fill("gameId" to gameId.value)

    fun sync(gameId: GameId): String = SYNC.fill("gameId" to gameId.value)

    fun socket(gameId: GameId): String = SOCKET.fill("gameId" to gameId.value)

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

    fun settingsPreview(gameId: GameId): String = SETTINGS_PREVIEW.fill("gameId" to gameId.value)

    fun leave(gameId: GameId): String = LEAVE.fill("gameId" to gameId.value)

    fun crowdingAccept(gameId: GameId): String = CROWDING_ACCEPT.fill("gameId" to gameId.value)

    fun pause(gameId: GameId): String = PAUSE.fill("gameId" to gameId.value)

    fun sos(gameId: GameId): String = SOS.fill("gameId" to gameId.value)

    fun tracks(gameId: GameId): String = TRACKS.fill("gameId" to gameId.value)

    fun gameInvites(gameId: GameId): String = GAME_INVITES.fill("gameId" to gameId.value)

    fun chat(gameId: GameId): String = CHAT.fill("gameId" to gameId.value)

    fun chatReport(gameId: GameId, seq: Long): String =
        CHAT_REPORT.fill("gameId" to gameId.value, "seq" to seq.toString())

    fun bigGameSignup(id: BigGameId): String = BIG_GAME_SIGNUP.fill("bigGameId" to id.value)

    fun bigGameSignupCancel(id: BigGameId): String = BIG_GAME_SIGNUP_CANCEL.fill("bigGameId" to id.value)

    fun bigGameJoin(id: BigGameId): String = BIG_GAME_JOIN.fill("bigGameId" to id.value)

    fun adminBigGameUpdate(id: BigGameId): String = ADMIN_BIG_GAME_UPDATE.fill("bigGameId" to id.value)

    fun adminBigGameStart(id: BigGameId): String = ADMIN_BIG_GAME_START.fill("bigGameId" to id.value)

    fun adminBigGameCancel(id: BigGameId): String = ADMIN_BIG_GAME_CANCEL.fill("bigGameId" to id.value)

    fun adminTile(z: Int, x: Int, y: Int): String =
        ADMIN_TILE.fill("z" to z.toString(), "x" to x.toString(), "y" to y.toString())

    fun inviteDismiss(inviteId: InviteId): String = INVITE_DISMISS.fill("inviteId" to inviteId.value)

    fun spectate(gameId: GameId): String = SPECTATE.fill("gameId" to gameId.value)

    fun spectateStreetZone(gameId: GameId): String = SPECTATE_STREET_ZONE.fill("gameId" to gameId.value)

    fun spectateLeave(gameId: GameId): String = SPECTATE_LEAVE.fill("gameId" to gameId.value)

    /** [ME_GAMES], the page of games that ended before [before] (null: the newest). */
    fun meGames(before: Long? = null): String = if (before == null) ME_GAMES else "$ME_GAMES?before=$before"

    fun meGameRoute(gameId: GameId): String = ME_GAME_ROUTE.fill("gameId" to gameId.value)

    fun meGameRouteDelete(gameId: GameId): String = ME_GAME_ROUTE_DELETE.fill("gameId" to gameId.value)

    fun meGameRecording(gameId: GameId): String = ME_GAME_RECORDING.fill("gameId" to gameId.value)

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

    fun adminFeature(feature: ServerFeature): String = ADMIN_FEATURE.fill("feature" to feature.name)

    fun items(gameId: GameId): String = ITEMS.fill("gameId" to gameId.value)

    fun itemRemove(gameId: GameId, itemId: ItemId): String =
        ITEM_REMOVE.fill("gameId" to gameId.value, "itemId" to itemId.value)

    fun checkpointScan(gameId: GameId): String = CHECKPOINT_SCAN.fill("gameId" to gameId.value)

    fun perks(gameId: GameId): String = PERKS.fill("gameId" to gameId.value)

    fun quests(gameId: GameId): String = QUESTS.fill("gameId" to gameId.value)

    fun questDone(gameId: GameId, questId: QuestId): String =
        QUEST_DONE.fill("gameId" to gameId.value, "questId" to questId.value)

    fun questReview(gameId: GameId, questId: QuestId): String =
        QUEST_REVIEW.fill("gameId" to gameId.value, "questId" to questId.value)
    fun adminGameWatch(gameId: GameId): String = ADMIN_GAME_WATCH.fill("gameId" to gameId.value)

    fun adminGameLive(gameId: GameId): String = ADMIN_GAME_LIVE.fill("gameId" to gameId.value)

    fun groupMembers(groupId: GroupId): String = GROUP_MEMBERS.fill("groupId" to groupId.value)

    fun groupMemberRemove(groupId: GroupId, userId: UserId): String =
        GROUP_MEMBER_REMOVE.fill("groupId" to groupId.value, "userId" to userId.value)

    fun groupRename(groupId: GroupId): String = GROUP_RENAME.fill("groupId" to groupId.value)

    fun groupDelete(groupId: GroupId): String = GROUP_DELETE.fill("groupId" to groupId.value)

    fun labState(runId: LabRunId): String = LAB_STATE.fill("runId" to runId.value)

    fun labAdvance(runId: LabRunId): String = LAB_ADVANCE.fill("runId" to runId.value)

    fun labEvents(runId: LabRunId): String = LAB_EVENTS.fill("runId" to runId.value)

    fun gameFieldJoin(gameId: GameId): String = GAME_FIELD_JOIN.fill("gameId" to gameId.value)

    fun gameFieldLeave(gameId: GameId): String = GAME_FIELD_LEAVE.fill("gameId" to gameId.value)

    fun labUwb(runId: LabRunId): String = LAB_UWB.fill("runId" to runId.value)

    /** [ADMIN_LAB_RUN] and the actions under it: `adminLabRun(id)`, `adminLabRun(id, "finish")`. */
    fun adminLabRun(runId: LabRunId, action: String? = null): String {
        val run = ADMIN_LAB_RUN.fill("runId" to runId.value)
        return if (action == null) run else "$run/$action"
    }

    /** [ADMIN_FIELD_GAME] and the exports under it: `adminFieldGame(id, "digest.jsonl")`. */
    fun adminFieldGame(runId: LabRunId, export: String? = null): String {
        val run = ADMIN_FIELD_GAME.fill("runId" to runId.value)
        return if (export == null) run else "$run/$export"
    }

    /** Replaces each `{name}` of the template with its value. */
    private fun String.fill(vararg values: Pair<String, String>): String =
        values.fold(this) { path, (name, value) -> path.replace("{$name}", value) }
}
