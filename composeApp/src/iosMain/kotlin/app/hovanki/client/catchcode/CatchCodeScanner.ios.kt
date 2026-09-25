package app.hovanki.client.catchcode

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// TODO(QR): camera scanning with AVFoundation (AVCaptureMetadataOutput, QR type), calling onScanned with the QR text.
@Composable
actual fun CatchCodeScanner(onScanned: (String) -> Unit, modifier: Modifier) {
    ScannerPlaceholder(modifier)
}
