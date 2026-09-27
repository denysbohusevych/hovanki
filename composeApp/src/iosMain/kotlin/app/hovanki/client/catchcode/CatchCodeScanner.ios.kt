@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.client.catchcode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

/** The back camera through AVFoundation; the metadata output reports QR codes. */
@Composable
actual fun CatchCodeScanner(onScanned: (String) -> Unit, modifier: Modifier) {
    var status by remember { mutableStateOf(AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) }
    LaunchedEffect(Unit) {
        if (status == AVAuthorizationStatusNotDetermined) {
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { _ ->
                dispatch_async(dispatch_get_main_queue()) {
                    status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
                }
            }
        }
    }
    when (status) {
        AVAuthorizationStatusAuthorized -> CameraPreview(onScanned, modifier)
        AVAuthorizationStatusNotDetermined -> Box(modifier = modifier.background(Color.Black))
        else -> NoCameraNotice(modifier)
    }
}

@Composable
private fun CameraPreview(onScanned: (String) -> Unit, modifier: Modifier) {
    val currentOnScanned by rememberUpdatedState(onScanned)
    val scanner = remember { QrScanner { text -> currentOnScanned(text) } }
    DisposableEffect(scanner) {
        scanner.start()
        onDispose { scanner.stop() }
    }
    UIKitView(
        factory = { scanner.view },
        modifier = modifier,
        // Only a picture: touches stay with the Compose buttons over it.
        properties = UIKitInteropProperties(isInteractive = false, isNativeAccessibilityEnabled = false),
    )
}

/**
 * A capture session from the back camera to a metadata output for QR codes, shown by [view]. Without a camera (the
 * simulator) the view stays black.
 */
private class QrScanner(private val onText: (String) -> Unit) :
    NSObject(),
    AVCaptureMetadataOutputObjectsDelegateProtocol {
    private val session = AVCaptureSession()
    val view = PreviewView(session)

    init {
        val camera = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
        val input = camera?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, null) }
        if (input != null && session.canAddInput(input)) {
            session.addInput(input)
            val output = AVCaptureMetadataOutput()
            if (session.canAddOutput(output)) {
                session.addOutput(output)
                output.setMetadataObjectsDelegate(this, dispatch_get_main_queue())
                // Only after the output joined the session: before, it supports no types at all.
                output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
            }
        }
    }

    /** Starting blocks for a moment: off the main thread. */
    fun start() {
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0UL)) {
            session.startRunning()
        }
    }

    fun stop() {
        session.stopRunning()
    }

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        didOutputMetadataObjects
            .filterIsInstance<AVMetadataMachineReadableCodeObject>()
            .firstNotNullOfOrNull { it.stringValue }
            ?.let(onText)
    }
}

/** A view whose preview layer follows its size. */
private class PreviewView(session: AVCaptureSession) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val preview = AVCaptureVideoPreviewLayer(session = session).apply {
        videoGravity = AVLayerVideoGravityResizeAspectFill
    }

    init {
        layer.addSublayer(preview)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        preview.frame = bounds
    }
}
