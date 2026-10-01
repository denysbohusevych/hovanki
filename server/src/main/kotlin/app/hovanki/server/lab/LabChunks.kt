package app.hovanki.server.lab

import app.hovanki.shared.lab.LabFields
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** The lab's chunks as bytes: gzip both ways, and a device's chunks put together into its log. */
internal object LabChunks {
    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** The bytes of [input], at most [limit] of them; null when there are more (the rest is never read). */
    fun read(input: InputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** The unpacked bytes; null when they are more than [limit] bytes. Throws [IOException] on what is no gzip. */
    fun gunzip(bytes: ByteArray, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream(minOf(limit, bytes.size * 4L).toInt())
        GZIPInputStream(bytes.inputStream()).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > limit) return null
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    /**
     * A device's log from its [chunks], line by line: every event once, in the order of `seq`; a chunk that overlaps
     * the ones before it (a retried upload cut differently) adds only its events after them. The bodies are read one
     * chunk at a time ([body]), as the lines are taken, so a whole log is never in memory. [after]: only the events
     * after this `seq` (the live report reads what came since it last looked).
     */
    fun lines(chunks: List<LabChunk>, body: (LabChunk) -> ByteArray, after: Long = Long.MIN_VALUE): Sequence<String> =
        sequence {
            var last = after
            for (chunk in chunks.sortedBy { it.seqFrom }) {
                if (chunk.seqTo <= last) continue
                val bytes = body(chunk)
                if (bytes.isNotEmpty()) {
                    GZIPInputStream(bytes.inputStream()).bufferedReader(Charsets.UTF_8).useLines { lines ->
                        val kept = lines.filter { it.isNotBlank() }
                        val seen = last
                        yieldAll(if (chunk.seqFrom > seen) kept else kept.filter { (seqOf(it) ?: MIN) > seen })
                    }
                }
                last = chunk.seqTo
            }
        }

    /** Writes a device's log ([lines]) to [out]. */
    fun write(chunks: List<LabChunk>, body: (LabChunk) -> ByteArray, out: OutputStream) {
        for (line in lines(chunks, body)) {
            out.write(line.toByteArray(Charsets.UTF_8))
            out.write('\n'.code)
        }
    }

    private fun seqOf(line: String): Long? = runCatching {
        ((Json.parseToJsonElement(line) as? JsonObject)?.get(LabFields.SEQ) as? JsonPrimitive)?.longOrNull
    }.getOrNull()

    private const val BUFFER = 64 * 1024
    private const val MIN = Long.MIN_VALUE
}
