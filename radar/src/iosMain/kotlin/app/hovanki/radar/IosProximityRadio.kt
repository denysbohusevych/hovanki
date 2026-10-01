@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.radar

import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.rules.OverflowArea
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBAdvertisementDataIsConnectable
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataManufacturerDataKey
import platform.CoreBluetooth.CBAdvertisementDataOverflowServiceUUIDsKey
import platform.CoreBluetooth.CBAdvertisementDataServiceDataKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAdvertisementDataTxPowerLevelKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationRestricted
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBUUID
import platform.CoreLocation.CLBeacon
import platform.CoreLocation.CLBeaconIdentityConstraint
import platform.CoreLocation.CLBeaconRegion
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.CLRegion
import platform.CoreLocation.CLRegionState
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUUID
import platform.Foundation.allKeys
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * The radar over Bluetooth LE on iOS (docs/adr/0012-nearby-radar.md, section 2.2), the iOS host of the radar's
 * channels (docs/adr/0017-radar-techniques-and-big-run.md, section 2.2): one advertisement of `CBPeripheralManager`
 * joined from the channels' parts ([RadarCatalog.advert]), the game's scan for its service, CoreLocation for the
 * seekers' iBeacons, and every frame read by every channel ([AirDecoder]).
 *
 * A hider's phone advertises the game's service UUID with the token as its name (iOS lets a third-party app advertise
 * nothing else, and keeps only 8 characters of the name next to a 128-bit UUID; in the background only the UUID goes
 * out, in the overflow area other iPhones see). A seeker's phone advertises an iBeacon frame with the token as major
 * and minor, which works on the screen only, where a seeker is anyway. Every phone scans through CoreBluetooth for the
 * game's service (an Android hider's `.scan_response`, an iPhone's name; the `.bare` and `.mfr` layouts are read too,
 * should iOS ever let them through) and ranges the seekers' iBeacon through CoreLocation, which keeps giving the
 * signal once a second from a pocket, screen off, while the round's location updates keep the app alive («Пульс»);
 * the beacon region also wakes the app when a seeker comes near.
 *
 * With a journal ([RadioTrace.isListening]: the field build, the lab), «in the shadow» (ADR 0018 §4 B), nothing of it
 * reaching the game: the region's entries and exits go into it (`ble.ibeacon.region`); on the screen a second scan
 * asks for the overflow table's UUIDs and reads locked iPhones' masks (`ble.overflow`); and as the app resigns, still
 * active, the advertisement becomes the one a locked iPhone keeps on the air: the game's service and the table's UUIDs
 * of its token, which iOS turns into the overflow mask; it goes back as the app is active again. The advertisement is
 * never touched in the background (iOS can't start one there).
 *
 * Written without an iOS build at hand: the first run on a device is part of the field test's spike.
 */
class IosProximityRadio(private val trace: RadioTrace = RadioTrace.None) : ProximityRadio {
    private val mutableState = MutableStateFlow(BluetoothState.UNSUPPORTED)
    override val state: StateFlow<BluetoothState> = mutableState

    /** Watches the adapter for [state] without scanning; created on the first use, which also asks the user. */
    private var watcher: CBCentralManager? = null
    private val watcherDelegate = object : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            mutableState.value = stateOf(central)
        }
    }

    /** Starts watching the adapter (the system asks for Bluetooth the first time). */
    override fun refresh() {
        if (watcher == null) watcher = CBCentralManager(watcherDelegate, null)
        watcher?.let { mutableState.value = stateOf(it) }
    }

    override fun run(tokens: StateFlow<String?>, asSeeker: Boolean, options: RadioOptions): Flow<RadioSighting> =
        callbackFlow {
            val decoder = AirDecoder(trace)
            val onFrame: (HeardFrame) -> Unit = { frame ->
                for (sighting in decoder.decode(frame)) trySend(sighting)
            }
            val listener = Listener(onState = { central -> mutableState.value = stateOf(central) }, onFrame, trace)
            val role = if (asSeeker) AirRole.SEEKER else AirRole.HIDER
            val advertiser = Advertiser(if (asSeeker) "ibeacon" else "hider_name", trace) { token ->
                RadarCatalog.advert(token, role, AirPlatform.IOS, options, trace.isListening)
            }
            var shadowScanner: ShadowScanner? = null
            fun followTheJournal() {
                val listening = trace.isListening
                if (listening && shadowScanner == null) shadowScanner = ShadowScanner(onFrame, trace)
                if (!listening) {
                    shadowScanner?.close()
                    shadowScanner = null
                }
            }
            followTheJournal()
            val tokenJob = tokens.onEach { advertiser.advertise(it) }.launchIn(this)
            // The air's counts once a second, and the journal followed: the shadow's scan comes and goes with it.
            val shadowJob = launch {
                while (isActive) {
                    delay(SHADOW_CHECK_MILLIS)
                    decoder.flush(nowMillis())
                    followTheJournal()
                }
            }
            // All of them are the delegates of their managers, which hold them only weakly: referenced here, they live
            // as long as the radio runs (a delegate the garbage collector took would leave the phone deaf).
            awaitClose {
                tokenJob.cancel()
                shadowJob.cancel()
                advertiser.close()
                listener.close()
                shadowScanner?.close()
                decoder.flush(nowMillis(), force = true)
            }
        }

    private fun stateOf(central: CBCentralManager): BluetoothState = when {
        central.state == CBManagerStateUnsupported -> BluetoothState.UNSUPPORTED

        central.state == CBManagerStateUnauthorized -> BluetoothState.DENIED

        CBManager.authorization == CBManagerAuthorizationDenied ||
            CBManager.authorization == CBManagerAuthorizationRestricted -> BluetoothState.DENIED

        central.state == CBManagerStatePoweredOn -> BluetoothState.ON

        CBManager.authorization != CBManagerAuthorizationAllowedAlways -> BluetoothState.DENIED

        else -> BluetoothState.OFF
    }

    companion object {
        /** The same as on Android: the phones of both kinds hear each other by it. */
        const val SERVICE_UUID = GameAir.SERVICE_UUID

        /**
         * An iPhone hider on the screen carries its token as its name (iOS advertises no data); the first apps put
         * this before it, which iOS cut off with the token's end, so it is only still read.
         */
        const val NAME_PREFIX = GameAir.NAME_PREFIX

        /** The journal is looked at this often; the air's counts go out as often. */
        private const val SHADOW_CHECK_MILLIS = 1_000L
    }
}

private const val BEACON_REGION_ID = "app.hovanki.radar"

/** Anything weaker than this is noise. */
private const val MIN_RSSI = -110

/** A real reading: CoreLocation reports 0 for a beacon it lost, CoreBluetooth 127 for none; weaker is noise. */
private fun isReading(rssi: Int): Boolean = rssi in MIN_RSSI..-1

private fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

private fun NSData.toByteArray(): ByteArray {
    val bytes = ByteArray(length.toInt())
    if (bytes.isNotEmpty()) {
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), this.bytes, length) }
    }
    return bytes
}

private fun uuidsOf(value: Any?): List<String> =
    (value as? List<*>).orEmpty().mapNotNull { (it as? CBUUID)?.UUIDString?.let(BleUuid::canonical) }

/**
 * What CoreBluetooth shows of an advertisement, for the channels: the name, the service UUIDs and the overflow area's
 * (those the scan asked for), service data, the manufacturer data (iOS gives one block: the company id, little-endian,
 * then its data; never an iBeacon frame), the power and whether it is connectable.
 */
private fun frameOf(data: Map<Any?, *>, rssi: Int, peer: String): HeardFrame {
    val serviceData = (data[CBAdvertisementDataServiceDataKey] as? Map<*, *>).orEmpty().entries.mapNotNull { entry ->
        val uuid = (entry.key as? CBUUID)?.UUIDString ?: return@mapNotNull null
        val bytes = (entry.value as? NSData)?.toByteArray() ?: return@mapNotNull null
        BleUuid.canonical(uuid) to AirHex.of(bytes)
    }.toMap()
    val manufacturer = (data[CBAdvertisementDataManufacturerDataKey] as? NSData)?.toByteArray()
        ?.takeIf { it.size >= 2 }
        ?.let { bytes ->
            val company = (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8)
            mapOf(company to AirHex.of(bytes.copyOfRange(2, bytes.size)))
        }
    return HeardFrame(
        rssi = rssi,
        atMillis = nowMillis(),
        api = RadioApi.COREBLUETOOTH,
        peer = peer,
        localName = data[CBAdvertisementDataLocalNameKey] as? String,
        serviceUuids = uuidsOf(data[CBAdvertisementDataServiceUUIDsKey]),
        overflowUuids = uuidsOf(data[CBAdvertisementDataOverflowServiceUUIDsKey]),
        serviceData = serviceData,
        manufacturerData = manufacturer.orEmpty(),
        txPower = (data[CBAdvertisementDataTxPowerLevelKey] as? NSNumber)?.intValue,
        connectable = (data[CBAdvertisementDataIsConnectable] as? NSNumber)?.boolValue,
    )
}

/**
 * Listens while the radio runs: scans through CoreBluetooth for the game's service (an Android hider's
 * `.scan_response`, an iPhone's name), ranges the seekers' iBeacon through CoreLocation (CoreBluetooth never shows
 * iBeacon frames to an app) and watches their region, which tells the journal when it is entered and left
 * (`ble.ibeacon.region`). The delegate of both managers, which hold it only weakly: whoever runs it keeps a reference
 * until [close].
 */
private class Listener(
    private val onState: (CBCentralManager) -> Unit,
    private val onFrame: (HeardFrame) -> Unit,
    private val trace: RadioTrace,
) : NSObject(),
    CBCentralManagerDelegateProtocol,
    CLLocationManagerDelegateProtocol {
    /** What iOS can scan for of the game's channels: the services they list (a filter works in the background). */
    private val services = RadarCatalog.interests(AirPlatform.IOS, shadow = false)
        .filterIsInstance<ScanInterest.ServiceUuid>()
        .map { CBUUID.UUIDWithString(it.uuid) }
    private val central = CBCentralManager()
    private val ranger = CLLocationManager()
    private val constraint = CLBeaconIdentityConstraint(NSUUID(GameAir.SERVICE_UUID))
    private val region = CLBeaconRegion(constraint, BEACON_REGION_ID)

    /** The region's last state, so the journal says `enter` and `exit` only on a change. */
    private var lastState: String? = null

    init {
        central.delegate = this
        ranger.delegate = this
        region.notifyEntryStateOnDisplay = false
        ranger.startMonitoringForRegion(region)
        ranger.startRangingBeaconsSatisfyingConstraint(constraint)
        trace.scan("start", RadioApi.CORELOCATION_RANGING, "iBeacon of the game")
    }

    fun close() {
        ranger.stopRangingBeaconsSatisfyingConstraint(constraint)
        ranger.stopMonitoringForRegion(region)
        ranger.delegate = null
        if (central.state == CBManagerStatePoweredOn) central.stopScan()
        central.delegate = null
        trace.scan("stop", RadioApi.COREBLUETOOTH)
        trace.scan("stop", RadioApi.CORELOCATION_RANGING)
    }

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        onState(central)
        if (central.state == CBManagerStatePoweredOn) {
            central.scanForPeripheralsWithServices(
                services,
                mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to true),
            )
            trace.scan("start", RadioApi.COREBLUETOOTH, "game service")
        }
    }

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) {
        val rssi = RSSI.intValue
        if (!isReading(rssi)) return
        onFrame(frameOf(advertisementData, rssi, didDiscoverPeripheral.identifier.UUIDString))
    }

    override fun locationManager(
        manager: CLLocationManager,
        didRangeBeacons: List<*>,
        satisfyingConstraint: CLBeaconIdentityConstraint,
    ) {
        for (beacon in didRangeBeacons) {
            val found = beacon as? CLBeacon ?: continue
            val rssi = found.rssi.toInt()
            if (!isReading(rssi)) continue
            val heard = HeardIBeacon(found.UUID.UUIDString, found.major.intValue, found.minor.intValue)
            onFrame(HeardFrame(rssi, nowMillis(), RadioApi.CORELOCATION_RANGING, iBeacon = heard))
        }
    }

    /** iOS tells the region's state on every crossing (besides entered and left) and when asked. */
    override fun locationManager(manager: CLLocationManager, didDetermineState: CLRegionState, forRegion: CLRegion) {
        val state = when (didDetermineState) {
            CLRegionState.CLRegionStateInside -> "inside"
            CLRegionState.CLRegionStateOutside -> "outside"
            else -> "unknown"
        }
        val was = lastState
        lastState = state
        if (!trace.isListening) return
        val event = when {
            was == state -> "state"
            state == "inside" -> "enter"
            state == "outside" && was != null -> "exit"
            else -> "state"
        }
        trace.region(event, state)
    }

    override fun locationManager(
        manager: CLLocationManager,
        monitoringDidFailForRegion: CLRegion?,
        withError: NSError,
    ) {
        if (trace.isListening) trace.region("failed", error = withError.localizedDescription)
    }
}

/**
 * The shadow's scan on the screen (`ble.overflow`): CoreBluetooth reads another iPhone's overflow area only for the
 * UUIDs a scan asks for by name, so this one asks for the table's 128 and hands the frames to the same channels; iOS
 * stops it in the background by itself. A manager of its own, so the game's scan stays as it is. The delegate of its
 * manager, kept by whoever runs it until [close].
 */
private class ShadowScanner(private val onFrame: (HeardFrame) -> Unit, private val trace: RadioTrace) :
    NSObject(),
    CBCentralManagerDelegateProtocol {
    private val central = CBCentralManager()
    private val uuids = RadarCatalog.interests(AirPlatform.IOS, shadow = true)
        .filterIsInstance<ScanInterest.OverflowUuids>()
        .flatMap { it.uuids }
        .distinct()
        .map { CBUUID.UUIDWithString(it) }

    init {
        central.delegate = this
    }

    fun close() {
        if (central.state == CBManagerStatePoweredOn) central.stopScan()
        central.delegate = null
        trace.scan("stop", RadioApi.COREBLUETOOTH, "overflow table")
    }

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        if (central.state == CBManagerStatePoweredOn && uuids.isNotEmpty()) {
            central.scanForPeripheralsWithServices(uuids, mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to true))
            trace.scan("start", RadioApi.COREBLUETOOTH, "overflow table (${OverflowArea.BITS})")
        }
    }

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) {
        val rssi = RSSI.intValue
        if (!isReading(rssi)) return
        val frame = frameOf(advertisementData, rssi, didDiscoverPeripheral.identifier.UUIDString)
        // The game's scan reads everything else; this one is for the masks.
        if (frame.overflowUuids.isNotEmpty()) onFrame(frame)
    }
}

/**
 * Advertises the latest token for as long as it is set, whatever the adapter does meanwhile. iOS takes
 * `startAdvertising` only once the peripheral manager says it is powered on, which comes a moment after it is made
 * (and again after Bluetooth was switched off and on): a call before that is dropped without a word, so the token
 * waits here and goes out from [peripheralManagerDidUpdateState].
 *
 * Since iOS 14 an app in the background can neither start an advertisement nor change it: a restart there (the token's
 * five-minute slot changing while the phone is locked) would stop the one on the air and start nothing, and the phone
 * would be gone from the radar until it is unlocked. So in the background the advertisement on the air stays; the new
 * token waits for the app to come back ([UIApplicationDidBecomeActiveNotification]). In the background iOS sends neither
 * the name nor the iBeacon frame anyway, at most the services' bits (docs/adr/0016-iphone-overflow-radar.md).
 *
 * With a journal, as the app resigns (still active: [UIApplicationWillResignActiveNotification]) the advertisement
 * becomes the locked phone's ([Advert.backgroundUuids]: the game's service and the table's UUIDs of the token, the
 * overflow channel's, in the shadow); the token in it stays the one of that moment until the app is active again.
 * Without a journal resigning changes nothing.
 */
private class Advertiser(
    private val mode: String,
    private val trace: RadioTrace,
    private val advertOf: (token: String) -> Advert,
) : NSObject(),
    CBPeripheralManagerDelegateProtocol {
    private val manager = CBPeripheralManager()
    private var token: String? = null

    /** The app resigned (or is in the background): with a journal, the locked phone's advertisement is wanted. */
    private var resigned = false

    /** What is on the air; null: nothing is. */
    private var advertised: Wanted? = null

    private val becameActive = NSNotificationCenter.defaultCenter.addObserverForName(
        UIApplicationDidBecomeActiveNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ ->
        resigned = false
        restart()
    }

    private val willResign = NSNotificationCenter.defaultCenter.addObserverForName(
        UIApplicationWillResignActiveNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ ->
        resigned = true
        restart()
    }

    init {
        manager.delegate = this
    }

    fun advertise(token: String?) {
        this.token = token
        restart()
    }

    fun close() {
        NSNotificationCenter.defaultCenter.removeObserver(becameActive)
        NSNotificationCenter.defaultCenter.removeObserver(willResign)
        token = null
        restart()
        manager.delegate = null
    }

    override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
        // Bluetooth off (or not yet on) ends any advertisement: nothing is on the air until it is started again.
        if (peripheral.state != CBManagerStatePoweredOn) advertised = null
        restart()
    }

    private fun restart() {
        if (manager.state != CBManagerStatePoweredOn) return
        val wanted = token?.let { token ->
            val advert = advertOf(token)
            Wanted(token, background = resigned && trace.isListening && advert.backgroundUuids.isNotEmpty(), advert)
        }
        if (wanted?.token == advertised?.token && wanted?.background == advertised?.background) return
        // Stopping works anywhere; a new advertisement waits for the screen while one is on the air (see above).
        val inBackground =
            UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateBackground
        if (wanted != null && advertised != null && inBackground) {
            trace.advertise("skipped_background", modeOf(wanted), wanted.token)
            return
        }
        manager.stopAdvertising()
        advertised?.let { trace.advertise("stop", modeOf(it), it.token) }
        advertised = null
        if (wanted == null) return
        val data = dataOf(wanted)
        if (data.isEmpty()) {
            trace.advertise("failed", modeOf(wanted), wanted.token, "nothing to advertise", wanted.advert.report())
            return
        }
        manager.startAdvertising(data)
        advertised = wanted
        trace.advertise("start", modeOf(wanted), wanted.token, report = wanted.advert.report())
    }

    override fun peripheralManagerDidStartAdvertising(peripheral: CBPeripheralManager, error: NSError?) {
        val on = advertised
        if (error != null) trace.advertise("failed", on?.let(::modeOf) ?: mode, on?.token, error.localizedDescription)
    }

    private fun modeOf(wanted: Wanted): String = if (wanted.background) BACKGROUND_MODE else mode

    /** The dictionary `startAdvertising` takes: an iBeacon's, or the name and the service UUIDs. */
    private fun dataOf(wanted: Wanted): Map<Any?, *> {
        val advert = wanted.advert
        if (wanted.background) {
            val uuids = (advert.main.serviceUuids + advert.backgroundUuids).distinct()
            return buildMap<Any?, Any?> {
                put(CBAdvertisementDataServiceUUIDsKey, uuids.map { CBUUID.UUIDWithString(it) })
                advert.main.localName?.let { put(CBAdvertisementDataLocalNameKey, it) }
            }
        }
        advert.iBeacon?.let { beacon ->
            val region = CLBeaconRegion(
                uUID = NSUUID(beacon.uuid),
                major = beacon.major.toUShort(),
                minor = beacon.minor.toUShort(),
                identifier = BEACON_REGION_ID,
            )
            val dictionary = region.peripheralDataWithMeasuredPower(null)
            return dictionary.allKeys.associateWith<Any?, Any?> { dictionary.objectForKey(it) }
        }
        return buildMap<Any?, Any?> {
            if (advert.main.serviceUuids.isNotEmpty()) {
                put(CBAdvertisementDataServiceUUIDsKey, advert.main.serviceUuids.map { CBUUID.UUIDWithString(it) })
            }
            // The bare token: next to a 128-bit service iOS keeps 8 characters of the name, which is exactly it.
            advert.main.localName?.let { put(CBAdvertisementDataLocalNameKey, it) }
        }
    }

    /** An advertisement wanted on the air: the token, the locked phone's or the screen's, and what it is made of. */
    private class Wanted(val token: String, val background: Boolean, val advert: Advert)
}

/**
 * The locked phone's advertisement in the journal (`adv`). Top level: Kotlin/Native allows no fields in the companion
 * of an Objective-C class's subclass ([Advertiser]).
 */
private const val BACKGROUND_MODE = "background_overflow"
