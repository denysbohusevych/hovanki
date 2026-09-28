package app.hovanki.shared.rules

import app.hovanki.shared.protocol.GameId

/**
 * What the QR code of a scan checkpoint says (docs/adr/0011-quests-sparks-and-sensors.md, section 2.4):
 * `hovanki:cp:<gameId>:<code>`. The host prints it from the lobby and hangs it up; the player's scanner sends the code
 * to the server, which checks it and that the player stands there.
 */
data class CheckpointPayload(val gameId: GameId, val code: String) {
    fun encode(): String = listOf(PREFIX, KIND, gameId.value, code).joinToString(SEPARATOR)

    companion object {
        private const val PREFIX = "hovanki"
        private const val KIND = "cp"
        private const val SEPARATOR = ":"

        fun decode(text: String): CheckpointPayload? {
            val parts = text.trim().split(SEPARATOR)
            if (parts.size != 4 || parts[0] != PREFIX || parts[1] != KIND) return null
            if (parts[2].isEmpty() || parts[3].isEmpty()) return null
            return CheckpointPayload(GameId(parts[2]), parts[3])
        }
    }
}
