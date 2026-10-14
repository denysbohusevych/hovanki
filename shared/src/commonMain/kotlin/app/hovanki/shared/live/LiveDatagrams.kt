package app.hovanki.shared.live

/**
 * AES-128-GCM with one key (docs/live-v2/spec.md §5.2): the platform's own, `javax.crypto` on the server and Android,
 * CryptoKit through a Swift bridge on iOS. [open] returns null for anything that does not authenticate.
 */
interface LiveAead {
    fun seal(nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray

    fun open(nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray?
}

/**
 * The UDP datagrams of v2, byte by byte (docs/live-v2/spec.md §5.1): DATA (an encrypted message or a fragment of one)
 * and RESET (the session is gone). The header's numbers are big-endian; the header is the AAD.
 */
object LiveDatagrams {
    const val TYPE_DATA: Byte = 0x21
    const val TYPE_RESET: Byte = 0x22
    const val SESSION_ID_BYTES = 8
    const val RESET_TAG_BYTES = 16
    const val RESET_BYTES = 1 + SESSION_ID_BYTES + RESET_TAG_BYTES
    const val KIND_MESSAGE: Byte = 0x01
    const val KIND_FRAGMENT: Byte = 0x02
    private const val NONCE_BYTES = 12

    /** The smallest DATA datagram the server answers with a RESET: then the RESET is smaller than the question. */
    const val MIN_DATA_FOR_RESET = LiveLimits.HEADER_BYTES + LiveLimits.TAG_BYTES + 12

    fun header(sessionId: Long, pn: Int): ByteArray {
        val header = ByteArray(LiveLimits.HEADER_BYTES)
        header[0] = TYPE_DATA
        putLong(header, 1, sessionId)
        putInt(header, 9, pn)
        return header
    }

    /** 8 zero bytes and the packet number: each direction has its own key, so a number never repeats a nonce. */
    fun nonce(pn: Int): ByteArray = ByteArray(NONCE_BYTES).also { putInt(it, 8, pn) }

    /** A DATA datagram with [plaintext] (a payload: [message] or one of [fragments]). */
    fun seal(aead: LiveAead, sessionId: Long, pn: Int, plaintext: ByteArray): ByteArray {
        require(plaintext.size <= LiveLimits.MAX_PLAINTEXT_BYTES) { "Plaintext too big: ${plaintext.size}" }
        val header = header(sessionId, pn)
        return header + aead.seal(nonce(pn), header, plaintext)
    }

    /** A datagram's type, session and packet number: before anything is decrypted. Null: not a v2 datagram. */
    class Head(val type: Byte, val sessionId: Long, val pn: Int)

    fun head(datagram: ByteArray): Head? = when {
        datagram.isEmpty() -> null

        datagram[0] == TYPE_DATA && datagram.size >= LiveLimits.HEADER_BYTES + LiveLimits.TAG_BYTES ->
            Head(TYPE_DATA, getLong(datagram, 1), getInt(datagram, 9))

        datagram[0] == TYPE_RESET && datagram.size == RESET_BYTES -> Head(TYPE_RESET, getLong(datagram, 1), 0)

        else -> null
    }

    /** The plaintext of a DATA datagram, or null: it does not authenticate with [aead]. */
    fun open(aead: LiveAead, datagram: ByteArray): ByteArray? {
        val head = head(datagram) ?: return null
        if (head.type != TYPE_DATA) return null
        val header = datagram.copyOfRange(0, LiveLimits.HEADER_BYTES)
        val body = datagram.copyOfRange(LiveLimits.HEADER_BYTES, datagram.size)
        return aead.open(nonce(head.pn), header, body)
    }

    /** The server's RESET for [sessionId]: the tag is `HMAC-SHA256(reset_secret, session_id)`, its first 16 bytes. */
    fun reset(sessionId: Long, resetTag: ByteArray): ByteArray {
        require(resetTag.size == RESET_TAG_BYTES)
        val datagram = ByteArray(RESET_BYTES)
        datagram[0] = TYPE_RESET
        putLong(datagram, 1, sessionId)
        resetTag.copyInto(datagram, 1 + SESSION_ID_BYTES)
        return datagram
    }

    /** The phone: [datagram] is the RESET of its session ([resetToken] from the open). Compared in constant time. */
    fun isReset(datagram: ByteArray, sessionId: Long, resetToken: ByteArray): Boolean {
        if (datagram.size != RESET_BYTES || datagram[0] != TYPE_RESET ||
            resetToken.size != RESET_TAG_BYTES
        ) {
            return false
        }
        if (getLong(datagram, 1) != sessionId) return false
        var diff = 0
        for (i in 0 until RESET_TAG_BYTES) {
            diff =
                diff or (datagram[1 + SESSION_ID_BYTES + i].toInt() xor resetToken[i].toInt())
        }
        return diff == 0
    }

    /** A whole message in one datagram's plaintext. */
    fun message(bytes: ByteArray): ByteArray {
        require(bytes.size <= LiveLimits.MAX_MESSAGE_IN_DATAGRAM) { "Message too big for one datagram: ${bytes.size}" }
        return byteArrayOf(KIND_MESSAGE) + bytes
    }

    /**
     * The plaintexts of one message: itself when it fits a datagram, else its fragments under [messageId] (§5.4).
     * A message over [LiveLimits.MAX_FRAGMENTS] fragments is the server's bug: [IllegalArgumentException].
     */
    fun plaintexts(bytes: ByteArray, messageId: Int): List<ByteArray> {
        if (bytes.size <= LiveLimits.MAX_MESSAGE_IN_DATAGRAM) return listOf(message(bytes))
        val count = (bytes.size + LiveLimits.MAX_FRAGMENT_DATA - 1) / LiveLimits.MAX_FRAGMENT_DATA
        require(count <= LiveLimits.MAX_FRAGMENTS) { "Message too big: ${bytes.size} bytes" }
        return List(count) { index ->
            val from = index * LiveLimits.MAX_FRAGMENT_DATA
            val to = minOf(bytes.size, from + LiveLimits.MAX_FRAGMENT_DATA)
            val fragment = ByteArray(5 + to - from)
            fragment[0] = KIND_FRAGMENT
            fragment[1] = (messageId ushr 8).toByte()
            fragment[2] = messageId.toByte()
            fragment[3] = index.toByte()
            fragment[4] = count.toByte()
            bytes.copyInto(fragment, 5, from, to)
            fragment
        }
    }

    internal fun putLong(to: ByteArray, at: Int, value: Long) {
        for (i in 0 until 8) to[at + i] = (value ushr (56 - 8 * i)).toByte()
    }

    internal fun putInt(to: ByteArray, at: Int, value: Int) {
        for (i in 0 until 4) to[at + i] = (value ushr (24 - 8 * i)).toByte()
    }

    internal fun getLong(from: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (from[at + i].toLong() and 0xff)
        return value
    }

    internal fun getInt(from: ByteArray, at: Int): Int {
        var value = 0
        for (i in 0 until 4) value = (value shl 8) or (from[at + i].toInt() and 0xff)
        return value
    }
}

/**
 * Puts messages together from the plaintexts of datagrams (§5.4): a whole message comes out at once, fragments once
 * the last one of a message came. At most [LiveLimits.MAX_REASSEMBLIES] messages at a time, each for at most
 * [LiveLimits.FRAGMENT_TIMEOUT_MILLIS]; a lost fragment loses its message (the protocol mends itself). Not thread safe.
 */
class LiveReassembler {
    private class Partial(val messageId: Int, val count: Int, val startedAtMillis: Long) {
        val pieces = arrayOfNulls<ByteArray>(count)
        var received = 0
    }

    private val partials = ArrayList<Partial>(LiveLimits.MAX_REASSEMBLIES)

    /** The message [plaintext] completes, if any. Malformed plaintexts are dropped. */
    fun accept(plaintext: ByteArray, nowMillis: Long): ByteArray? {
        partials.removeAll { nowMillis - it.startedAtMillis > LiveLimits.FRAGMENT_TIMEOUT_MILLIS }
        if (plaintext.isEmpty()) return null
        return when (plaintext[0]) {
            LiveDatagrams.KIND_MESSAGE -> plaintext.copyOfRange(1, plaintext.size)
            LiveDatagrams.KIND_FRAGMENT -> fragment(plaintext, nowMillis)
            else -> null
        }
    }

    private fun fragment(plaintext: ByteArray, nowMillis: Long): ByteArray? {
        if (plaintext.size < 6) return null
        val messageId = ((plaintext[1].toInt() and 0xff) shl 8) or (plaintext[2].toInt() and 0xff)
        val index = plaintext[3].toInt() and 0xff
        val count = plaintext[4].toInt() and 0xff
        if (count < 2 || count > LiveLimits.MAX_FRAGMENTS || index >= count) return null
        var partial = partials.firstOrNull { it.messageId == messageId }
        if (partial != null && partial.count != count) {
            partials.remove(partial)
            partial = null
        }
        if (partial == null) {
            if (partials.size >= LiveLimits.MAX_REASSEMBLIES) partials.removeAt(0)
            partial = Partial(messageId, count, nowMillis).also { partials += it }
        }
        if (partial.pieces[index] != null) return null
        partial.pieces[index] = plaintext.copyOfRange(5, plaintext.size)
        partial.received++
        if (partial.received < count) return null
        partials.remove(partial)
        val size = partial.pieces.sumOf { it!!.size }
        val message = ByteArray(size)
        var at = 0
        for (piece in partial.pieces) {
            piece!!.copyInto(message, at)
            at += piece.size
        }
        return message
    }
}

/**
 * The receiver's window of packet numbers (§5.3, after RFC 9147 §4.5.1): a number above the highest seen, or one of
 * the [LiveLimits.REPLAY_WINDOW] below it not seen yet, passes once. Checked after the datagram decrypted.
 */
class LiveReplayWindow {
    /** The highest number seen; 0: none yet (numbers start at 1). */
    var highest: Long = 0
        private set
    private var seen = 0L

    /** True once per number: [pn] is new and now marked. */
    fun accept(pn: Int): Boolean {
        val number = pn.toLong() and 0xffffffffL
        if (number == 0L) return false
        if (number > highest) {
            val shift = number - highest
            seen = if (shift >= LiveLimits.REPLAY_WINDOW) 0L else seen shl shift.toInt()
            // Bit 0 is the highest itself; bit k is highest - k.
            seen = seen or 1L
            highest = number
            return true
        }
        val back = highest - number
        if (back >= LiveLimits.REPLAY_WINDOW) return false
        val bit = 1L shl back.toInt()
        if (seen and bit != 0L) return false
        seen = seen or bit
        return true
    }
}
