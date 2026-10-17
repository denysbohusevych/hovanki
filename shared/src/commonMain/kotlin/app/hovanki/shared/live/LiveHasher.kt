package app.hovanki.shared.live

import com.squareup.wire.ProtoAdapter
import com.squareup.wire.ProtoWriter
import okio.Buffer

/**
 * The xxHash64 of a message's protobuf bytes, encoded into one buffer kept for many messages: [LiveDelta] hashes every
 * section and every part of every player on each sync, and `ProtoAdapter.encode(value)` would make a writer, a buffer
 * and a byte array for each (docs/live-v2/server.md §3.5). The same bytes, so the same hash. Not thread safe: one per
 * delta.
 */
internal class LiveHasher {
    private val buffer = Buffer()
    private val writer = ProtoWriter(buffer)
    private val cursor = Buffer.UnsafeCursor()

    /** The size in bytes of the message [hash] encoded last: 0 is a message at its zero. */
    var size: Long = 0
        private set

    fun <T> hash(adapter: ProtoAdapter<T>, value: T): Long {
        adapter.encode(writer, value)
        size = buffer.size
        if (size == 0L) return EMPTY
        var hash = 0L
        var inOneSegment = false
        buffer.readUnsafe(cursor)
        try {
            cursor.seek(0)
            val length = cursor.end - cursor.start
            // One segment (8 KB) holds every message but a big board's JSON: hashed where it lies.
            inOneSegment = length.toLong() == size
            if (inOneSegment) hash = XxHash64.hash(cursor.data!!, cursor.start, length)
        } finally {
            cursor.close()
        }
        if (!inOneSegment) hash = XxHash64.hash(buffer.readByteArray())
        // Empty after every hash: its segment goes back to okio's pool at once, not with the hasher to the garbage.
        buffer.clear()
        return hash
    }

    private companion object {
        val EMPTY = XxHash64.hash(ByteArray(0))
    }
}
