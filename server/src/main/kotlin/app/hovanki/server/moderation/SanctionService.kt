package app.hovanki.server.moderation

import app.hovanki.server.game.GameException
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.SanctionKind
import app.hovanki.shared.protocol.UserId
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * What bans and chat bans stop (docs/adr/0008-admin.md): a banned account can't log in (so no game with it either:
 * the ban also deletes its sessions) and can't chat; a chat ban stops the chat only. Guests have no account and can't
 * be banned.
 */
@Service
class SanctionService(private val sanctions: SanctionRepository, private val clock: Clock) {
    /** Throws 403 [ErrorReason.ACCOUNT_BANNED] with the end of the ban. Call only after the password was right. */
    fun checkNotBanned(userId: UserId) {
        val ban = sanctions.active(userId, SanctionKind.BAN, clock.instant()) ?: return
        throw GameException(
            ErrorCode.FORBIDDEN,
            "This account is banned",
            ErrorReason.ACCOUNT_BANNED,
            untilMillis = ban.until?.toEpochMilli(),
        )
    }

    /** Throws 403 [ErrorReason.CHAT_MUTED] if [userId] is banned or may not chat, with when that ends. */
    fun checkCanChat(userId: UserId) {
        val now = clock.instant()
        val sanction = sanctions.active(userId, SanctionKind.BAN, now)
            ?: sanctions.active(userId, SanctionKind.MUTE, now)
            ?: return
        throw GameException(
            ErrorCode.FORBIDDEN,
            "You may not write in the chat",
            ErrorReason.CHAT_MUTED,
            untilMillis = sanction.until?.toEpochMilli(),
        )
    }
}
