package app.hovanki.client.catchcode

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.scanner_placeholder
import org.jetbrains.compose.resources.stringResource

/**
 * Camera preview that reads the QR code on the hider's screen and reports its raw text
 * (parse it with [app.hovanki.shared.totp.CatchCodePayload.decode]).
 * Typing the 4 digits by hand is a full-fledged alternative, not just a fallback.
 */
@Composable
expect fun CatchCodeScanner(onScanned: (String) -> Unit, modifier: Modifier)

/** Shown by the platform scanners until camera scanning is implemented. */
@Composable
internal fun ScannerPlaceholder(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().height(96.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(Res.string.scanner_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
