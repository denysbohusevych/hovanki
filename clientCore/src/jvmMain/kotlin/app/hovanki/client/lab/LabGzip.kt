package app.hovanki.client.lab

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

actual fun gzipOrNull(bytes: ByteArray): ByteArray? = try {
    val out = ByteArrayOutputStream(bytes.size / 4 + 64)
    GZIPOutputStream(out).use { it.write(bytes) }
    out.toByteArray()
} catch (_: IOException) {
    null
}
