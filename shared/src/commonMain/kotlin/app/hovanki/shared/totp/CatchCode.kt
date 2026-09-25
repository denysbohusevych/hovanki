package app.hovanki.shared.totp

import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.PlayerId

/** Catch-code generator/verifier for a hider, built identically on the client and the server. */
fun catchCodeTotp(secretHex: String, rules: GameRules): Totp = Totp(
    secret = secretHex.hexToBytes(),
    periodSeconds = rules.catchCodePeriodSeconds,
    digits = rules.catchCodeDigits,
)

/**
 * Content of the QR code on the hider's screen: `hovanki:1:<gameId>:<playerId>:<code>`.
 * The same digits can be read out loud and typed in when the camera does not work.
 */
data class CatchCodePayload(val gameId: GameId, val hiderId: PlayerId, val code: String) {
    fun encode(): String = listOf(PREFIX, VERSION, gameId.value, hiderId.value, code).joinToString(SEPARATOR)

    companion object {
        private const val PREFIX = "hovanki"
        private const val VERSION = "1"
        private const val SEPARATOR = ":"

        fun decode(text: String): CatchCodePayload? {
            val parts = text.trim().split(SEPARATOR)
            if (parts.size != 5 || parts[0] != PREFIX || parts[1] != VERSION) return null
            if (parts.drop(2).any { it.isEmpty() }) return null
            return CatchCodePayload(GameId(parts[2]), PlayerId(parts[3]), parts[4])
        }
    }
}
