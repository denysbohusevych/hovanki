package app.hovanki.client.catchcode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.scanner_no_camera
import app.hovanki.client.ui.theme.Palette
import org.jetbrains.compose.resources.stringResource

/**
 * Camera preview that reads the QR code on the hider's screen and reports its raw text, possibly many times
 * (parse it with [app.hovanki.shared.totp.CatchCodePayload.decode]). Asks for the camera permission itself; without
 * it, says so. Typing the 4 digits by hand is a full-fledged alternative, not just a fallback.
 */
@Composable
expect fun CatchCodeScanner(onScanned: (String) -> Unit, modifier: Modifier)

/** The camera is not allowed (or there is none): the digits are the way. */
@Composable
internal fun NoCameraNotice(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Palette.Ink), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(Res.string.scanner_no_camera),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}
