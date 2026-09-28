package app.hovanki.client.session

import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.totp.CatchCodePayload
import app.hovanki.shared.totp.catchCodeTotp

/** The code a hider shows (QR or 4 digits) while a seeker's claim awaits it. */
data class CatchCode(val code: String, val millisUntilNext: Long)

/**
 * The code the viewer has to show right now, or null unless the viewer is a hider with a claim awaiting the code.
 * Computed locally from the secret and [serverNowMillis] (use [ServerClock.now]): works while the network is down,
 * and a wrong phone clock can't produce codes the server rejects.
 */
fun GameSnapshot.catchCodeToShow(serverNowMillis: Long): CatchCode? {
    catches.firstOrNull { it.hiderId == me.playerId && it.status == CatchStatus.AWAITING_CODE } ?: return null
    return codeAt(serverNowMillis)
}

/**
 * The code a hider can show without a claim («My code»): the seeker scans it and the catch is confirmed in one step.
 * Null unless the viewer is a hider still playing in the seeking phase. Computed like [catchCodeToShow].
 */
fun GameSnapshot.myCatchCode(serverNowMillis: Long): CatchCode? {
    if (phase != GamePhase.SEEKING || me.role != Role.HIDER || me.status != PlayerStatus.ACTIVE) return null
    return codeAt(serverNowMillis)
}

/** The text of the viewer's QR code for [code] ([CatchCodePayload]). */
fun GameSnapshot.catchQr(code: CatchCode): String = CatchCodePayload(gameId, me.playerId, code.code).encode()

/**
 * What the seeker's camera read, when it is the QR code of a hider of this game who can still be caught; null for
 * anything else (another game, a caught player, not a catch code at all). Whether the code is right is the server's to
 * say.
 */
fun GameSnapshot.catchableScan(text: String): CatchCodePayload? {
    val payload = CatchCodePayload.decode(text) ?: return null
    if (payload.gameId != gameId) return null
    val hider = players.firstOrNull { it.id == payload.hiderId } ?: return null
    return payload.takeIf { hider.role == Role.HIDER && hider.status == PlayerStatus.ACTIVE }
}

private fun GameSnapshot.codeAt(serverNowMillis: Long): CatchCode? {
    val secret = me.catchCodeSecret ?: return null
    val totp = catchCodeTotp(secret, settings.rules)
    return CatchCode(totp.codeAt(serverNowMillis), totp.millisUntilNextCode(serverNowMillis))
}
