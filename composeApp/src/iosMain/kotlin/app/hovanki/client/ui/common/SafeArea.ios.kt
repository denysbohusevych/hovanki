package app.hovanki.client.ui.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIKeyboardDidHideNotification
import platform.UIKit.UIKeyboardDidShowNotification
import platform.UIKit.UIViewController

@Composable
internal actual fun platformSafeDrawing(): WindowInsets {
    val safeDrawing = WindowInsets.safeDrawing
    val window by IosSafeArea.insets.collectAsState()
    return remember(safeDrawing, window) {
        safeDrawing.union(WindowInsets(window.left.dp, window.top.dp, window.right.dp, window.bottom.dp))
    }
}

/** The window's safe area over the app's root view, in points (a point is a dp on iOS). */
internal data class SafeAreaPoints(
    val top: Double = 0.0,
    val left: Double = 0.0,
    val bottom: Double = 0.0,
    val right: Double = 0.0,
)

/**
 * The safe area as the window has it, measured around the app's root view: what Compose's own `safeDrawing` should
 * say, and after a return from the background sometimes doesn't (all zero). Measured again whenever the app becomes
 * active and the keyboard comes or goes, a few times while the layout settles.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosSafeArea {
    private val mutableInsets = MutableStateFlow(SafeAreaPoints())
    val insets: StateFlow<SafeAreaPoints> = mutableInsets.asStateFlow()

    private val scope = MainScope()
    private var root: UIViewController? = null
    private var measuring: Job? = null

    /** [controller] shows the whole app (`mainViewController`); call once, on the main thread. */
    fun attach(controller: UIViewController) {
        root = controller
        val center = NSNotificationCenter.defaultCenter
        val moments = listOf(
            UIApplicationDidBecomeActiveNotification,
            UIKeyboardDidShowNotification,
            UIKeyboardDidHideNotification,
        )
        for (name in moments) {
            center.addObserverForName(name, `object` = null, queue = NSOperationQueue.mainQueue) { _ -> measureSoon() }
        }
        measureSoon()
    }

    private fun measureSoon() {
        measuring?.cancel()
        measuring = scope.launch {
            for (wait in MEASURE_DELAYS_MILLIS) {
                delay(wait)
                measure()
            }
        }
    }

    /** How far the window's safe area reaches into the root view on each side; nothing until the view is shown. */
    private fun measure() {
        val view = root?.viewIfLoaded ?: return
        val window = view.window ?: return
        val frame = view.convertRect(view.bounds, toView = null)
        val x = frame.useContents { origin.x }
        val y = frame.useContents { origin.y }
        val width = frame.useContents { size.width }
        val height = frame.useContents { size.height }
        val windowWidth = window.bounds.useContents { size.width }
        val windowHeight = window.bounds.useContents { size.height }
        mutableInsets.value = window.safeAreaInsets.useContents {
            SafeAreaPoints(
                top = (top - y).coerceAtLeast(0.0),
                left = (left - x).coerceAtLeast(0.0),
                bottom = (y + height - (windowHeight - bottom)).coerceAtLeast(0.0),
                right = (x + width - (windowWidth - right)).coerceAtLeast(0.0),
            )
        }
    }

    private val MEASURE_DELAYS_MILLIS = listOf(0L, 300L, 1_000L)
}
