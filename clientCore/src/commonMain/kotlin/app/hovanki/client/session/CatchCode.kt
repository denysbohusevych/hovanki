package app.hovanki.client.session

import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.totp.catchCodeTotp

/** The code a hider shows (QR or 4 digits) while a seeker's claim awaits it. */
data class CatchCode(val code: String, val millisUntilNext: Long)

/**
 * The code the viewer has to show right now, or null unless the viewer is a hider with a claim awaiting the code.
 * Computed locally from the secret and [serverNowMillis] (use [ServerClock.now]): works while the network is down,
 * and a wrong phone clock can't produce codes the server rejects.
 */
fun GameSnapshot.catchCodeToShow(serverNowMillis: Long): CatchCode? {
    val secret = me.catchCodeSecret ?: return null
    catches.firstOrNull { it.hiderId == me.playerId && it.status == CatchStatus.AWAITING_CODE } ?: return null
    val totp = catchCodeTotp(secret, settings.rules)
    return CatchCode(totp.codeAt(serverNowMillis), totp.millisUntilNextCode(serverNowMillis))
}
