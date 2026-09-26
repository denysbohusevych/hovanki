package app.hovanki.client.di

import app.hovanki.client.location.AndroidLocationProvider
import app.hovanki.client.location.LocationProvider
import app.hovanki.client.proximity.NoopProximityScanner
import app.hovanki.client.proximity.ProximityScanner
import app.hovanki.client.storage.AndroidSecureStore
import app.hovanki.client.storage.SecureStore
import app.hovanki.client.tracking.AndroidBackgroundTracker
import app.hovanki.client.tracking.BackgroundTracker
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<HttpClientEngine> { OkHttp.create() }
    single<SecureStore> { AndroidSecureStore(androidContext()) }
    single<LocationProvider> { AndroidLocationProvider(androidContext()) }
    single<BackgroundTracker> { AndroidBackgroundTracker(androidContext()) }
    // TODO(BLE, after MVP): Kable-based scanner, see docs/adr/0001-stack.md.
    single<ProximityScanner> { NoopProximityScanner() }
}
