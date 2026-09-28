package app.hovanki.client.automation

import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.UserId

/**
 * Stable ids of the elements UI automation needs: `Modifier.testTag` in :composeApp, the device orchestrator in :e2e
 * and the Maestro flows in `e2e/maestro/` (docs/e2e.md). They become the Android resource-id in debug builds
 * (`testTagsAsResourceId`) and the iOS `accessibilityIdentifier`. Renaming one breaks the flows: change them together.
 */
object TestTags {
    const val LOADING = "loading"
    const val RESUMING = "resuming"
    const val RESUMING_LEAVE = "resuming_leave"

    /** The «Play» tab of the main screen (logged in): create a game, join by code, invites. */
    const val HOME_SCREEN = "home_screen"

    /** A guest's name, on the welcome screen. */
    const val HOME_NAME = "home_name"
    const val HOME_CREATE = "home_create"

    /** Join by code: on the welcome screen (as a guest) and on the «Play» tab. */
    const val HOME_JOIN_CODE = "home_join_code"
    const val HOME_JOIN = "home_join"

    /** Watch the open game of the code typed (docs/adr/0011-spectators-and-recordings.md): «Play» tab only. */
    const val HOME_WATCH = "home_watch"

    /** Watching a game: the screen, the delay chip, the list of players, «Stop watching», the end of it. */
    const val SPECTATOR_SCREEN = "spectator_screen"
    const val SPECTATOR_DELAY = "spectator_delay"
    const val SPECTATOR_PHASE = "spectator_phase"
    const val SPECTATOR_LEAVE = "spectator_leave"
    const val SPECTATOR_ENDED = "spectator_ended"

    /** Creating or joining a game is under way (location fix, server). */
    const val HOME_BUSY = "home_busy"

    /** A game could not be created or joined for a reason found on the phone (no name, no location fix...). */
    const val HOME_PROBLEM = "home_problem"
    const val HOME_BUILD = "home_build"

    // Logged out: log in, register, reset the password, or join as a guest (HOME_NAME, HOME_JOIN_CODE, HOME_JOIN).
    const val WELCOME_SCREEN = "welcome_screen"

    /** Opens the login form. */
    const val WELCOME_LOG_IN = "welcome_log_in"

    /** Opens the registration form. */
    const val WELCOME_REGISTER = "welcome_register"

    /** The server ended the account session: log in again. */
    const val WELCOME_SESSION_EXPIRED = "welcome_session_expired"

    /** Back from a login, registration or password reset form. */
    const val FORM_BACK = "form_back"
    const val LOGIN_LOGIN = "login_login"
    const val LOGIN_PASSWORD = "login_password"
    const val LOGIN_SUBMIT = "login_submit"
    const val LOGIN_FORGOT = "login_forgot"
    const val REGISTER_NICKNAME = "register_nickname"
    const val REGISTER_EMAIL = "register_email"
    const val REGISTER_PASSWORD = "register_password"
    const val REGISTER_SUBMIT = "register_submit"
    const val RESET_EMAIL = "reset_email"
    const val RESET_SEND_CODE = "reset_send_code"
    const val RESET_CODE = "reset_code"
    const val RESET_PASSWORD = "reset_password"
    const val RESET_SUBMIT = "reset_submit"

    /** An account command is under way (welcome, email confirmation, profile). */
    const val ACCOUNT_BUSY = "account_busy"

    /** An account command failed (wrong password, nickname taken, no network...). */
    const val ACCOUNT_ERROR = "account_error"

    /** Confirming the email (optional), full screen over the main screen; [PANEL_CLOSE] closes it. */
    const val VERIFY_PANEL = "verify_panel"

    /** The address the code went to. */
    const val VERIFY_EMAIL = "verify_email"
    const val VERIFY_CODE = "verify_code"
    const val VERIFY_SUBMIT = "verify_submit"
    const val VERIFY_RESEND = "verify_resend"

    /** Opens the change email form: [VERIFY_NEW_EMAIL], [VERIFY_PASSWORD] (the current one), [VERIFY_SAVE_EMAIL]. */
    const val VERIFY_CHANGE_EMAIL = "verify_change_email"
    const val VERIFY_NEW_EMAIL = "verify_new_email"
    const val VERIFY_PASSWORD = "verify_password"
    const val VERIFY_SAVE_EMAIL = "verify_save_email"

    /** The email was confirmed: a short notice on the main screen once the panel closed by itself. */
    const val VERIFY_CONFIRMED = "verify_confirmed"

    /** «Play», while the email is not confirmed: a card that opens the panel ([CONFIRM_EMAIL_OPEN]) or hides. */
    const val CONFIRM_EMAIL_CARD = "confirm_email_card"
    const val CONFIRM_EMAIL_OPEN = "confirm_email_open"

    /** Hides the card until the app starts again. */
    const val CONFIRM_EMAIL_LATER = "confirm_email_later"

    // Main screen (logged in): the navigation bar.
    const val TAB_PLAY = "tab_play"
    const val TAB_FRIENDS = "tab_friends"
    const val TAB_GROUPS = "tab_groups"
    const val TAB_PROFILE = "tab_profile"

    const val PROFILE_SCREEN = "profile_screen"
    const val PROFILE_NICKNAME = "profile_nickname"
    const val PROFILE_EMAIL = "profile_email"

    /** «not confirmed» next to the email; [PROFILE_CONFIRM_EMAIL] opens the panel. */
    const val PROFILE_EMAIL_UNCONFIRMED = "profile_email_unconfirmed"
    const val PROFILE_CONFIRM_EMAIL = "profile_confirm_email"

    /** Opens the change password form. */
    const val PROFILE_CHANGE_PASSWORD = "profile_change_password"
    const val PROFILE_CURRENT_PASSWORD = "profile_current_password"
    const val PROFILE_NEW_PASSWORD = "profile_new_password"
    const val PROFILE_SAVE_PASSWORD = "profile_save_password"
    const val PROFILE_PASSWORD_CHANGED = "profile_password_changed"
    const val PROFILE_LOG_OUT = "profile_log_out"

    /** Opens the delete account form (warning and password). */
    const val PROFILE_DELETE = "profile_delete"
    const val PROFILE_DELETE_PASSWORD = "profile_delete_password"
    const val PROFILE_DELETE_CONFIRM = "profile_delete_confirm"

    // The player's own history (docs/adr/0007-game-history-and-routes.md).

    /** The statistics card of the profile. */
    const val PROFILE_STATS = "profile_stats"
    const val STATS_GAMES = "stats_games"
    const val STATS_DISTANCE = "stats_distance"

    /** «Save my routes»: the switch in the profile (off asks first: the saved routes are deleted). */
    const val PROFILE_SAVE_ROUTES = "profile_save_routes"
    const val PROFILE_SAVE_ROUTES_OFF_CONFIRM = "profile_save_routes_off_confirm"

    /** Opens the history panel. */
    const val PROFILE_HISTORY = "profile_history"
    const val HISTORY_PANEL = "history_panel"
    const val HISTORY_EMPTY = "history_empty"
    const val HISTORY_MORE = "history_more"
    const val ROUTE_PANEL = "route_panel"
    const val ROUTE_MAP = "route_map"
    const val ROUTE_DELETE = "route_delete"
    const val ROUTE_DELETE_CONFIRM = "route_delete_confirm"

    /** A game's recording over the history, everybody's way (docs/adr/0011-spectators-and-recordings.md). */
    const val RECORDING_PANEL = "recording_panel"

    /** On the results screen: keep the routes, this game's too. */
    const val RESULTS_SAVE_ROUTES = "results_save_routes"
    const val RESULTS_ROUTE_SAVED = "results_route_saved"

    const val FRIENDS_SCREEN = "friends_screen"
    const val FRIENDS_NICKNAME = "friends_nickname"
    const val FRIENDS_ADD = "friends_add"

    /** A friends, groups or invite command failed. */
    const val SOCIAL_ERROR = "social_error"

    /** News after a friends command, e.g. "request sent". */
    const val SOCIAL_INFO = "social_info"

    const val GROUPS_SCREEN = "groups_screen"

    /** Opens the new group form. */
    const val GROUP_NEW = "group_new"

    /** The name of a new group, or the new name when renaming one. */
    const val GROUP_NAME = "group_name"
    const val GROUP_CREATE = "group_create"

    /** A group's panel over the main screen. */
    const val GROUP_PANEL = "group_panel"
    const val GROUP_PLAY = "group_play"

    /** Owner: opens the list of friends to add ([groupPick]), [GROUP_ADD_CONFIRM] adds them. */
    const val GROUP_ADD_MEMBERS = "group_add_members"
    const val GROUP_ADD_CONFIRM = "group_add_confirm"

    /** Owner: opens the name field ([GROUP_NAME]), [GROUP_SAVE_NAME] saves it. */
    const val GROUP_RENAME = "group_rename"
    const val GROUP_SAVE_NAME = "group_save_name"

    /** Owner: asks to confirm, [GROUP_DELETE_CONFIRM] deletes the group. */
    const val GROUP_DELETE = "group_delete"
    const val GROUP_DELETE_CONFIRM = "group_delete_confirm"
    const val GROUP_LEAVE = "group_leave"

    /** What the game does with location, before the system asks for it (only while the player has not decided). */
    const val LOCATION_CONSENT = "location_consent"
    const val LOCATION_CONSENT_ALLOW = "location_consent_allow"
    const val LOCATION_CONSENT_LATER = "location_consent_later"

    /** Closes a full-screen panel (email confirmation, group, invite, chat), like the system back button. */
    const val PANEL_CLOSE = "panel_close"

    /** Lobby: opens the panel to invite friends and groups. */
    const val LOBBY_INVITE = "lobby_invite"
    const val INVITE_PANEL = "invite_panel"
    const val INVITE_SEND = "invite_send"

    /** Lobby: the invitations went out. */
    const val INVITES_SENT = "invites_sent"

    /** Opens the chat panel; shows the unread count ([CHAT_UNREAD]). */
    const val CHAT_OPEN = "chat_open"
    const val CHAT_UNREAD = "chat_unread"
    const val CHAT_PANEL = "chat_panel"
    const val CHAT_INPUT = "chat_input"
    const val CHAT_SEND = "chat_send"
    const val CHAT_TO_ALL = "chat_to_all"
    const val CHAT_TO_TEAM = "chat_to_team"

    /** Actions on another player's message, after a long press on it. */
    const val CHAT_REPORT = "chat_report"
    const val CHAT_BLOCK = "chat_block"

    const val LOBBY_SCREEN = "lobby_screen"
    const val LOBBY_JOIN_CODE = "lobby_join_code"
    const val LOBBY_START = "lobby_start"
    const val LOBBY_WAITING = "lobby_waiting"

    /** «Random»: the host has the server draw the seekers. */
    const val LOBBY_RANDOM = "lobby_random"

    /** A player who is not the host: the role the host gave them («You seek» / «You hide»). */
    const val LOBBY_MY_ROLE = "lobby_my_role"

    /** The zone's buildings (loading or how many) and the zone by streets being built. */
    const val LOBBY_BUILDINGS = "lobby_buildings"
    const val LOBBY_STREETS = "lobby_streets"

    /**
     * How many players the zone fits (docs/adr/0010-big-games.md), and the host's warning of a crowded zone (or one with
     * few places to hide) with «Play anyway».
     */
    const val LOBBY_CAPACITY = "lobby_capacity"
    const val LOBBY_CROWDED = "lobby_crowded"
    const val LOBBY_PLAY_ANYWAY = "lobby_play_anyway"

    /** A big game's lobby: its title and countdown, and the friends in it. */
    const val BIG_LOBBY = "big_lobby"
    const val BIG_LOBBY_COUNTDOWN = "big_lobby_countdown"

    /** The host's game settings: the button in the lobby, the panel, its «Save». */
    const val LOBBY_SETTINGS = "lobby_settings"
    const val SETTINGS_PANEL = "settings_panel"
    const val SETTINGS_SAVE = "settings_save"
    const val SETTINGS_SHAPE_CIRCLE = "settings_shape_circle"
    const val SETTINGS_SHAPE_STREETS = "settings_shape_streets"
    const val SETTINGS_SHRINKS = "settings_shrinks"
    const val SETTINGS_GLOW = "settings_glow"

    /** Open to spectators, and how far behind they see it (docs/adr/0011-spectators-and-recordings.md). */
    const val SETTINGS_OPEN_GAME = "settings_open_game"

    /** One of the spectators' delays in the settings, by seconds (0: live). */
    fun settingsDelay(seconds: Int) = "settings_delay_$seconds"

    /** In the lobby: the game is open to spectators; how many watch; the game is recorded. */
    const val LOBBY_OPEN = "lobby_open"
    const val SPECTATORS = "spectators"
    const val LOBBY_RECORDED = "lobby_recorded"

    /** An invite into another game, over the lobby and the results: go there, or dismiss it. */
    const val INVITE_BANNER = "invite_banner"
    const val INVITE_BANNER_GO = "invite_banner_go"
    const val INVITE_BANNER_DISMISS = "invite_banner_dismiss"

    /** In a round: the invitation's badge on «More», and «Leave and go» in its dialog. */
    const val ROUND_INVITE = "round_invite"
    const val LEAVE_AND_GO = "leave_and_go"

    const val GAME_SCREEN = "game_screen"
    const val GAME_TIMER = "game_timer"
    const val GAME_OUT_OF_ZONE = "game_out_of_zone"
    const val GAME_CAUGHT = "game_caught"
    const val GAME_ELIMINATED = "game_eliminated"
    const val GAME_MAP = "game_map"
    const val MAP_ATTRIBUTION = "map_attribution"
    const val GAME_IN_BUILDING = "game_in_building"
    const val BUILDING_RULE_OFF = "building_rule_off"

    /** The glow's countdown (or «glowing») in the HUD, and the notice that the zone by streets fell back to circles. */
    const val GLOW_CHIP = "glow_chip"
    const val STREET_ZONE_OFF = "street_zone_off"
    const val CATCH_CODE = "catch_code"
    const val CATCH_DISPUTE = "catch_dispute"
    const val CODE_INPUT = "code_input"
    const val CODE_CONFIRM = "code_confirm"

    /** The hider's code as a QR code, and the seeker's camera for it. */
    const val CATCH_QR = "catch_qr"
    const val SCANNER_OPEN = "scanner_open"
    const val SCANNER = "scanner"

    /** One scan: the seeker's «Found!» opens the camera with no claim; the hider's «My code» shows it without one. */
    const val FOUND_OPEN = "found_open"
    const val MY_CODE_OPEN = "my_code_open"
    const val MY_CODE = "my_code"
    const val MY_CODE_DIGITS = "my_code_digits"
    const val MY_CODE_CLOSE = "my_code_close"

    /** The arrow back into the zone at the edge of the map. */
    const val ZONE_ARROW = "zone_arrow"
    const val LOBBY_SHARE = "lobby_share"

    const val RESULTS_SCREEN = "results_screen"
    const val RESULTS_BACK = "results_back"
    const val RESULTS_AWARDS = "results_awards"

    /** The replay on the results screen: the map with everybody's way, and its time slider. */
    const val REPLAY = "replay"
    const val REPLAY_SLIDER = "replay_slider"

    const val BANNER_RECONNECTING = "banner_reconnecting"
    const val BANNER_ERROR = "banner_error"
    const val BANNER_NO_LOCATION = "banner_no_location"

    fun lobbyPlayer(id: PlayerId) = "lobby_player_${id.value}"

    fun seekerSwitch(id: PlayerId) = "seeker_switch_${id.value}"

    /** A player's role in the lobby as the others see it (only the host can switch it: [seekerSwitch]). */
    fun lobbyRole(id: PlayerId) = "lobby_role_${id.value}"

    /** «Not connected» next to a player in the lobby. */
    fun lobbyOffline(id: PlayerId) = "lobby_offline_${id.value}"

    /** A setting's value in the settings panel, and its − and + buttons (e.g. `radius`, `hiding`, `glow_every`). */
    fun settingValue(name: String) = "setting_${name}_value"

    fun settingMinus(name: String) = "setting_${name}_minus"

    fun settingPlus(name: String) = "setting_${name}_plus"

    /** Title of the game screen; changes with the phase, so a flow can wait for the next phase. */
    fun phase(phase: GamePhase) = "phase_${phase.name.lowercase()}"

    fun claimButton(hiderId: PlayerId) = "claim_${hiderId.value}"

    fun voteConfirm(catchId: CatchId) = "vote_confirm_${catchId.value}"

    fun voteReject(catchId: CatchId) = "vote_reject_${catchId.value}"

    /** A big game on «Play» (docs/adr/0010-big-games.md): its card, «Sign up», «Cancel», «Into the lobby». */
    fun bigGame(id: String) = "big_game_$id"

    fun bigGameSignUp(id: String) = "big_game_sign_up_$id"

    fun bigGameCancel(id: String) = "big_game_cancel_$id"

    fun bigGameJoin(id: String) = "big_game_join_$id"

    /** A game invite on the «Play» tab, by the game's join code (one invite per game). */
    fun invite(joinCode: String) = "invite_$joinCode"

    fun inviteAccept(joinCode: String) = "invite_accept_$joinCode"

    fun inviteDismiss(joinCode: String) = "invite_dismiss_$joinCode"

    /** Friends tab: a friend, an incoming or outgoing request or a blocked user. */
    fun friend(userId: UserId) = "friend_${userId.value}"

    fun friendAccept(userId: UserId) = "friend_accept_${userId.value}"

    fun friendDecline(userId: UserId) = "friend_decline_${userId.value}"

    fun friendCancel(userId: UserId) = "friend_cancel_${userId.value}"

    fun friendRemove(userId: UserId) = "friend_remove_${userId.value}"

    fun friendBlock(userId: UserId) = "friend_block_${userId.value}"

    fun friendUnblock(userId: UserId) = "friend_unblock_${userId.value}"

    /** Groups tab: opens the group's panel. */
    fun group(groupId: GroupId) = "group_${groupId.value}"

    /** A friend to pick for a new group or to add to one. */
    fun groupPick(userId: UserId) = "group_pick_${userId.value}"

    fun groupMemberRemove(userId: UserId) = "group_remove_${userId.value}"

    /** Invite panel: a friend or a group to invite. */
    fun inviteFriend(userId: UserId) = "invite_friend_${userId.value}"

    fun inviteGroup(groupId: GroupId) = "invite_group_${groupId.value}"

    /** Lobby and results: the player has no account. */
    fun playerGuest(id: PlayerId) = "player_guest_${id.value}"

    /** Lobby and results: sends a friend request to the player (or accepts theirs). */
    fun addFriend(id: PlayerId) = "add_friend_${id.value}"

    /** Lobby and results: "friend", "request sent" or "blocked" next to the player. */
    fun playerRelation(id: PlayerId) = "player_relation_${id.value}"

    fun chatMessage(seq: Long) = "chat_message_$seq"

    /** A game in the history panel; [historyRoute] opens its route, when one is saved. */
    fun historyGame(gameId: GameId) = "history_game_${gameId.value}"

    fun historyRoute(gameId: GameId) = "history_route_${gameId.value}"

    fun historyRecording(gameId: GameId) = "history_recording_${gameId.value}"
}
