package app.hovanki.client.catchcode

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.floor

/**
 * [text] as a QR code: dark modules in [color] on [background], with the light quiet zone of 4 modules around it
 * that cameras need. Square: give it a square size. Modules snap to whole pixels, so the edges stay sharp.
 */
@Composable
fun QrCodeImage(
    text: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Black,
    background: Color = Color.White,
) {
    val code = remember(text) { QrCode.encode(text) }
    Canvas(modifier = modifier.semantics { this.contentDescription = contentDescription }) {
        val modules = code.size + 2 * QUIET_ZONE_MODULES
        val side = size.minDimension
        val module = floor(side / modules).coerceAtLeast(1f)
        val origin = Offset((size.width - module * modules) / 2, (size.height - module * modules) / 2)
        drawRect(background, topLeft = Offset.Zero, size = size)
        val cell = Size(module, module)
        for (y in 0 until code.size) {
            for (x in 0 until code.size) {
                if (!code[x, y]) continue
                drawRect(
                    color = color,
                    topLeft = Offset(
                        origin.x + (x + QUIET_ZONE_MODULES) * module,
                        origin.y + (y + QUIET_ZONE_MODULES) * module,
                    ),
                    size = cell,
                )
            }
        }
    }
}

private const val QUIET_ZONE_MODULES = 4
