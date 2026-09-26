package app.hovanki.client.di

import app.hovanki.client.BuildInfo
import app.hovanki.client.automation.LaunchOptionsHolder
import app.hovanki.client.iosBuildInfo
import app.hovanki.client.location.IosLocationProvider
import app.hovanki.client.location.LocationProvider
import app.hovanki.client.proximity.NoopProximityScanner
import app.hovanki.client.proximity.ProximityScanner
import app.hovanki.client.storage.KeychainSecureStore
import app.hovanki.client.storage.SecureStore
import app.hovanki.client.tracking.BackgroundTracker
import app.hovanki.client.tracking.IosBackgroundTracker
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
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
    single<BackgroundTracker> { IosBackgroundTracker() }
    // TODO(BLE, after MVP): Kable-based scanner, see docs/adr/0001-stack.md.
    single<ProximityScanner> { NoopProximityScanner() }
}
