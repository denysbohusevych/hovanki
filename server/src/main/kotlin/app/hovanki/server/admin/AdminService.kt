package app.hovanki.server.admin

import app.hovanki.server.account.AccountService
import app.hovanki.server.account.AccountSessionRepository
import app.hovanki.server.account.UserRecord
import app.hovanki.server.account.UserRepository
import app.hovanki.server.features.FeatureFlags
import app.hovanki.server.game.GameException
import app.hovanki.server.game.GameService
import app.hovanki.server.moderation.ReportRecord
import app.hovanki.server.moderation.ReportRepository
import app.hovanki.server.moderation.SanctionRepository
import app.hovanki.shared.protocol.AdminAction
import app.hovanki.shared.protocol.AdminAudit
import app.hovanki.shared.protocol.AdminFeature
import app.hovanki.shared.protocol.AdminFeatureRequest
import app.hovanki.shared.protocol.AdminFeatures
import app.hovanki.shared.protocol.AdminFindByEmailRequest
import app.hovanki.shared.protocol.AdminGames
import app.hovanki.shared.protocol.AdminLimits
import app.hovanki.shared.protocol.AdminLiveGame
import app.hovanki.shared.protocol.AdminReport
import app.hovanki.shared.protocol.AdminReports
import app.hovanki.shared.protocol.AdminRevealedEmail
import app.hovanki.shared.protocol.AdminSetRoleRequest
import app.hovanki.shared.protocol.AdminStaff
import app.hovanki.shared.protocol.AdminStaffMember
import app.hovanki.shared.protocol.AdminStats
import app.hovanki.shared.protocol.AdminUserCard
import app.hovanki.shared.protocol.AdminUserRow
import app.hovanki.shared.protocol.AdminUsers
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.ReportAction
import app.hovanki.shared.protocol.ResolveReportRequest
import app.hovanki.shared.protocol.SanctionKind
import app.hovanki.shared.protocol.SanctionRequest
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserRole
import app.hovanki.shared.rules.AccountRules
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.lang.management.ManagementFactory
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * What staff do in the admin (docs/adr/0008-admin.md): reports, accounts, bans and chat bans, games, numbers, staff,
 * the audit log. Every change is written to the audit log in the same transaction. Rules:
 * - admins only: email lookups, showing an email, bans over [AdminLimits.MODERATOR_MAX_BAN_DAYS] days or forever
 *   (and lifting those), logging an account out everywhere, deleting it, ending games, roles, the audit log;
 * - sanctions, nickname resets and deletions only ever hit players: staff lose their role first; nobody acts on
 *   themselves;
 * - every change needs a reason.
 */
@Service
class AdminService(
    private val users: UserRepository,
    private val accounts: AccountService,
    private val sessions: AccountSessionRepository,
    private val reports: ReportRepository,
    private val sanctions: SanctionRepository,
    private val staffRepository: StaffRepository,
    private val queries: AdminQueries,
    private val audit: AuditLog,
    private val games: GameService,
    private val features: FeatureFlags,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)
    private val random = SecureRandom()

    /** Who watches which open game live, until when ([watchGame]); memory only, like the games. */
    private val watching = ConcurrentHashMap<WatchKey, Instant>()

    private data class WatchKey(val staff: UserId, val gameId: GameId)

    // Reports

    fun reports(openOnly: Boolean, before: Long?): AdminReports {
        val page = reports.page(openOnly, before, PAGE_SIZE)
        return AdminReports(toAdmin(page), reports.openCount())
    }

    /** Closes report [id] and every other open report on its message, with [ResolveReportRequest.action] on its author. */
    fun resolveReport(staff: Staff, id: Long, request: ResolveReportRequest): AdminReport {
        val reason = validReason(request.reason)
        val report = reports.find(id) ?: throw GameException(ErrorCode.NOT_FOUND, "No such report")
        if (report.resolvedAt != null) throw GameException(ErrorCode.WRONG_STATE, "The report is already handled")
        val author = if (request.action == ReportAction.DISMISS) {
            null
        } else {
            val authorId = report.reportedUserId?.let(::UserId)
                ?: throw GameException(ErrorCode.WRONG_STATE, "The author played as a guest: only dismiss")
            target(staff, authorId)
        }
        val now = clock.instant()
        val resolution = when (request.action) {
            ReportAction.DISMISS -> "DISMISS"
            ReportAction.MUTE -> "MUTE ${daysText(request.days)}"
            ReportAction.BAN -> "BAN ${daysText(request.days)}"
            ReportAction.RENAME -> "RENAME"
        }
        transactions.executeWithoutResult {
            if (author != null) {
                when (request.action) {
                    ReportAction.DISMISS -> Unit
                    ReportAction.MUTE -> sanction(staff, author, SanctionKind.MUTE, request.days, reason, now)
                    ReportAction.BAN -> sanction(staff, author, SanctionKind.BAN, request.days, reason, now)
                    ReportAction.RENAME -> rename(staff, author, reason, now)
                }
            }
            reports.resolveMessage(report.gameId, report.messageSeq, staff.nickname, "$resolution: $reason", now)
            audit.record(staff, AdminAction.RESOLVE_REPORT, now, author?.id, "report ${report.id}: $resolution", reason)
        }
        return toAdmin(listOf(checkNotNull(reports.find(id)))).single()
    }

    // Accounts

    /** By nickname prefix or exact id. */
    fun searchUsers(query: String): AdminUsers {
        val q = query.trim()
        if (q.isEmpty()) return AdminUsers(emptyList())
        return AdminUsers(rows(users.search(q, PAGE_SIZE)))
    }

    /** By the exact address someone wrote to support from. Admins; logged with the address masked. */
    fun findByEmail(staff: Staff, request: AdminFindByEmailRequest): AdminUsers {
        requireAdmin(staff)
        val reason = validReason(request.reason)
        val email = AccountRules.normalizeEmail(request.email)
        val user = users.findByEmail(email)
        audit.record(staff, AdminAction.FIND_BY_EMAIL, clock.instant(), user?.id, EmailMask.mask(email), reason)
        return AdminUsers(rows(listOfNotNull(user)))
    }

    fun card(userId: UserId): AdminUserCard {
        val user = user(userId)
        val counts = queries.accountCounts(user.id)
        val against = reports.againstAuthors(listOf(user.id))[user.id]?.first ?: 0
        return AdminUserCard(
            id = user.id,
            nickname = user.nickname,
            role = user.role,
            createdAtMillis = user.createdAt.toEpochMilli(),
            language = user.language,
            emailMasked = EmailMask.mask(user.email),
            emailVerified = user.emailVerified,
            saveRoutes = user.saveRoutesSince != null,
            games = counts.games,
            lastGameAtMillis = counts.lastGameAt?.toEpochMilli(),
            devices = counts.devices,
            lastSeenAtMillis = counts.lastSeenAt?.toEpochMilli(),
            friends = counts.friends,
            blockedBy = counts.blockedBy,
            reportsAgainst = against,
            reportsBy = reports.countBy(user.id),
            sanctions = sanctions.of(user.id).map { it.toAdmin() },
            totpEnrolled = user.role.isStaff && staffRepository.totpSecret(user.id) != null,
        )
    }

    /** The whole address. Admins; logged. */
    fun showEmail(staff: Staff, userId: UserId, reason: String): AdminRevealedEmail {
        requireAdmin(staff)
        val why = validReason(reason)
        val user = user(userId)
        audit.record(staff, AdminAction.SHOW_EMAIL, clock.instant(), user.id, reason = why)
        return AdminRevealedEmail(user.email)
    }

    fun ban(staff: Staff, userId: UserId, request: SanctionRequest): AdminUserCard =
        sanctionAndCard(staff, userId, SanctionKind.BAN, request)

    fun mute(staff: Staff, userId: UserId, request: SanctionRequest): AdminUserCard =
        sanctionAndCard(staff, userId, SanctionKind.MUTE, request)

    /** Lifts the active ban or chat ban early. A forever one only admins. */
    fun lift(staff: Staff, userId: UserId, kind: SanctionKind, reason: String): AdminUserCard {
        val why = validReason(reason)
        val user = target(staff, userId)
        val now = clock.instant()
        val active = sanctions.active(user.id, kind, now)
            ?: throw GameException(ErrorCode.WRONG_STATE, "Nothing to lift")
        if (!staff.isAdmin && (active.until == null || !withinModeratorLimit(active.createdAt, active.until))) {
            throw adminsOnly()
        }
        transactions.executeWithoutResult {
            sanctions.lift(user.id, kind, staff.nickname, now)
            val action = if (kind == SanctionKind.BAN) AdminAction.UNBAN else AdminAction.UNMUTE
            audit.record(staff, action, now, user.id, reason = why)
        }
        return card(user.id)
    }

    /** Resets an offensive nickname to a random `player-…` one. */
    fun rename(staff: Staff, userId: UserId, reason: String): AdminUserCard {
        val why = validReason(reason)
        val user = target(staff, userId)
        transactions.executeWithoutResult { rename(staff, user, why, clock.instant()) }
        return card(user.id)
    }

    /** Logs the account out on every device (a stolen account). Admins. */
    fun logoutDevices(staff: Staff, userId: UserId, reason: String): AdminUserCard {
        requireAdmin(staff)
        val why = validReason(reason)
        val user = target(staff, userId)
        transactions.executeWithoutResult {
            sessions.deleteAll(user.id)
            audit.record(staff, AdminAction.LOGOUT_DEVICES, clock.instant(), user.id, reason = why)
        }
        return card(user.id)
    }

    /** Deletes the account and everything of it, on its owner's written request. Admins. */
    fun deleteAccount(staff: Staff, userId: UserId, reason: String) {
        requireAdmin(staff)
        val why = validReason(reason)
        val user = target(staff, userId)
        transactions.executeWithoutResult {
            audit.record(staff, AdminAction.DELETE_ACCOUNT, clock.instant(), user.id, reason = why)
            accounts.deleteAccount(user.id)
        }
    }

    // Games

    fun games(): AdminGames = AdminGames(games.adminGames())

    /** Admins. */
    fun endGame(staff: Staff, gameId: GameId, reason: String) {
        requireAdmin(staff)
        val why = validReason(reason)
        if (!games.endByStaff(gameId)) throw GameException(ErrorCode.NOT_FOUND, "No such game")
        audit.record(staff, AdminAction.END_GAME, clock.instant(), target = gameId.value, reason = why)
    }

    // Server features (docs/adr/0012-nearby-radar.md, docs/adr/0013-quests-sparks-and-sensors.md)

    /** Every feature and whether it is on; staff see, admins switch ([setFeature]). */
    fun features(): AdminFeatures = AdminFeatures(
        ServerFeature.entries.map { feature ->
            val record = features.all().firstOrNull { it?.feature == feature }
            // What the server does: a switch on in the database is off where the server may not have the feature.
            AdminFeature(
                feature,
                features.isEnabled(feature),
                record?.updatedAt?.toEpochMilli(),
                record?.updatedBy,
                shadowOnly = features.isShadowOnly(feature),
            )
        },
    )

    /**
     * Turns a feature on or off for everybody on the server. Admins; the audit log gets it. The field log is turned on
     * only where the server may have it (FeatureFlags.isAllowed, the test server): elsewhere 409.
     */
    fun setFeature(staff: Staff, feature: ServerFeature, request: AdminFeatureRequest): AdminFeatures {
        requireAdmin(staff)
        val why = validReason(request.reason)
        val now = clock.instant()
        transactions.executeWithoutResult {
            features.set(feature, request.enabled, staff.nickname, now)
            audit.record(
                staff,
                AdminAction.SET_FEATURE,
                now,
                target = "${feature.name} ${if (request.enabled) "on" else "off"}",
                reason = why,
            )
        }
        return features()
    }

    /**
     * An admin starts watching open game [gameId] live (docs/adr/0011-spectators-and-recordings.md): with a reason in
     * the audit log, and only a game its host opened to spectators. [liveGame] then serves it to them for
     * [WATCH_GRANT]; watching longer takes another reason.
     */
    fun watchGame(staff: Staff, gameId: GameId, reason: String): AdminLiveGame {
        requireAdmin(staff)
        val why = validReason(reason)
        val live = games.liveView(gameId) ?: throw GameException(ErrorCode.NOT_FOUND, "No such game")
        val now = clock.instant()
        audit.record(staff, AdminAction.WATCH_GAME, now, target = gameId.value, reason = why)
        watching[WatchKey(staff.userId, gameId)] = now.plus(WATCH_GRANT)
        return live
    }

    /** Open game [gameId] right now, for an admin who started watching it ([watchGame]) within [WATCH_GRANT]. */
    fun liveGame(staff: Staff, gameId: GameId): AdminLiveGame {
        requireAdmin(staff)
        val now = clock.instant()
        watching.values.removeIf { !it.isAfter(now) }
        if (!watching.containsKey(WatchKey(staff.userId, gameId))) {
            throw GameException(ErrorCode.FORBIDDEN, "Start watching this game with a reason first")
        }
        return games.liveView(gameId) ?: throw GameException(ErrorCode.NOT_FOUND, "No such game")
    }

    // Numbers

    fun stats(): AdminStats {
        val now = clock.instant()
        val history = queries.historyNumbers(now)
        val live = games.adminGames()
        val memory = Runtime.getRuntime()
        return AdminStats(
            generatedAtMillis = now.toEpochMilli(),
            users = history.users,
            usersNew7d = history.usersNew7d,
            usersNew30d = history.usersNew30d,
            activePlayers1d = history.activePlayers1d,
            activePlayers7d = history.activePlayers7d,
            games1d = history.games1d,
            games7d = history.games7d,
            games30d = history.games30d,
            avgPlayers30d = history.avgPlayers30d,
            avgSearchMinutes30d = history.avgSearchMinutes30d,
            seekersWinRate30d = history.seekersWinRate30d,
            disputes7d = history.disputes7d,
            reports7d = reports.countSince(now.minus(Duration.ofDays(7))),
            reportsOpen = reports.openCount(),
            activeBans = sanctions.countActive(SanctionKind.BAN, now),
            activeMutes = sanctions.countActive(SanctionKind.MUTE, now),
            gamesLive = live.size,
            playersLive = live.sumOf { it.players },
            // Set by the Docker image (server/Dockerfile): the commit it was built from.
            serverVersion = System.getenv("HOVANKI_REVISION")?.take(REVISION_LENGTH) ?: "dev",
            uptimeSeconds = ManagementFactory.getRuntimeMXBean().uptime / 1000,
            heapUsedMb = ((memory.totalMemory() - memory.freeMemory()) / MB).toInt(),
            heapMaxMb = (memory.maxMemory() / MB).toInt(),
        )
    }

    // Staff

    /** Admins. */
    fun staff(staff: Staff): AdminStaff {
        requireAdmin(staff)
        val members = users.staff()
        val ids = members.map { it.id }
        val enrolled = staffRepository.enrolled(ids)
        val logins = staffRepository.lastLogins(ids)
        return AdminStaff(
            members.map {
                AdminStaffMember(it.id, it.nickname, it.role, it.id in enrolled, logins[it.id]?.toEpochMilli())
            },
        )
    }

    /**
     * Makes a player a moderator or a moderator a player (admins; [UserRole.ADMIN] is set only on the server, and admins
     * can't be changed here). A player again loses their admin sessions and authenticator.
     */
    fun setRole(staff: Staff, userId: UserId, request: AdminSetRoleRequest): AdminUserCard {
        requireAdmin(staff)
        val why = validReason(request.reason)
        if (request.role == UserRole.ADMIN) {
            throw GameException(ErrorCode.FORBIDDEN, "Admins are made on the server only (docs/deploy.md)")
        }
        val user = user(userId)
        if (user.id == staff.userId) throw GameException(ErrorCode.FORBIDDEN, "Not on yourself")
        if (user.role ==
            UserRole.ADMIN
        ) {
            throw GameException(ErrorCode.FORBIDDEN, "Admins are changed on the server only")
        }
        val now = clock.instant()
        if (request.role == UserRole.MODERATOR && sanctions.active(user.id, SanctionKind.BAN, now) != null) {
            throw GameException(ErrorCode.WRONG_STATE, "The account is banned")
        }
        transactions.executeWithoutResult {
            users.setRole(user.id, request.role)
            if (request.role == UserRole.PLAYER) {
                staffRepository.deleteSessions(user.id)
                staffRepository.deleteTotp(user.id)
            }
            audit.record(staff, AdminAction.SET_ROLE, now, user.id, request.role.name, why)
        }
        return card(user.id)
    }

    /** A moderator lost their phone: they set up the authenticator again at the next login. Admins. */
    fun resetTotp(staff: Staff, userId: UserId, reason: String): AdminUserCard {
        requireAdmin(staff)
        val why = validReason(reason)
        val user = user(userId)
        if (user.role != UserRole.MODERATOR) {
            throw GameException(ErrorCode.FORBIDDEN, "Only moderators; an admin's is reset on the server")
        }
        transactions.executeWithoutResult {
            staffRepository.deleteTotp(user.id)
            staffRepository.deleteSessions(user.id)
            audit.record(staff, AdminAction.RESET_TOTP, clock.instant(), user.id, reason = why)
        }
        return card(user.id)
    }

    /** Admins. */
    fun audit(staff: Staff, before: Long?): AdminAudit {
        requireAdmin(staff)
        return AdminAudit(audit.page(before, PAGE_SIZE))
    }

    // Helpers

    private fun sanctionAndCard(
        staff: Staff,
        userId: UserId,
        kind: SanctionKind,
        request: SanctionRequest,
    ): AdminUserCard {
        val reason = validReason(request.reason)
        val user = target(staff, userId)
        transactions.executeWithoutResult { sanction(staff, user, kind, request.days, reason, clock.instant()) }
        return card(user.id)
    }

    /** Inside a transaction. A ban also logs the account out everywhere. */
    private fun sanction(staff: Staff, user: UserRecord, kind: SanctionKind, days: Int?, reason: String, now: Instant) {
        if (days != null && days !in 1..AdminLimits.MAX_SANCTION_DAYS) {
            throw GameException(ErrorCode.BAD_REQUEST, "Days: 1..${AdminLimits.MAX_SANCTION_DAYS}, or none for forever")
        }
        if (!staff.isAdmin && (days == null || days > AdminLimits.MODERATOR_MAX_BAN_DAYS)) throw adminsOnly()
        val until = days?.let { now.plus(Duration.ofDays(it.toLong())) }
        sanctions.insert(user.id, kind, reason, staff.nickname, now, until)
        if (kind == SanctionKind.BAN) sessions.deleteAll(user.id)
        val action = if (kind == SanctionKind.BAN) AdminAction.BAN else AdminAction.MUTE
        audit.record(staff, action, now, user.id, daysText(days), reason)
    }

    /** Inside a transaction. */
    private fun rename(staff: Staff, user: UserRecord, reason: String, now: Instant) {
        repeat(RENAME_ATTEMPTS) {
            val nickname =
                "player-" +
                    (1..RANDOM_NICKNAME_LENGTH).map { NICKNAME_ALPHABET[random.nextInt(NICKNAME_ALPHABET.length)] }
                        .joinToString("")
            check(AccountRules.isValidNickname(nickname)) { "Generated nickname is invalid" }
            if (users.findByNickname(nickname) == null && users.updateNickname(user.id, nickname)) {
                audit.record(staff, AdminAction.RENAME, now, user.id, nickname, reason)
                return
            }
        }
        throw GameException(ErrorCode.INTERNAL, "No free nickname found")
    }

    private fun rows(found: List<UserRecord>): List<AdminUserRow> {
        val ids = found.map { it.id }
        val now = clock.instant()
        val banned = sanctions.activeAmong(ids, SanctionKind.BAN, now)
        val muted = sanctions.activeAmong(ids, SanctionKind.MUTE, now)
        return found.map {
            AdminUserRow(it.id, it.nickname, it.role, it.createdAt.toEpochMilli(), it.id in banned, it.id in muted)
        }
    }

    private fun toAdmin(page: List<ReportRecord>): List<AdminReport> {
        val authors = page.mapNotNull { it.reportedUserId?.let(::UserId) }.toSet()
        val reporters = page.mapNotNull { it.reporterUserId?.let(::UserId) }.toSet()
        val names = users.findAll(reporters).associate { it.id to it.nickname }
        val counts = reports.againstAuthors(authors)
        return page.map { report ->
            val author = report.reportedUserId?.let(::UserId)
            val reporter = report.reporterUserId?.let(::UserId)
            AdminReport(
                id = report.id,
                gameId = GameId(report.gameId),
                messageSeq = report.messageSeq,
                text = report.text,
                authorId = author,
                authorName = report.reportedName,
                reporterId = reporter,
                reporterName = reporter?.let(names::get),
                createdAtMillis = report.createdAt.toEpochMilli(),
                resolvedAtMillis = report.resolvedAt?.toEpochMilli(),
                resolvedByName = report.resolvedBy,
                resolution = report.resolution,
                authorReports = author?.let { counts[it]?.first } ?: 0,
                authorReporters = author?.let { counts[it]?.second } ?: 0,
            )
        }
    }

    private fun user(userId: UserId): UserRecord = users.findById(userId)
        ?: throw GameException(ErrorCode.NOT_FOUND, "No such account", ErrorReason.USER_NOT_FOUND)

    /** An account staff may act on: a player, not themselves. */
    private fun target(staff: Staff, userId: UserId): UserRecord {
        val user = user(userId)
        if (user.id == staff.userId) throw GameException(ErrorCode.FORBIDDEN, "Not on yourself")
        if (user.role.isStaff) throw GameException(ErrorCode.FORBIDDEN, "Staff: an admin takes the role away first")
        return user
    }

    private fun withinModeratorLimit(from: Instant, until: Instant) =
        Duration.between(from, until) <= Duration.ofDays(AdminLimits.MODERATOR_MAX_BAN_DAYS.toLong())

    private fun requireAdmin(staff: Staff) {
        if (!staff.isAdmin) throw adminsOnly()
    }

    private fun validReason(reason: String): String {
        val trimmed = reason.trim()
        if (trimmed.isEmpty() || trimmed.length > AdminLimits.REASON_MAX_LENGTH) {
            throw GameException(
                ErrorCode.BAD_REQUEST,
                "A reason is required: 1..${AdminLimits.REASON_MAX_LENGTH} characters",
            )
        }
        return trimmed
    }

    private companion object {
        /** How long a reason covers watching an open game live. */
        val WATCH_GRANT: Duration = Duration.ofMinutes(30)

        const val PAGE_SIZE = 50
        const val REVISION_LENGTH = 12
        const val MB = 1024 * 1024
        const val RENAME_ATTEMPTS = 5
        const val RANDOM_NICKNAME_LENGTH = 6
        const val NICKNAME_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"

        fun daysText(days: Int?) = if (days == null) "forever" else "${days}d"

        fun adminsOnly() = GameException(ErrorCode.FORBIDDEN, "Admins only")
    }
}
