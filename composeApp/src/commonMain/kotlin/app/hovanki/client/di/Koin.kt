package app.hovanki.client.di

import app.hovanki.client.automation.LaunchOptions
import app.hovanki.client.automation.LaunchOptionsHolder
import app.hovanki.client.defaultServerUrl
import app.hovanki.client.network.GameApi
import app.hovanki.client.network.GameConnection
import app.hovanki.client.network.HttpGameApi
import app.hovanki.client.network.PollingGameConnection
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.createHttpClient
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.session.ServerClock
import app.hovanki.client.ui.game.GameViewModel
import app.hovanki.client.ui.home.HomeViewModel
import app.hovanki.client.ui.lobby.LobbyViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools

/**
 * Starts DI once per process. Idempotent because both entry points may call it more than once
 * (iOS creates a new view controller when the scene is recreated).
 */
fun initKoin(appDeclaration: KoinAppDeclaration = {}) {
    if (KoinPlatformTools.defaultContext().getOrNull() != null) return
    startKoin {
        appDeclaration()
        modules(commonModule, platformModule)
    }
}

val commonModule: Module = module {
    single { ServerUrl(defaultServerUrl()) }
    single { LaunchOptionsHolder() }
    single { createHttpClient(get()) }
    single<GameApi> { HttpGameApi(get(), get()) }
    single<GameConnection> { PollingGameConnection(get()) }
    single { ServerClock() }
    single { GameSessionManager(get(), get(), get(), get(), get()) }

    viewModelOf(::HomeViewModel)
    viewModelOf(::LobbyViewModel)
    viewModelOf(::GameViewModel)
}

/** Hands debug start parameters (UI automation) to the start screen; see [LaunchOptions]. */
fun offerLaunchOptions(options: LaunchOptions) {
    KoinPlatformTools.defaultContext().get().get<LaunchOptionsHolder>().offer(options)
}

/** Platform services: HTTP engine, [app.hovanki.client.location.LocationProvider], background tracking, BLE. */
expect val platformModule: Module
