package app.hovanki.shared.live

import kotlin.test.Test
import kotlin.test.assertEquals

/** xxHash64 with seed 0 against the reference (python-xxhash 3, `xxh64(data).intdigest()`). */
class XxHash64Test {
    @Test
    fun matchesTheReference() {
        val cases =
            listOf(
                ByteArray(0) to "ef46db3751d8e999",
                "a".encodeToByteArray() to "d24ec4f1a98c6e5b",
                "abc".encodeToByteArray() to "44bc2cf5ad770999",
                "hovanki".encodeToByteArray() to "8c4f066a0b835315",
                ByteArray(31) { it.toByte() } to "c346d2b59b4d8ee1",
                ByteArray(32) { it.toByte() } to "cbf59c5116ff32b4",
                ByteArray(100) { it.toByte() } to "6ac1e58032166597",
                ByteArray(768) { (it % 256).toByte() } to "8e03c838c596036f",
            )
        for ((bytes, expected) in cases) {
            assertEquals(expected, XxHash64.hash(bytes).toULong().toString(16).padStart(16, '0'), "${bytes.size} bytes")
        }
    }

    @Test
    fun hashesARangeOfABiggerArray() {
        val bytes = ByteArray(40) { it.toByte() }
        assertEquals(XxHash64.hash(bytes.copyOfRange(3, 35)), XxHash64.hash(bytes, offset = 3, length = 32))
    }
}
