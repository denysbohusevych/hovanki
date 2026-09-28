package app.hovanki.client.di

import app.hovanki.client.account.AccountManager
import app.hovanki.client.automation.LaunchOptions
import app.hovanki.client.automation.LaunchOptionsHolder
import app.hovanki.client.defaultServerUrl
import app.hovanki.client.history.HistoryManager
import app.hovanki.client.network.AccountApi
import app.hovanki.client.network.GameApi
import app.hovanki.client.network.GameConnection
import app.hovanki.client.network.HistoryApi
import app.hovanki.client.network.HttpAccountApi
import app.hovanki.client.network.HttpGameApi
import app.hovanki.client.network.HttpHistoryApi
import app.hovanki.client.network.HttpSocialApi
import app.hovanki.client.network.PollingGameConnection
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.SocialApi
import app.hovanki.client.network.createHttpClient
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.session.ServerClock
import app.hovanki.client.social.SocialManager
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.ui.chat.ChatViewModel
import app.hovanki.client.ui.friends.FriendsViewModel
import app.hovanki.client.ui.game.GameViewModel
import app.hovanki.client.ui.groups.GroupsViewModel
import app.hovanki.client.ui.history.HistoryViewModel
import app.hovanki.client.ui.invite.InviteBannerViewModel
import app.hovanki.client.ui.lobby.LobbyViewModel
import app.hovanki.client.ui.main.MainViewModel
import app.hovanki.client.ui.play.PlayViewModel
import app.hovanki.client.ui.profile.ProfileViewModel
import app.hovanki.client.ui.results.ResultsViewModel
import app.hovanki.client.ui.verify.VerifyEmailViewModel
import app.hovanki.client.ui.welcome.WelcomeViewModel
import app.hovanki.shared.rules.AccountRules
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
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
    single { ClientStorage(get()) }
    // One server: the build's (debug builds: the development machine, or the launch options' server, see onAppStart).
    single { ServerUrl(defaultServerUrl(get())) }
    single { LaunchOptionsHolder() }
    single { createHttpClient(get()) }
    single<GameApi> { HttpGameApi(get(), get()) }
    single<AccountApi> { HttpAccountApi(get(), get()) }
    single<SocialApi> { HttpSocialApi(get(), get()) }
    single<HistoryApi> { HttpHistoryApi(get(), get()) }
    single<GameConnection> { PollingGameConnection(get()) }
    single { ServerClock() }
    single { AccountManager(get(), get(), get()) }
    single { SocialManager(get(), get()) }
    single { HistoryManager(get(), get()) }
    single {
        GameSessionManager(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            account = get<AccountManager>(),
            radio = get(),
            precisionRadio = get(),
            deviceInfo = get(),
            activityMonitor = get(),
            pocketPulse = get(),
            carryMonitor = get(),
        )
    }

    viewModelOf(::WelcomeViewModel)
    viewModelOf(::VerifyEmailViewModel)
    viewModelOf(::MainViewModel)
    viewModelOf(::PlayViewModel)
    viewModelOf(::FriendsViewModel)
    viewModelOf(::GroupsViewModel)
    viewModelOf(::ProfileViewModel)
    viewModelOf(::HistoryViewModel)
    viewModelOf(::LobbyViewModel)
    viewModelOf(::GameViewModel)
    viewModelOf(::ResultsViewModel)
    viewModelOf(::ChatViewModel)
    viewModelOf(::InviteBannerViewModel)
}

/** Hands debug start parameters (UI automation) to the screens; see [LaunchOptions]. */
fun offerLaunchOptions(options: LaunchOptions) {
    KoinPlatformTools.defaultContext().get().get<LaunchOptionsHolder>().offer(options)
}

/** [onAppStart] ran in this process. Main thread only. */
private var appStarted = false

/**
 * The UI starts (Android: MainActivity is created, iOS: the view controller): hands over the debug launch [options],
 * then, once per process and in this order: the options' server, dropping the saved game and account they ask to
 * drop, the account saved by an earlier run, a login with the options' account, and back into the saved game, if any.
 * Main thread; later calls only offer options (e.g. a new intent).
 */
fun onAppStart(options: LaunchOptions?) {
    if (options != null) offerLaunchOptions(options)
    if (appStarted) return
    appStarted = true
    val koin = KoinPlatformTools.defaultContext().get()
    val sessionManager = koin.get<GameSessionManager>()
    val account = koin.get<AccountManager>()
    // Debug builds only reach here with options: release builds never read them.
    options?.serverUrl?.let { koin.get<ServerUrl>().value = it }
    if (options?.forgetSavedGame == true) sessionManager.forgetSavedGame()
    if (options?.logOut == true) account.forgetSavedAccount()
    account.restore()
    val login = options?.playerName
    val password = options?.password
    if (login != null && password != null) logInAtStart(account, login, password)
    sessionManager.resumeSavedGame()
}

/** UI automation: log in as the launch options' account, unless the restored account already is that one. */
private fun logInAtStart(account: AccountManager, login: String, password: String) {
    val restored = account.state.value.user
    if (restored != null) {
        val key = login.trim().lowercase()
        if (key == AccountRules.nicknameKey(restored.nickname) || key == AccountRules.emailKey(restored.email)) return
        account.logOut()
    }
    MainScope().launch { account.logIn(login, password) }
}

/**
 * Platform services: [app.hovanki.client.BuildInfo], HTTP engine, [app.hovanki.client.storage.SecureStore],
 * [app.hovanki.client.location.LocationProvider], background tracking, the radar by Bluetooth
 * ([app.hovanki.client.radio.ProximityRadio]), what the phone is ([app.hovanki.client.device.DeviceInfo]) and its
 * motion sensors ([app.hovanki.client.tracking.ActivityMonitor]).
 */
expect val platformModule: Module
