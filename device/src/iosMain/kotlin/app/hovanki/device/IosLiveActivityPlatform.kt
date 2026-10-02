package app.hovanki.device

import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationWillResignActiveNotification

/**
 * The iPhone's side of the round's Live Activity ([RoundLiveActivity], the field build): `willResignActive` observed
 * on the main queue, so the start runs while iOS still counts the app as on the screen, and the file of silence the
 * alerts play. Written without an iOS build at hand.
 */
class IosLiveActivityPlatform : LiveActivityPlatform {
    override fun onWillResignActive(block: () -> Unit): () -> Unit {
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(
            UIApplicationWillResignActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> block() }
        return { center.removeObserver(observer) }
    }

    override fun prepareSilentSound(): Boolean = writeSilentSound()
}
