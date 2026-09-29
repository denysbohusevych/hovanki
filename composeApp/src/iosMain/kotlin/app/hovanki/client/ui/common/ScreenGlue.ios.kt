package app.hovanki.client.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeZoneWithName
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIApplication
import platform.UIKit.UIScreen

@Composable
actual fun KeepScreenBright() {
    DisposableEffect(Unit) {
        val screen = UIScreen.mainScreen
        val previous = screen.brightness
        UIApplication.sharedApplication.idleTimerDisabled = true
        screen.brightness = 1.0
        onDispose {
            screen.brightness = previous
            UIApplication.sharedApplication.idleTimerDisabled = false
        }
    }
}

@Composable
actual fun rememberReduceMotion(): Boolean = remember { UIAccessibilityIsReduceMotionEnabled() }

@OptIn(ExperimentalComposeUiApi::class)
actual fun plainTextClipEntry(text: String): ClipEntry = ClipEntry.withPlainText(text)

actual fun formatDateTime(epochMillis: Long): String = format(epochMillis, withTime = true)

actual fun formatDate(epochMillis: Long): String = format(epochMillis, withTime = false)

actual fun formatDateTimeIn(epochMillis: Long, timeZone: String): String =
    format(epochMillis, withTime = true, timeZone = NSTimeZone.timeZoneWithName(timeZone))

private fun format(epochMillis: Long, withTime: Boolean, timeZone: NSTimeZone? = null): String {
    val formatter = NSDateFormatter()
    formatter.dateStyle = NSDateFormatterMediumStyle
    formatter.timeStyle = if (withTime) NSDateFormatterShortStyle else NSDateFormatterNoStyle
    if (timeZone != null) formatter.timeZone = timeZone
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))
}
