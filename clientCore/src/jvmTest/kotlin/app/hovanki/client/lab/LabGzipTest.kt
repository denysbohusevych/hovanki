package app.hovanki.client.lab

import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LabGzipTest {
    @Test
    fun theUploadsGzipComesBackAsItWas() {
        val jsonl = (1..1_000).joinToString("") { "{\"seq\":$it,\"k\":\"tick\"}\n" }.encodeToByteArray()
        val gzipped = assertNotNull(gzipOrNull(jsonl))
        assertTrue(gzipped.size < jsonl.size / 4, "${gzipped.size} of ${jsonl.size}")
        assertContentEquals(jsonl, GZIPInputStream(gzipped.inputStream()).use { it.readBytes() })
    }
}
