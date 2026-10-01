package app.hovanki.client.di

import app.hovanki.client.BuildInfo
import app.hovanki.client.androidBuildInfo
import app.hovanki.client.field.AndroidAppPermissions
import app.hovanki.client.lab.AndroidLabFiles
import app.hovanki.client.lab.AppPermissions
import app.hovanki.client.lab.LabFiles
import app.hovanki.client.location.AndroidLocationProvider
import app.hovanki.client.location.LocationProvider
import app.hovanki.client.share.AndroidShareSheet
import app.hovanki.client.share.ShareSheet
import app.hovanki.client.storage.AndroidSecureStore
import app.hovanki.client.storage.SecureStore
import app.hovanki.client.tracking.AndroidBackgroundTracker
import app.hovanki.client.tracking.BackgroundTracker
import app.hovanki.device.ActivityMonitor
import app.hovanki.device.AndroidActivityMonitor
import app.hovanki.device.AndroidCarryMonitor
import app.hovanki.device.AndroidDeviceInfo
import app.hovanki.device.AndroidImpactMonitor
import app.hovanki.device.AndroidPocketPulse
import app.hovanki.device.CarryMonitor
import app.hovanki.device.DeviceInfo
import app.hovanki.device.ImpactMonitor
import app.hovanki.device.PocketPulse
import app.hovanki.device.lab.AndroidLabHaptics
import app.hovanki.device.lab.AndroidLabProbes
import app.hovanki.device.lab.AndroidLabScreen
import app.hovanki.device.lab.LabHaptics
import app.hovanki.device.lab.LabProbes
import app.hovanki.device.lab.LabScreen
import app.hovanki.radar.AndroidProximityRadio
import app.hovanki.radar.NoopPrecisionRadio
import app.hovanki.radar.PrecisionRadio
import app.hovanki.radar.ProximityRadio
import app.hovanki.radar.lab.AndroidLabAir
import app.hovanki.radar.lab.LabAir
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<BuildInfo> { androidBuildInfo(androidContext()) }
    single<HttpClientEngine> { OkHttp.create() }
    single<SecureStore> { AndroidSecureStore(androidContext()) }
    single<LocationProvider> { AndroidLocationProvider(androidContext()) }
    single<AppPermissions> { AndroidAppPermissions(androidContext()) }
    single<BackgroundTracker> { AndroidBackgroundTracker(androidContext()) }
    single<ShareSheet> { AndroidShareSheet(androidContext()) }
    // The radar by Bluetooth LE (docs/adr/0012-nearby-radar.md); the precision radar by UWB is not implemented yet.
    single<ProximityRadio> { AndroidProximityRadio(androidContext(), get()) }
    single<PrecisionRadio> { NoopPrecisionRadio() }
    single<DeviceInfo> { AndroidDeviceInfo(androidContext()) }
    single<ActivityMonitor> { AndroidActivityMonitor(androidContext()) }
    single<PocketPulse> { AndroidPocketPulse(androidContext()) }
    single<CarryMonitor> { AndroidCarryMonitor(androidContext()) }
    single<ImpactMonitor> { AndroidImpactMonitor(androidContext()) }
    // The radio lab (docs/radio-lab.md), reached from the debug build's diagnostics only.
    single<LabProbes> { AndroidLabProbes(androidContext()) }
    single<LabAir> { AndroidLabAir(androidContext()) }
    single<LabScreen> { AndroidLabScreen(androidContext()) }
    single<LabHaptics> { AndroidLabHaptics(androidContext()) }
    single<LabFiles> { AndroidLabFiles(androidContext()) }
}
