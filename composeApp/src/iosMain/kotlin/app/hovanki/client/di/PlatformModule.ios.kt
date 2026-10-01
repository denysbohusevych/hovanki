package app.hovanki.client.di

import app.hovanki.client.BuildInfo
import app.hovanki.client.automation.LaunchOptionsHolder
import app.hovanki.client.field.IosAppPermissions
import app.hovanki.client.iosBuildInfo
import app.hovanki.client.lab.AppPermissions
import app.hovanki.client.lab.BridgedLiveActivityHost
import app.hovanki.client.lab.IosLabFiles
import app.hovanki.client.lab.LabFiles
import app.hovanki.client.lab.LabRangeTrace
import app.hovanki.client.location.IosLocationProvider
import app.hovanki.client.location.LocationProvider
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.alert_seeker_near_text
import app.hovanki.client.resources.alert_seeker_near_title
import app.hovanki.client.share.IosShareSheet
import app.hovanki.client.share.ShareSheet
import app.hovanki.client.storage.KeychainSecureStore
import app.hovanki.client.storage.SecureStore
import app.hovanki.client.tracking.BackgroundTracker
import app.hovanki.client.tracking.IosBackgroundTracker
import app.hovanki.device.ActivityMonitor
import app.hovanki.device.BackgroundModes
import app.hovanki.device.CarryMonitor
import app.hovanki.device.DeviceInfo
import app.hovanki.device.IosActivityMonitor
import app.hovanki.device.IosBackgroundModes
import app.hovanki.device.IosCarryMonitor
import app.hovanki.device.IosDeviceInfo
import app.hovanki.device.IosPocketPulse
import app.hovanki.device.LiveActivityHost
import app.hovanki.device.PocketPulse
import app.hovanki.device.lab.IosLabHaptics
import app.hovanki.device.lab.IosLabProbes
import app.hovanki.device.lab.IosLabScreen
import app.hovanki.device.lab.LabHaptics
import app.hovanki.device.lab.LabProbes
import app.hovanki.device.lab.LabScreen
import app.hovanki.radar.AirHost
import app.hovanki.radar.HostProximityRadio
import app.hovanki.radar.NoopPrecisionRadio
import app.hovanki.radar.PrecisionRadio
import app.hovanki.radar.ProximityRadio
import app.hovanki.radar.host.IosAirHost
import app.hovanki.radar.lab.HostLabAir
import app.hovanki.radar.lab.LabAir
import app.hovanki.radar.link.GattLink
import app.hovanki.radar.link.IosGattLink
import app.hovanki.radar.uwb.IosPrecisionRadio
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import org.jetbrains.compose.resources.getString
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<BuildInfo> { iosBuildInfo() }
    single<HttpClientEngine> { Darwin.create() }
    single<SecureStore> { KeychainSecureStore() }
    single<LocationProvider> {
        val launchOptions = get<LaunchOptionsHolder>()
        IosLocationProvider(allowSimulatedLocation = { launchOptions.options.value?.allowSimulatedLocation == true })
    }
    single<AppPermissions> { IosAppPermissions() }
    single<BackgroundTracker> { IosBackgroundTracker() }
    single<ShareSheet> { IosShareSheet() }
    // The radar by Bluetooth LE (docs/adr/0012-nearby-radar.md): the game's channels on the iPhone's host (ADR 0017
    // §2.2), traced into the radio lab's log; the game's precision radar by UWB stays a no-op (the lab has its own).
    single<AirHost> { IosAirHost() }
    single<ProximityRadio> { HostProximityRadio(get(), trace = get()) }
    single<PrecisionRadio> { NoopPrecisionRadio() }
    single<DeviceInfo> { IosDeviceInfo() }
    single<ActivityMonitor> { IosActivityMonitor() }
    single<PocketPulse> {
        // `:device` has no string resources: the notification's texts come from the app's, read when it is posted.
        IosPocketPulse {
            getString(Res.string.alert_seeker_near_title) to getString(Res.string.alert_seeker_near_text)
        }
    }
    single<CarryMonitor> { IosCarryMonitor() }
    // The radio lab (docs/radio-lab.md), reached from the debug build's diagnostics only.
    single<LabProbes> { IosLabProbes() }
    single<LabAir> { HostLabAir(get(), get()) }
    single<LabScreen> { IosLabScreen() }
    single<LabHaptics> { IosLabHaptics(get()) }
    single<LabFiles> { IosLabFiles() }
    // The lab's background modes (docs/radar-run.md §5.1, §5.3): the Live Activity through the Swift host the app
    // installs at start (`LiveActivityBridge.kt`), unavailable until the owner adds the widget extension in Xcode.
    single<LiveActivityHost> { BridgedLiveActivityHost() }
    single<BackgroundModes> { IosBackgroundModes(get()) }
    // The radio lab's GATT link and its own UWB radio (docs/radar-run.md §5.2, §5.3); the game uses neither.
    single<GattLink> { IosGattLink() }
    single<PrecisionRadio>(LAB_PRECISION) { IosPrecisionRadio(LabRangeTrace(get())) }
}
