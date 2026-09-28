package app.hovanki.shared.qr

import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.totp.CatchCodePayload
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Mode
import com.google.zxing.qrcode.decoder.Version
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.google.zxing.qrcode.encoder.Encoder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Cross-checks [QrCode] with ZXing: its reader decodes our symbols without correcting a single error, and its
 * encoder builds the very same symbols (version, mask, every module) from the same bytes.
 */
class QrCodeZxingTest {
    private val catchCode = CatchCodePayload(GameId("k3v9x0qz7m2a"), PlayerId("p0w8d4n1c6ye"), "042137").encode()

    @Test
    fun zxingReadsTheCatchCode() {
        for (ecc in QrEcc.entries) {
            val code = QrCode.encode(catchCode, ecc)
            assertDecodes(catchCode, code)
            // Also through ZXing's detector (finders, alignment pattern, perspective), not only the pure-barcode path.
            assertEquals(catchCode, QRCodeReader().decode(code.render()).text, "detector at $ecc")
        }
    }

    @Test
    fun zxingReadsShortAndNonAsciiTexts() {
        val texts = listOf("", "a", "0", "hovanki", "Привет", "Привет, мир! Ховайся 🙈", "日本語のテキスト")
        for (ecc in QrEcc.entries) {
            for (text in texts) assertDecodes(text, QrCode.encode(text, ecc))
        }
    }

    @Test
    fun zxingReadsFullSymbolsOfEachLevelAndVersion() {
        for (ecc in QrEcc.entries) {
            for (version in listOf(1, 2, 5, 7, 10, 20, 40)) {
                val text = lowercaseText(QrCode.byteCapacity(version, ecc), seed = version)
                val code = QrCode.encode(text, ecc)
                assertEquals(version, code.version, "${text.length} bytes at $ecc")
                assertDecodes(text, code)
            }
        }
    }

    @Test
    fun sameSymbolsAsTheZxingEncoderForEveryVersionAndLevel() {
        for (ecc in QrEcc.entries) {
            for (version in 1..40) {
                // Full, and a little shorter so that terminator and pad codewords show up too.
                val capacity = QrCode.byteCapacity(version, ecc)
                for (length in setOf(capacity, maxOf(capacity - 3, 0))) {
                    assertSameAsZxing(lowercaseText(length, seed = version * 31 + length), ecc)
                }
            }
        }
    }

    @Test
    fun sameSymbolsAsTheZxingEncoderForCatchCodes() {
        val random = Random(42)
        repeat(50) {
            val payload = CatchCodePayload(
                GameId(randomId(random)),
                PlayerId(randomId(random)),
                (0 until 6).joinToString("") { random.nextInt(10).toString() },
            ).encode()
            for (ecc in QrEcc.entries) assertSameAsZxing(payload, ecc)
        }
        for (text in listOf("", "a", "hovanki", "hovanki:1:abc123def456:zyx987wvu654:0421")) {
            for (ecc in QrEcc.entries) assertSameAsZxing(text, ecc)
        }
    }

    @Test
    fun everyMaskLikeZxing() {
        for (ecc in QrEcc.entries) {
            for (text in listOf(catchCode, lowercaseText(QrCode.byteCapacity(7, ecc), seed = 7))) {
                for (mask in 0..7) {
                    val ours = QrCode.encodeBytes(text.encodeToByteArray(), ecc, forcedMask = mask)
                    val zxing = Encoder.encode(text, ecc.zxing, mapOf(EncodeHintType.QR_MASK_PATTERN to mask))
                    assertSameModules(zxing.matrix, ours, "mask $mask, ${text.length} bytes at $ecc")
                    assertDecodes(text, ours)
                }
            }
        }
    }

    @Test
    fun choosesTheSmallestVersionLikeZxing() {
        for (ecc in QrEcc.entries) {
            for (version in 1..39) {
                // One byte more than a version holds needs the next one.
                val text = lowercaseText(QrCode.byteCapacity(version, ecc) + 1, seed = version)
                val zxing = Encoder.encode(text, ecc.zxing)
                assertEquals(Mode.BYTE, zxing.mode)
                assertEquals(version + 1, zxing.version.versionNumber, "ZXing, ${text.length} bytes at $ecc")
                assertEquals(version + 1, QrCode.encode(text, ecc).version, "${text.length} bytes at $ecc")
            }
        }
    }

    @Test
    fun tablesMatchZxing() {
        for (version in 1..40) {
            val zxing = Version.getVersionForNumber(version)
            assertContentEquals(zxing.alignmentPatternCenters, alignmentPatternCenters(version), "version $version")
            for (ecc in QrEcc.entries) {
                val dataCodewords = zxing.totalCodewords - zxing.getECBlocksForLevel(ecc.zxing).totalECCodewords
                val headerBits = 4 + if (version <= 9) 8 else 16
                assertEquals(
                    (dataCodewords * 8 - headerBits) / 8,
                    QrCode.byteCapacity(version, ecc),
                    "capacity of version $version at $ecc",
                )
            }
        }
    }

    /** Same bytes in byte mode: [text] must contain a lowercase letter so that ZXing picks byte mode too. */
    private fun assertSameAsZxing(text: String, ecc: QrEcc) {
        val ours = QrCode.encode(text, ecc)
        // Without a character set hint ZXing encodes ISO-8859-1 without an ECI header: the same bytes for ASCII.
        val zxing = Encoder.encode(text, ecc.zxing)
        val what = "${text.length} bytes at $ecc"
        assertEquals(Mode.BYTE, zxing.mode, what)
        assertEquals(zxing.version.versionNumber, ours.version, what)
        assertEquals(zxing.maskPattern, ours.mask, what)
        assertSameModules(zxing.matrix, ours, what)
    }

    private fun assertSameModules(zxing: ByteMatrix, ours: QrCode, what: String) {
        assertEquals(zxing.width, ours.size, what)
        for (y in 0 until ours.size) {
            for (x in 0 until ours.size) {
                assertEquals(zxing[x, y].toInt() == 1, ours[x, y], "module ($x, $y), $what")
            }
        }
    }

    private fun assertDecodes(text: String, code: QrCode) {
        val what = "${text.encodeToByteArray().size} bytes, version ${code.version}, ${code.ecc}"
        val result = QRCodeReader().decode(code.render(), mapOf(DecodeHintType.PURE_BARCODE to true))
        assertEquals(text, result.text, what)
        assertEquals(code.ecc.zxing.name, result.metadata(ResultMetadataType.ERROR_CORRECTION_LEVEL), what)
        assertEquals(0, result.metadata(ResultMetadataType.ERRORS_CORRECTED), "no codeword needed a fix, $what")
    }

    private fun Result.metadata(type: ResultMetadataType): Any? = resultMetadata?.get(type)

    /** Black on white, [scale] pixels per module, with a quiet zone of [border] modules. */
    private fun QrCode.render(scale: Int = 4, border: Int = 4): BinaryBitmap {
        val width = (size + 2 * border) * scale
        val pixels = IntArray(width * width) { i ->
            val x = i % width / scale - border
            val y = i / width / scale - border
            if (x in 0 until size && y in 0 until size && this[x, y]) BLACK else WHITE
        }
        return BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, width, pixels)))
    }

    private val QrEcc.zxing: ErrorCorrectionLevel
        get() = when (this) {
            QrEcc.LOW -> ErrorCorrectionLevel.L
            QrEcc.MEDIUM -> ErrorCorrectionLevel.M
            QrEcc.QUARTILE -> ErrorCorrectionLevel.Q
            QrEcc.HIGH -> ErrorCorrectionLevel.H
        }

    private fun randomId(random: Random) = String(CharArray(12) { ID_ALPHABET[random.nextInt(ID_ALPHABET.length)] })

    /** [length] ASCII characters that start with lowercase letters (so ZXing encodes them in byte mode too). */
    private fun lowercaseText(length: Int, seed: Int): String {
        val random = Random(seed)
        return String(CharArray(length) { if (it < PREFIX.length) PREFIX[it] else TEXT_ALPHABET.random(random) })
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val PREFIX = "hovanki:"
        const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        const val TEXT_ALPHABET = "$ID_ALPHABET:-_ .,ABCXYZ"
    }
}
