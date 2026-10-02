package app.hovanki.device

import platform.UIKit.UIApplication

/**
 * The iPhone's auto-lock (`UIApplication.idleTimerDisabled`) shared by everyone who turns it off: the catch code's
 * bright screen (`KeepScreenBright` of the app) and the screen by the proximity sensor (`lab.IosLabScreen`: the lab's
 * and the field build's round). Off while anyone [hold]s it, on again once the last one lets go, so one letting go
 * never locks the other's screen. Main thread.
 */
object IosIdleTimer {
    private val holders = HashSet<Any>()

    /** [holder] wants the auto-lock off ([off] true) or no longer ([off] false). */
    fun hold(holder: Any, off: Boolean) {
        if (off) holders += holder else holders -= holder
        UIApplication.sharedApplication.idleTimerDisabled = holders.isNotEmpty()
    }
}
