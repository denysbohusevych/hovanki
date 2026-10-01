@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.hovanki.device

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionReasonAppWasSuspended
import platform.AVFAudio.AVAudioSessionInterruptionReasonBuiltInMicMuted
import platform.AVFAudio.AVAudioSessionInterruptionReasonDefault
import platform.AVFAudio.AVAudioSessionInterruptionReasonKey
import platform.AVFAudio.AVAudioSessionInterruptionReasonRouteDisconnected
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionMediaServicesWereResetNotification
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonCategoryChange
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonNewDeviceAvailable
import platform.AVFAudio.AVAudioSessionRouteChangeReasonNoSuitableRouteForCategory
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOverride
import platform.AVFAudio.AVAudioSessionRouteChangeReasonRouteConfigurationChange
import platform.AVFAudio.AVAudioSessionRouteChangeReasonWakeFromSleep
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.dataWithBytes
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObjectProtocol

/**
 * The background modes of an iPhone for the radio lab (docs/radar-run.md, §5.1 and §5.3; ADR 0017 §2.3, `mode.*`).
 * Debug builds only (`audio` is in the background modes of the Debug Info.plist only, `debug-info-plist.sh`); written
 * without an iOS build at hand. Everything on the main thread.
 *
 * - `mode.audio`: the shared audio session as `playback` mixing with others, and a player looping a file of silence,
 *   so iOS keeps the app running on the lock and Core Haptics on that session (`pulse.core_haptics.audio`) may keep
 *   playing. Interruptions (a call, Siri, an alarm) and route changes go to [events]; after an interruption the player
 *   is started again.
 * - `mode.notification_wake`: a local notification every [NOTIFICATION_WAKE_MILLIS], scheduled ahead with time
 *   triggers, so they come while the app is suspended too (a loop in the app would stop with it); the loop only tops
 *   the queue up and reports each one as it becomes due.
 * - `mode.live_activity`: the round's Live Activity through [liveActivity], started when the app is about to leave the
 *   screen (iOS grants one only to an app on the screen), updated once a minute.
 */
class IosBackgroundModes(private val liveActivity: LiveActivityHost) : BackgroundModes {
    private val scope = MainScope()
    private val events = MutableSharedFlow<ModeEvent>(extraBufferCapacity = 64)
    private val center = NSNotificationCenter.defaultCenter

    override val available: Set<String>
        get() = buildSet {
            add(ModeIds.AUDIO)
            add(ModeIds.NOTIFICATION_WAKE)
            if (liveActivity.isAvailable) add(ModeIds.LIVE_ACTIVITY)
        }

    // mode.audio
    private var audioMode = false
    private var player: AVAudioPlayer? = null
    private var audioObservers: List<NSObjectProtocol> = emptyList()

    // mode.notification_wake
    private var wakeJob: Job? = null

    // mode.live_activity
    private var resignObserver: NSObjectProtocol? = null
    private var liveJob: Job? = null
    private var liveStarted = false

    override fun events(): Flow<ModeEvent> = events.asSharedFlow()

    override fun set(id: String, on: Boolean): ModeResult = when (id) {
        ModeIds.AUDIO -> if (on) audioOn() else audioOff()
        ModeIds.NOTIFICATION_WAKE -> if (on) wakeOn() else wakeOff()
        ModeIds.LIVE_ACTIVITY -> if (on) liveOn() else liveOff()
        else -> ModeResult(false, "unknown mode")
    }

    override fun stopAll() {
        ModeIds.ALL.forEach { set(it, on = false) }
    }

    // --- mode.audio ---

    private fun audioOn(): ModeResult {
        if (audioMode) return ModeResult(true)
        val error = startAudio()
        if (error != null) {
            player?.stop()
            player = null
            deactivateAudio()
            return ModeResult(false, error)
        }
        audioMode = true
        audioObservers = listOf(
            observe(AVAudioSessionInterruptionNotification, ::onInterruption),
            observe(AVAudioSessionRouteChangeNotification) { note ->
                emit(ModeIds.AUDIO, "route_change", routeChangeReason(note?.number(AVAudioSessionRouteChangeReasonKey)))
            },
            observe(AVAudioSessionMediaServicesWereResetNotification) { _ ->
                // The media services restarted: every session and player is gone and has to be made again.
                player = null
                emit(ModeIds.AUDIO, "media_services_reset", startAudio()?.let { "restart failed: $it" })
            },
        )
        return ModeResult(true)
    }

    /** Sets the session up and starts the silent loop; the error when a step failed. */
    private fun startAudio(): String? = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val session = AVAudioSession.sharedInstance()
        val configured = session.setCategory(
            AVAudioSessionCategoryPlayback,
            mode = AVAudioSessionModeDefault,
            options = AVAudioSessionCategoryOptionMixWithOthers,
            error = error.ptr,
        )
        if (!configured) return@memScoped "category: ${error.value?.localizedDescription}"
        if (!session.setActive(true, error = error.ptr)) {
            return@memScoped "activate: ${error.value?.localizedDescription}"
        }
        val bytes = silentWav(seconds = 1.0)
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        val made = AVAudioPlayer(data = data, error = error.ptr)
        error.value?.let { return@memScoped "player: ${it.localizedDescription}" }
        made.numberOfLoops = -1
        // The file is silence anyway; the session keeps the app alive while the player plays, whatever it plays.
        made.volume = 0f
        made.prepareToPlay()
        if (!made.play()) return@memScoped "play failed"
        player = made
        null
    }

    private fun audioOff(): ModeResult {
        if (!audioMode) return ModeResult(true)
        audioMode = false
        audioObservers.forEach { center.removeObserver(it) }
        audioObservers = emptyList()
        player?.stop()
        player = null
        val error = deactivateAudio()
        // Still in use elsewhere (the audio haptics' engine) fails the deactivation: the loop is off all the same.
        return ModeResult(true, error)
    }

    /** Lets other apps' audio go on; the error when iOS refused. */
    private fun deactivateAudio(): String? = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val options = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
        if (AVAudioSession.sharedInstance().setActive(false, withOptions = options, error = error.ptr)) {
            null
        } else {
            "deactivate: ${error.value?.localizedDescription}"
        }
    }

    private fun onInterruption(note: NSNotification?) {
        val began = note?.number(AVAudioSessionInterruptionTypeKey) == AVAudioSessionInterruptionTypeBegan
        if (began) {
            val reason = interruptionReason(note.number(AVAudioSessionInterruptionReasonKey))
            emit(ModeIds.AUDIO, "interruption_began", reason)
            return
        }
        val options = note?.number(AVAudioSessionInterruptionOptionKey) ?: 0uL
        val shouldResume = options and AVAudioSessionInterruptionOptionShouldResume != 0uL
        // The mode wants the app alive, so it plays again even when iOS doesn't suggest it; the log says which.
        val resumed = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            AVAudioSession.sharedInstance().setActive(true, error = error.ptr) && player?.play() == true
        }
        val reason = (if (shouldResume) "should_resume" else "no_resume") +
            if (resumed) ", resumed" else ", resume failed"
        emit(ModeIds.AUDIO, "interruption_ended", reason)
    }

    // --- mode.notification_wake ---

    private fun wakeOn(): ModeResult {
        if (wakeJob != null) return ModeResult(true)
        val startMillis = nowMillis()
        wakeJob = scope.launch {
            requestLabNotifications()
            var scheduled = 0 // wakes 1..scheduled are in iOS's queue
            var reported = 0 // wakes 1..reported were emitted
            while (isActive) {
                val now = nowMillis()
                while (dueMillis(startMillis, scheduled + 1) <= now + NOTIFICATION_AHEAD_MILLIS) {
                    scheduled++
                    schedule(scheduled, dueMillis(startMillis, scheduled) - now)
                }
                while (reported < scheduled && dueMillis(startMillis, reported + 1) <= now) {
                    reported++
                    // Late: the app was suspended when it came; iOS delivered it on time all the same.
                    val late = (now - dueMillis(startMillis, reported)) / 1000
                    val reason = "wake $reported" + if (late > LATE_SECONDS) ", seen ${late}s late" else ""
                    emit(ModeIds.NOTIFICATION_WAKE, "notification_sent", reason)
                }
                delay(NOTIFICATION_WAKE_MILLIS)
            }
        }
        return ModeResult(true)
    }

    private fun dueMillis(startMillis: Long, wake: Int): Long = startMillis + wake * NOTIFICATION_WAKE_MILLIS

    /** Wake [number] in [inMillis], without a sound: the lit lock screen is what the mode tries. */
    private fun schedule(number: Int, inMillis: Long) {
        if (inMillis <= 0) return
        val content = UNMutableNotificationContent()
        content.setTitle("Hovanki lab")
        content.setBody("wake $number")
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(inMillis / 1000.0, repeats = false)
        val request = UNNotificationRequest.requestWithIdentifier("$WAKE_PREFIX$number", content, trigger)
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) { error ->
            if (error != null) emit(ModeIds.NOTIFICATION_WAKE, "notification_failed", error.localizedDescription)
        }
    }

    private fun wakeOff(): ModeResult {
        val job = wakeJob ?: return ModeResult(true)
        job.cancel()
        wakeJob = null
        val notifications = UNUserNotificationCenter.currentNotificationCenter()
        notifications.getPendingNotificationRequestsWithCompletionHandler { requests ->
            val ids = requests.orEmpty().mapNotNull { (it as? UNNotificationRequest)?.identifier }.filter(::isWake)
            notifications.removePendingNotificationRequestsWithIdentifiers(ids)
        }
        notifications.getDeliveredNotificationsWithCompletionHandler { delivered ->
            val ids = delivered.orEmpty().mapNotNull { (it as? UNNotification)?.request?.identifier }.filter(::isWake)
            notifications.removeDeliveredNotificationsWithIdentifiers(ids)
        }
        return ModeResult(true)
    }

    private fun isWake(identifier: String): Boolean = identifier.startsWith(WAKE_PREFIX)

    // --- mode.live_activity ---

    private fun liveOn(): ModeResult {
        if (!liveActivity.isAvailable) return ModeResult(false, "no live activity host: add HovankiLive in Xcode")
        if (resignObserver != null) return ModeResult(true)
        resignObserver = observe(UIApplicationWillResignActiveNotification) { _ -> startLiveActivity() }
        return ModeResult(true)
    }

    private var liveText = "the run is on"
    private var liveBand = 0
    private var liveDetail = ""
    private var liveEndsAtMillis = 0L

    override fun liveStatus(text: String, band: Int, detail: String, endsAtMillis: Long) {
        if (text == liveText && band == liveBand && detail == liveDetail && endsAtMillis == liveEndsAtMillis) return
        liveText = text
        liveBand = band
        liveDetail = detail
        liveEndsAtMillis = endsAtMillis
        if (liveStarted) {
            liveActivity.update(text, band, detail, endsAtMillis)
            emit(ModeIds.LIVE_ACTIVITY, "live_activity_updated", "status")
        }
    }

    private fun startLiveActivity() {
        if (liveStarted) return
        liveStarted = liveActivity.start("Hovanki lab", liveText)
        if (!liveStarted) {
            // Live Activities off in the settings, or iOS thinks the app already left the screen.
            emit(ModeIds.LIVE_ACTIVITY, "live_activity_refused", "the host refused: Live Activities off?")
            return
        }
        emit(ModeIds.LIVE_ACTIVITY, "live_activity_started")
        liveActivity.update(liveText, liveBand, liveDetail, liveEndsAtMillis)
        liveJob = scope.launch {
            while (isActive) {
                delay(LIVE_UPDATE_MILLIS)
                // The minute's heartbeat keeps the card's time honest; the band and the step come by [liveStatus].
                liveActivity.update(liveText, liveBand, liveDetail.ifEmpty { clock() }, liveEndsAtMillis)
                emit(ModeIds.LIVE_ACTIVITY, "live_activity_updated")
            }
        }
    }

    private fun liveOff(): ModeResult {
        resignObserver?.let { center.removeObserver(it) }
        resignObserver = null
        liveJob?.cancel()
        liveJob = null
        if (liveStarted) {
            liveActivity.end()
            liveStarted = false
            emit(ModeIds.LIVE_ACTIVITY, "live_activity_ended")
        }
        return ModeResult(true)
    }

    // --- helpers ---

    private fun observe(name: String?, block: (NSNotification?) -> Unit): NSObjectProtocol =
        center.addObserverForName(name, `object` = null, queue = NSOperationQueue.mainQueue, usingBlock = block)

    private fun emit(mode: String, event: String, reason: String? = null) {
        events.tryEmit(ModeEvent(mode, event, reason))
    }

    private companion object {
        const val NOTIFICATION_WAKE_MILLIS = 20_000L

        /** How far ahead the wakes are queued: iOS keeps at most 64 pending notifications of an app. */
        const val NOTIFICATION_AHEAD_MILLIS = 10 * 60_000L
        const val WAKE_PREFIX = "hovanki.lab.wake."
        const val LATE_SECONDS = 2
        const val LIVE_UPDATE_MILLIS = 60_000L

        fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

        /** The wall clock's «HH:MM» in UTC for the activity's text: enough to see it's being updated. */
        fun clock(): String {
            val minutes = nowMillis() / 60_000 % (24 * 60)
            return "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')} UTC"
        }
    }
}

/** A number from the notification's user info (`NSNumber` there), as the unsigned enums of AVFAudio are. */
private fun NSNotification.number(key: String?): ULong? = (userInfo?.get(key) as? NSNumber)?.unsignedLongValue

private fun interruptionReason(reason: ULong?): String? = when (reason) {
    null -> null
    AVAudioSessionInterruptionReasonDefault -> "default"
    AVAudioSessionInterruptionReasonAppWasSuspended -> "app_was_suspended"
    AVAudioSessionInterruptionReasonBuiltInMicMuted -> "built_in_mic_muted"
    AVAudioSessionInterruptionReasonRouteDisconnected -> "route_disconnected"
    else -> "unknown ($reason)"
}

private fun routeChangeReason(reason: ULong?): String? = when (reason) {
    null -> null
    AVAudioSessionRouteChangeReasonNewDeviceAvailable -> "new_device"
    AVAudioSessionRouteChangeReasonOldDeviceUnavailable -> "old_device_gone"
    AVAudioSessionRouteChangeReasonCategoryChange -> "category_change"
    AVAudioSessionRouteChangeReasonOverride -> "override"
    AVAudioSessionRouteChangeReasonWakeFromSleep -> "wake_from_sleep"
    AVAudioSessionRouteChangeReasonNoSuitableRouteForCategory -> "no_suitable_route"
    AVAudioSessionRouteChangeReasonRouteConfigurationChange -> "configuration_change"
    else -> "unknown ($reason)"
}
