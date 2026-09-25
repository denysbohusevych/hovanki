package app.hovanki.server.game

import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.totp.toHex
import org.springframework.stereotype.Component
import java.security.SecureRandom

@Component
class IdGenerator {
    private val random = SecureRandom()

    fun gameId() = GameId(randomString(ID_LENGTH, ID_ALPHABET))

    fun playerId() = PlayerId(randomString(ID_LENGTH, ID_ALPHABET))

    fun catchId() = CatchId(randomString(ID_LENGTH, ID_ALPHABET))

    /** Short code players type or share to join; no 0/O/1/I to avoid confusion. */
    fun joinCode(): String = randomString(JOIN_CODE_LENGTH, JOIN_CODE_ALPHABET)

    fun token(): String = randomBytes(32).toHex()

    /** 160-bit TOTP secret, as recommended by RFC 4226. */
    fun catchCodeSecret(): String = randomBytes(20).toHex()

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    private fun randomString(length: Int, alphabet: String) =
        buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }

    private companion object {
        const val ID_LENGTH = 12
        const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        const val JOIN_CODE_LENGTH = 6
        const val JOIN_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
