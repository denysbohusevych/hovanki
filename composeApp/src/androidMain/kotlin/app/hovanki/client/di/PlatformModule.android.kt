package app.hovanki.client.di

import app.hovanki.client.BuildInfo
import app.hovanki.client.androidBuildInfo
import app.hovanki.client.device.AndroidDeviceInfo
import app.hovanki.client.device.DeviceInfo
import app.hovanki.client.lab.AndroidLabAir
import app.hovanki.client.lab.AndroidLabFiles
import app.hovanki.client.lab.AndroidLabHaptics
import app.hovanki.client.lab.AndroidLabProbes
import app.hovanki.client.lab.AndroidLabScreen
import app.hovanki.client.lab.LabAir
import app.hovanki.client.lab.LabFiles
import app.hovanki.client.lab.LabHaptics
import app.hovanki.client.lab.LabProbes
import app.hovanki.client.lab.LabScreen
import app.hovanki.client.location.AndroidLocationProvider
import app.hovanki.client.location.LocationProvider
import app.hovanki.client.radio.AndroidProximityRadio
import app.hovanki.client.radio.NoopPrecisionRadio
import app.hovanki.client.radio.PrecisionRadio
import app.hovanki.client.radio.ProximityRadio
import app.hovanki.client.share.AndroidShareSheet
import app.hovanki.client.share.ShareSheet
import app.hovanki.client.storage.AndroidSecureStore
import app.hovanki.client.storage.SecureStore
import app.hovanki.client.tracking.ActivityMonitor
import app.hovanki.client.tracking.AndroidActivityMonitor
import app.hovanki.client.tracking.AndroidBackgroundTracker
import app.hovanki.client.tracking.AndroidCarryMonitor
import app.hovanki.client.tracking.AndroidPocketPulse
import app.hovanki.client.tracking.BackgroundTracker
import app.hovanki.client.tracking.CarryMonitor
import app.hovanki.client.tracking.PocketPulse
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
    single<BackgroundTracker> { AndroidBackgroundTracker(androidContext()) }
    single<ShareSheet> { AndroidShareSheet(androidContext()) }
    // The radar by Bluetooth LE (docs/adr/0012-nearby-radar.md); the precision radar by UWB is not implemented yet.
    single<ProximityRadio> { AndroidProximityRadio(androidContext(), get()) }
    single<PrecisionRadio> { NoopPrecisionRadio() }
    single<DeviceInfo> { AndroidDeviceInfo(androidContext()) }
    single<ActivityMonitor> { AndroidActivityMonitor(androidContext()) }
    single<PocketPulse> { AndroidPocketPulse(androidContext()) }
    single<CarryMonitor> { AndroidCarryMonitor(androidContext()) }
    // The radio lab (docs/radio-lab.md), reached from the debug build's diagnostics only.
    single<LabProbes> { AndroidLabProbes(androidContext()) }
    single<LabAir> { AndroidLabAir(androidContext()) }
    single<LabScreen> { AndroidLabScreen(androidContext()) }
    single<LabHaptics> { AndroidLabHaptics(androidContext()) }
    single<LabFiles> { AndroidLabFiles(androidContext()) }
}
