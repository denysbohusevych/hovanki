package app.hovanki.client.share

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController

/**
 * `UIActivityViewController` over whatever is on screen. On an iPad it is a popover and needs an anchor: the middle
 * of the screen.
 */
class IosShareSheet : ShareSheet {
    override fun share(text: String) {
        presentShareSheet(listOf(text))
    }
}

/** The system «Share» with [items] (texts, file URLs); [onDone] when it closes, shared or not. */
@OptIn(ExperimentalForeignApi::class)
internal fun presentShareSheet(items: List<Any>, onDone: (() -> Unit)? = null) {
    val presenter = topViewController() ?: return
    val sheet = UIActivityViewController(activityItems = items, applicationActivities = null)
    if (onDone != null) sheet.completionWithItemsHandler = { _, _, _, _ -> onDone() }
    sheet.popoverPresentationController?.let { popover ->
        val view = presenter.view
        popover.sourceView = view
        view.bounds.useContents { popover.sourceRect = CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0) }
        popover.permittedArrowDirections = 0u
    }
    presenter.presentViewController(sheet, animated = true, completion = null)
}

private fun topViewController(): UIViewController? {
    val windows = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
    val window = windows.firstOrNull { it.keyWindow } ?: windows.firstOrNull()
    var top = window?.rootViewController ?: return null
    while (true) top = top.presentedViewController ?: return top
}
