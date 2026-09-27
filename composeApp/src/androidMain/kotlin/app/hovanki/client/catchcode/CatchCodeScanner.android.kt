package app.hovanki.client.catchcode

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/** CameraX preview of the back camera, each frame's luminance decoded by ZXing (QR codes only). */
@Composable
actual fun CatchCodeScanner(onScanned: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val permission = Manifest.permission.CAMERA
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    var refused by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        refused = !allowed
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(permission) }
    when {
        granted -> CameraPreview(onScanned, modifier)
        refused -> NoCameraNotice(modifier)
        else -> Box(modifier = modifier.background(Color.Black))
    }
}

@Composable
private fun CameraPreview(onScanned: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScanned by rememberUpdatedState(onScanned)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    AndroidView(factory = { previewView }, modifier = modifier)
    DisposableEffect(lifecycleOwner) {
        val analyzerThread = Executors.newSingleThreadExecutor()
        val mainThread = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        providerFuture.addListener(
            {
                if (disposed) return@addListener
                val cameraProvider = providerFuture.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(
                    analyzerThread,
                    QrAnalyzer { text -> mainThread.execute { if (!disposed) currentOnScanned(text) } },
                )
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (e: IllegalArgumentException) {
                    // No back camera (some emulators): the preview stays black, the digits still work.
                }
            },
            mainThread,
        )
        onDispose {
            disposed = true
            provider?.unbindAll()
            analyzerThread.shutdown()
        }
    }
}

/** Decodes QR codes from the luminance (Y) plane of the camera frames. */
private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining()).also { data -> buffer.get(data) }
            val source = PlanarYUVLuminanceSource(
                bytes,
                plane.rowStride,
                it.height,
                0,
                0,
                it.width,
                it.height,
                false,
            )
            val text = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (e: ReaderException) {
                null
            } finally {
                reader.reset()
            }
            text?.let(onText)
        }
    }
}
