package app.hovanki.client.share

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIPasteboard
import platform.UIKit.UIPasteboardOptionExpirationDate

/**
 * Instagram's `instagram-stories://share` with the picture on the pasteboard as the story's background (Meta's
 * documented contract; `LSApplicationQueriesSchemes` in Info.plist lets `canOpenURL` see it), else the system «Share»
 * with the picture and [caption]. Written without a build.
 */
class IosStoryShare(private val facebookAppId: String) : StoryShare {
    override fun share(png: ByteArray, caption: String) {
        val data = png.toNSData()
        val app = UIApplication.sharedApplication
        val stories = NSURL.URLWithString("instagram-stories://share?source_application=$facebookAppId")
        if (facebookAppId.isNotEmpty() && stories != null && app.canOpenURL(stories)) {
            UIPasteboard.generalPasteboard.setItems(
                listOf(mapOf<Any?, Any>(BACKGROUND_IMAGE to data)),
                options = mapOf<Any?, Any>(
                    UIPasteboardOptionExpirationDate to NSDate.dateWithTimeIntervalSinceNow(PASTEBOARD_SECONDS),
                ),
            )
            app.openURL(stories, options = emptyMap<Any?, Any>(), completionHandler = null)
            return
        }
        val image = UIImage.imageWithData(data) ?: return
        presentShareSheet(listOf(image, caption))
    }

    private companion object {
        const val BACKGROUND_IMAGE = "com.instagram.sharedSticker.backgroundImage"

        /** The picture leaves the pasteboard after five minutes. */
        const val PASTEBOARD_SECONDS = 300.0
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }

internal actual fun ImageBitmap.encodePng(): ByteArray =
    Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes ?: ByteArray(0)
