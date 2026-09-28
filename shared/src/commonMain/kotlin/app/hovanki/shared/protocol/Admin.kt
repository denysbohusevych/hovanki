package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

// Staff admin (docs/adr/0008-admin.md): the DTOs of /api/v1/admin/*. The app never calls these routes; the /admin web
// page does, and the e2e tests do with these classes. Staff see only what they need to handle a report or a support
// request: never coordinates, routes, chat outside reports, passwords or tokens; the email only masked, in full only
// on request with a reason (audit log).

/** What an account may do: players play; moderators and admins also work in the admin. Default: [PLAYER]. */
@Serializable
enum class UserRole {
    PLAYER,

    /** Reports, chat bans, bans up to [AdminLimits.MODERATOR_MAX_BAN_DAYS] days, nickname resets, games, stats. */
    MODERATOR,

    /** Everything: also email lookups, longer bans, account deletion, ending games, moderators, the audit log. */
    ADMIN,
    ;

    val isStaff: Boolean get() = this != PLAYER
}

object AdminLimits {
    const val MODERATOR_MAX_BAN_DAYS = 30
    const val MAX_SANCTION_DAYS = 3650
    const val REASON_MAX_LENGTH = 500
}

/** Step 1 of the staff login: the account's password ([login]: email or nickname). */
@Serializable
data class AdminLoginRequest(val login: String, val password: String)

@Serializable
enum class AdminLoginStep {
    /** Enter the code of the authenticator app ([AdminTotpRequest]). */
    TOTP,

    /** No authenticator yet: a code went to the account's email; [AdminEnrollRequest], then [AdminTotpRequest]. */
    ENROLL,
}

/**
 * The password was right and the account is staff: [challenge] (valid for a few minutes) goes with the next step.
 * [emailHint]: for [AdminLoginStep.ENROLL], where the code went, masked.
 */
@Serializable
data class AdminLoginResponse(val challenge: String, val next: AdminLoginStep, val emailHint: String? = null)

/** A 6-digit code of the authenticator app: logs in ([ApiRoutes.ADMIN_LOGIN_TOTP]) or confirms the enrollment. */
@Serializable
data class AdminTotpRequest(val challenge: String, val code: String)

/** The code from the email proves the enrollment is the account owner's. */
@Serializable
data class AdminEnrollRequest(val challenge: String, val emailCode: String)

/**
 * The new authenticator secret: [secret] in base32 to type in, or [otpauthUri] as a QR code, [qr]: its rows of modules
 * (`1` dark, `0` light), without the quiet zone. Active once a code from the app confirms it.
 */
@Serializable
data class AdminEnrollment(val secret: String, val otpauthUri: String, val qr: List<String>)

/** The staff member of the current admin session. */
@Serializable
data class AdminMe(val userId: UserId, val nickname: String, val role: UserRole, val sessionExpiresAtMillis: Long)

/** Why: required for every action, it goes to the audit log. */
@Serializable
data class AdminReasonRequest(val reason: String)

@Serializable
enum class SanctionKind {
    /** No login, no game with the account, no chat. */
    BAN,

    /** No chat messages. */
    MUTE,
}

/** A ban or a chat ban. [untilMillis] null: forever. */
@Serializable
data class AdminSanction(
    val id: Long,
    val kind: SanctionKind,
    val reason: String,
    val byName: String,
    val createdAtMillis: Long,
    val untilMillis: Long?,
    val liftedAtMillis: Long? = null,
    val liftedByName: String? = null,
) {
    fun isActiveAt(nowMillis: Long): Boolean =
        liftedAtMillis == null && (untilMillis == null || untilMillis > nowMillis)
}

/** [days] null: forever (bans only). */
@Serializable
data class SanctionRequest(val days: Int?, val reason: String)

@Serializable
enum class ReportAction {
    /** Nothing wrong: the report is closed. */
    DISMISS,

    /** Chat ban for [ResolveReportRequest.days]. */
    MUTE,

    /** Ban for [ResolveReportRequest.days] (null: forever). */
    BAN,

    /** The nickname is offensive: reset to a random one. */
    RENAME,
}

/** Closes the report and every other open report on the same message; [action] on its author. */
@Serializable
data class ResolveReportRequest(val action: ReportAction, val reason: String, val days: Int? = null)

/** A reported chat message. The reporter's and the author's names are their current nicknames, or the game name. */
@Serializable
data class AdminReport(
    val id: Long,
    val gameId: GameId,
    val messageSeq: Long,
    val text: String,
    val authorId: UserId?,
    val authorName: String,
    val reporterId: UserId?,
    val reporterName: String?,
    val createdAtMillis: Long,
    val resolvedAtMillis: Long? = null,
    val resolvedByName: String? = null,
    val resolution: String? = null,
    /** Reports on the author's messages in the kept period (90 days), and from how many different players. */
    val authorReports: Int = 0,
    val authorReporters: Int = 0,
)

@Serializable
data class AdminReports(val reports: List<AdminReport>, val open: Int)

@Serializable
data class AdminUserRow(
    val id: UserId,
    val nickname: String,
    val role: UserRole,
    val createdAtMillis: Long,
    val banned: Boolean,
    val muted: Boolean,
)

@Serializable
data class AdminUsers(val users: List<AdminUserRow>)

/** Lookup by the exact address someone wrote to support from (admins; audit log). */
@Serializable
data class AdminFindByEmailRequest(val email: String, val reason: String)

/** What staff see of an account. The email is masked; [ApiRoutes.ADMIN_USER_EMAIL] shows it (admins; audit log). */
@Serializable
data class AdminUserCard(
    val id: UserId,
    val nickname: String,
    val role: UserRole,
    val createdAtMillis: Long,
    val language: String,
    val emailMasked: String,
    val emailVerified: Boolean,
    /** Whether «save my routes» is on; the routes themselves are never shown. */
    val saveRoutes: Boolean,
    val games: Int,
    val lastGameAtMillis: Long?,
    /** Logged-in devices and the latest use of one (hour precision). */
    val devices: Int,
    val lastSeenAtMillis: Long?,
    val friends: Int,
    /** How many users blocked this one. */
    val blockedBy: Int,
    val reportsAgainst: Int,
    val reportsBy: Int,
    /** Newest first. */
    val sanctions: List<AdminSanction>,
    /** Staff only: whether an authenticator is set up. */
    val totpEnrolled: Boolean = false,
)

@Serializable
data class AdminRevealedEmail(val email: String)

/** Admins make a player a moderator or back ([UserRole.ADMIN] is only ever set on the server, docs/deploy.md). */
@Serializable
data class AdminSetRoleRequest(val role: UserRole, val reason: String)

/** A game in the server's memory: no zone center (the host's position), no positions, no chat. */
@Serializable
data class AdminGame(
    val gameId: GameId,
    val phase: GamePhase,
    val hostName: String,
    val players: Int,
    val guests: Int,
    val seekers: Int,
    val createdAtMillis: Long,
    val phaseStartedAtMillis: Long,
    val lastActivityMillis: Long,
    val zoneRadiusMeters: Double,
    val chatMessages: Int,
    /** The "no hiding in buildings" rule: loading, on, or off because the map data could not be loaded. */
    val buildings: BuildingsState? = null,
    /** How many buildings the rule judges by, once READY. */
    val buildingCount: Int? = null,
    val zoneShape: ZoneShape? = null,
    /** The zone by streets (a [ZoneShape.STREETS] game): being built, built, or unavailable (circles instead). */
    val streetZone: StreetZoneState? = null,
    /** About how many players the zone fits (docs/adr/0010-big-games.md); null until known. */
    val capacity: Int? = null,
    /** The host chose to play anyway in a crowded zone or one with few places to hide. */
    val crowdingAccepted: Boolean = false,
)

@Serializable
data class AdminGames(val games: List<AdminGame>)

/**
 * Numbers for the dashboard: counts, and averages only over at least 5 games (null otherwise, docs/metrics.md).
 * Windows end at [generatedAtMillis]: 1, 7 and 30 days.
 */
@Serializable
data class AdminStats(
    val generatedAtMillis: Long,
    val users: Int,
    val usersNew7d: Int,
    val usersNew30d: Int,
    val activePlayers1d: Int,
    val activePlayers7d: Int,
    val games1d: Int,
    val games7d: Int,
    val games30d: Int,
    val avgPlayers30d: Double?,
    val avgSearchMinutes30d: Double?,
    val seekersWinRate30d: Double?,
    val disputes7d: Int,
    val reports7d: Int,
    val reportsOpen: Int,
    val activeBans: Int,
    val activeMutes: Int,
    val gamesLive: Int,
    val playersLive: Int,
    val serverVersion: String,
    val uptimeSeconds: Long,
    val heapUsedMb: Int,
    val heapMaxMb: Int,
)

@Serializable
data class AdminStaffMember(
    val id: UserId,
    val nickname: String,
    val role: UserRole,
    val totpEnrolled: Boolean,
    val lastLoginAtMillis: Long?,
)

@Serializable
data class AdminStaff(val staff: List<AdminStaffMember>)

/** What staff did; every entry has who, when and why. */
@Serializable
enum class AdminAction {
    LOGIN,
    ENROLL_TOTP,
    RESOLVE_REPORT,
    BAN,
    UNBAN,
    MUTE,
    UNMUTE,
    RENAME,
    LOGOUT_DEVICES,
    DELETE_ACCOUNT,
    SHOW_EMAIL,
    FIND_BY_EMAIL,
    END_GAME,
    SET_ROLE,
    RESET_TOTP,
}

@Serializable
data class AdminAuditEntry(
    val id: Long,
    val atMillis: Long,
    val actorId: UserId,
    val actorName: String,
    val action: AdminAction,
    val targetUserId: UserId? = null,
    /** A game id, a report id, a new role: what else the action was about. */
    val target: String? = null,
    val reason: String? = null,
)

@Serializable
data class AdminAudit(val entries: List<AdminAuditEntry>)
