@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.hovanki.radar.uwb

import app.hovanki.radar.PeerRange
import app.hovanki.radar.PrecisionRadio
import app.hovanki.radar.RangeTrace
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.UwbPeer
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.Vector128
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSKeyedArchiver
import platform.Foundation.NSKeyedUnarchiver
import platform.Foundation.NSThread
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.NearbyInteraction.NIDiscoveryToken
import platform.NearbyInteraction.NIErrorCodeResourceUsageTimeout
import platform.NearbyInteraction.NIErrorCodeSessionFailed
import platform.NearbyInteraction.NINearbyObject
import platform.NearbyInteraction.NINearbyObjectDistanceNotAvailable
import platform.NearbyInteraction.NINearbyObjectRemovalReason
import platform.NearbyInteraction.NINearbyPeerConfiguration
import platform.NearbyInteraction.NISession
import platform.NearbyInteraction.NISessionDelegateProtocol
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.PI
import kotlin.math.atan2

/**
 * `uwb.ni` on an iPhone (ADR 0017 §2.3, docs/radar-run.md step 5.3): Nearby Interaction with another iPhone, metres
 * and a direction, in the radio lab for now.
 *
 * Nearby Interaction pairs sessions, not phones: each `NISession` has a discovery token of its own, and two phones
 * range when each runs a session configured with the token of the other's. The run on the server carries one token
 * per device ([token], `LabRunStateView.uwbTokens`), so this radio keeps one session, the home session whose token
 * the run knows, and ranges with one peer: the first of [range]'s peers by label. Every other peer is traced as a
 * `config` with the error and skipped: a session of its own would have a token nobody hears of. Two iPhones, the
 * question of step 5, range with each other; with three, A and B range and C, which picks A, does not.
 *
 * The home session is made by [prepare] (or the first [range]) and its token archived with `NSKeyedArchiver`, base64.
 * A session iOS invalidates takes its token with it: [token] goes null, and after a failure it may recover from
 * ([NIErrorCodeSessionFailed], [NIErrorCodeResourceUsageTimeout]: the ones after a long suspension) the radio makes a
 * new session once, [RERUN_MILLIS] later, while ranging: a new token the caller passes on to the run again. After
 * the others (the user said no, a configuration iOS refuses, too many sessions) it stays off until [prepare] is called
 * again. A suspension's end and a peer's timeout run the same configuration again, as Apple recommends.
 *
 * Everything runs on the main thread: the flow is collected there and the sessions call their delegates on the main
 * queue. A session holds its delegate only weakly: this radio keeps both. Written without an iOS build at hand: the
 * first run on two iPhones is part of the spike (docs/radar-run.md §5.3).
 */
class IosPrecisionRadio(private val trace: RangeTrace = RangeTrace.None) : PrecisionRadio {
    /** The chip: iPhone 11 and later, not the SE; false on the simulator. */
    override val isSupported: Boolean
        get() = NISession.deviceCapabilities.supportsPreciseDistanceMeasurement

    private val mutableToken = MutableStateFlow<String?>(null)
    override val token: StateFlow<String?> = mutableToken

    private var home: Home? = null

    /** The peer the home session ranges with and its configuration (run again after a suspension). */
    private var peer: UwbPeer? = null
    private var configuration: NINearbyPeerConfiguration? = null

    /** The collector of [range]: its scope takes the readings and the delayed reruns. Null while nobody ranges. */
    private var ranging: CoroutineScope? = null
    private var onRange: ((PeerRange) -> Unit)? = null

    /** A reading came since the last configuration (`running` traced). */
    private var running = false

    /** A new session was made after an invalidation and no reading came since: no second one. */
    private var recovered = false

    /** Peers traced as skipped (label and token), so each is noted once. */
    private val skipped = mutableSetOf<Pair<String, String>>()

    override fun prepare() {
        if (NSThread.isMainThread) session() else dispatch_async(dispatch_get_main_queue()) { session() }
    }

    override fun range(peers: StateFlow<List<UwbPeer>>): Flow<PeerRange> = callbackFlow {
        ranging = this
        onRange = { trySend(it) }
        session()
        val peersJob = peers.onEach(::onPeers).launchIn(this)
        awaitClose {
            peersJob.cancel()
            ranging = null
            onRange = null
            peer = null
            configuration = null
            skipped.clear()
            // Paused, not invalidated: the session and its token stay for the next time.
            home?.session?.pause()
            trace.range("stop")
        }
    }.flowOn(Dispatchers.Main)

    /** The home session; made (and its token published) when there is none and the phone has the chip. */
    private fun session(): Home? {
        home?.let { return it }
        if (!isSupported) return null
        val delegate = SessionDelegate(
            onUpdate = ::onUpdate,
            onRemove = ::onRemove,
            onSuspended = { session -> if (isHome(session)) trace.range("suspended", peer?.label) },
            onSuspensionEnded = ::onSuspensionEnded,
            onInvalidated = ::onInvalidated,
        )
        val session = NISession()
        session.delegate = delegate
        session.delegateQueue = dispatch_get_main_queue()
        val token = session.discoveryToken?.let(::archive)
        if (token == null) {
            trace.range("session_start", error = "no discovery token")
            session.invalidate()
            return null
        }
        return Home(session, delegate).also {
            home = it
            mutableToken.value = token
            trace.range("session_start")
        }
    }

    private fun onPeers(peers: List<UwbPeer>) {
        val sorted = peers.filter { it.token.isNotBlank() }.sortedBy { it.label }
        val first = sorted.firstOrNull()
        for (other in sorted.drop(1)) {
            if (skipped.add(other.label to other.token)) {
                trace.range("config", other.label, error = "one peer per session: ranging with ${first?.label}")
            }
        }
        if (first == peer) return
        peer = first
        if (first == null) {
            configuration = null
            home?.session?.pause()
        } else {
            configure(first)
        }
    }

    private fun configure(peer: UwbPeer) {
        val home = session() ?: return trace.range("config", peer.label, error = "no session: no UWB here")
        val token = unarchive(peer.token)
            ?: return trace.range("config", peer.label, error = "the peer's token did not unarchive")
        val configuration = NINearbyPeerConfiguration(token)
        this.configuration = configuration
        running = false
        home.session.runWithConfiguration(configuration)
        trace.range("config", peer.label)
    }

    private fun onUpdate(session: NISession, objects: List<*>) {
        if (!isHome(session)) return
        val label = peer?.label ?: return
        for (nearby in objects.filterIsInstance<NINearbyObject>()) {
            val distance = nearby.distance
            if (distance == NINearbyObjectDistanceNotAvailable || !distance.isFinite()) continue
            if (!running) {
                running = true
                recovered = false
                trace.range("running", label)
            }
            onRange?.invoke(PeerRange(PlayerId(label), distance.toDouble(), degreesOf(nearby.direction), nowMillis()))
        }
    }

    private fun onRemove(session: NISession, reason: NINearbyObjectRemovalReason) {
        if (!isHome(session)) return
        val why = when (reason) {
            NINearbyObjectRemovalReason.NINearbyObjectRemovalReasonTimeout -> "timeout"
            NINearbyObjectRemovalReason.NINearbyObjectRemovalReasonPeerEnded -> "peer_ended"
            else -> "unknown"
        }
        trace.range("removed", peer?.label, error = why)
        // A timeout: the peer is out of reach or its screen went off; the same configuration again (Apple's advice).
        // A peer that ended comes back with a new token, which configures the session anew.
        if (why == "timeout") rerun()
    }

    private fun onSuspensionEnded(session: NISession) {
        if (!isHome(session)) return
        trace.range("suspension_ended", peer?.label)
        // A suspended session does not go on by itself: it runs its configuration again.
        rerun()
    }

    private fun onInvalidated(session: NISession, error: NSError) {
        if (!isHome(session)) return
        trace.range("invalidated", peer?.label, error = "${error.code}: ${error.localizedDescription}")
        home = null
        mutableToken.value = null
        val recoverable = error.code == NIErrorCodeSessionFailed || error.code == NIErrorCodeResourceUsageTimeout
        val scope = ranging ?: return
        if (!recoverable || recovered) return
        recovered = true
        scope.launch {
            delay(RERUN_MILLIS)
            if (session() == null) return@launch
            trace.range("rerun", peer?.label)
            peer?.let(::configure)
        }
    }

    private fun rerun() {
        val configuration = configuration ?: return
        val session = home?.session ?: return
        if (ranging == null) return
        session.runWithConfiguration(configuration)
        trace.range("rerun", peer?.label)
    }

    private fun isHome(session: NISession): Boolean = home?.session === session

    /** The home session and its delegate: the session holds the delegate only weakly. */
    private class Home(val session: NISession, val delegate: SessionDelegate)

    private companion object {
        const val RERUN_MILLIS = 2_000L
    }
}

/** The delegate of a session: every callback on the main queue, handed to the radio. */
private class SessionDelegate(
    private val onUpdate: (NISession, List<*>) -> Unit,
    private val onRemove: (NISession, NINearbyObjectRemovalReason) -> Unit,
    private val onSuspended: (NISession) -> Unit,
    private val onSuspensionEnded: (NISession) -> Unit,
    private val onInvalidated: (NISession, NSError) -> Unit,
) : NSObject(),
    NISessionDelegateProtocol {
    @ObjCSignatureOverride
    override fun session(session: NISession, didUpdateNearbyObjects: List<*>) =
        onUpdate(session, didUpdateNearbyObjects)

    override fun session(
        session: NISession,
        didRemoveNearbyObjects: List<*>,
        withReason: NINearbyObjectRemovalReason,
    ) = onRemove(session, withReason)

    override fun sessionWasSuspended(session: NISession) = onSuspended(session)

    override fun sessionSuspensionEnded(session: NISession) = onSuspensionEnded(session)

    override fun session(session: NISession, didInvalidateWithError: NSError) =
        onInvalidated(session, didInvalidateWithError)
}

/** The run's label in the lab (the player in a game). */
private val UwbPeer.label: String get() = playerId.value

/** A discovery token as the run carries it: archived with secure coding, base64. */
private fun archive(token: NIDiscoveryToken): String? =
    NSKeyedArchiver.archivedDataWithRootObject(token, true, null)?.base64EncodedStringWithOptions(0u)

private fun unarchive(base64: String): NIDiscoveryToken? {
    val data = NSData.create(base64EncodedString = base64, options = 0u) ?: return null
    return NSKeyedUnarchiver.unarchivedObjectOfClass(NIDiscoveryToken, data, null) as? NIDiscoveryToken
}

/**
 * Degrees clockwise from where the phone points (the back of the phone, -z; +x to the right), null when iOS doesn't
 * know the direction (every component NaN: out of the camera's field of view, an iPhone 14 and later without ARKit).
 */
private fun degreesOf(direction: Vector128): Double? {
    val x = direction.getFloatAt(0)
    val z = direction.getFloatAt(2)
    if (x.isNaN() || z.isNaN()) return null
    return atan2(x.toDouble(), -z.toDouble()) * 180.0 / PI
}

private fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
