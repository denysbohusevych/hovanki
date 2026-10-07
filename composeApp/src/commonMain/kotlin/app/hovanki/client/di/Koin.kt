package app.hovanki.client.di

import app.hovanki.client.BuildInfo
import app.hovanki.client.account.AccountManager
import app.hovanki.client.account.CityLocator
import app.hovanki.client.automation.LaunchOptions
import app.hovanki.client.automation.LaunchOptionsHolder
import app.hovanki.client.bigGames.BigGameManager
import app.hovanki.client.crash.CrashReporter
import app.hovanki.client.crash.CrashReporting
import app.hovanki.client.crash.CurrentCrashReporter
import app.hovanki.client.crash.asErrorReporter
import app.hovanki.client.defaultServerUrl
import app.hovanki.client.diagnostics.Diagnostics
import app.hovanki.client.diagnostics.DiagnosticsBench
import app.hovanki.client.errors.ErrorReporter
import app.hovanki.client.history.HistoryManager
import app.hovanki.client.lab.AppPermissions
import app.hovanki.client.lab.FieldPocket
import app.hovanki.client.lab.FieldSession
import app.hovanki.client.lab.HttpLabApi
import app.hovanki.client.lab.LabAbout
import app.hovanki.client.lab.LabApi
import app.hovanki.client.lab.LabClockSync
import app.hovanki.client.lab.LabController
import app.hovanki.client.lab.LabLog
import app.hovanki.client.lab.LabRadioTrace
import app.hovanki.client.lab.LabRunFollower
import app.hovanki.client.lab.LabRunner
import app.hovanki.client.lab.LabUploader
import app.hovanki.client.lab.PocketTexts
import app.hovanki.client.location.LocationProvider
import app.hovanki.client.network.AccountApi
import app.hovanki.client.network.AdaptiveGameConnection
import app.hovanki.client.network.BigGameApi
import app.hovanki.client.network.GameApi
import app.hovanki.client.network.GameConnection
import app.hovanki.client.network.HistoryApi
import app.hovanki.client.network.HttpAccountApi
import app.hovanki.client.network.HttpBigGameApi
import app.hovanki.client.network.HttpGameApi
import app.hovanki.client.network.HttpHistoryApi
import app.hovanki.client.network.HttpSocialApi
import app.hovanki.client.network.HttpSpectatorApi
import app.hovanki.client.network.KtorGameSocketOpener
import app.hovanki.client.network.PollingGameConnection
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.SocialApi
import app.hovanki.client.network.SpectatorApi
import app.hovanki.client.network.WebSocketGameConnection
import app.hovanki.client.network.createHttpClient
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.app_name
import app.hovanki.client.resources.hud_radar_burning
import app.hovanki.client.resources.hud_radar_hot
import app.hovanki.client.resources.hud_radar_none
import app.hovanki.client.resources.hud_radar_warm
import app.hovanki.client.resources.hud_you_hide
import app.hovanki.client.resources.hud_you_seek
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.session.ServerClock
import app.hovanki.client.social.SocialManager
import app.hovanki.client.spectator.SpectatorManager
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.ui.achievements.AchievementsViewModel
import app.hovanki.client.ui.chat.ChatViewModel
import app.hovanki.client.ui.debug.DiagnosticsViewModel
import app.hovanki.client.ui.debug.LabViewModel
import app.hovanki.client.ui.field.FieldMarks
import app.hovanki.client.ui.friends.FriendsViewModel
import app.hovanki.client.ui.game.GameViewModel
import app.hovanki.client.ui.groups.GroupsViewModel
import app.hovanki.client.ui.history.HistoryViewModel
import app.hovanki.client.ui.invite.InviteBannerViewModel
import app.hovanki.client.ui.leaderboard.LeaderboardViewModel
import app.hovanki.client.ui.lobby.LobbyViewModel
import app.hovanki.client.ui.main.MainViewModel
import app.hovanki.client.ui.play.PlayViewModel
import app.hovanki.client.ui.profile.ProfileViewModel
import app.hovanki.client.ui.results.ResultsViewModel
import app.hovanki.client.ui.spectator.SpectatorViewModel
import app.hovanki.client.ui.verify.VerifyEmailViewModel
import app.hovanki.client.ui.welcome.WelcomeViewModel
import app.hovanki.device.DeviceInfo
import app.hovanki.device.lab.LabProbes
import app.hovanki.radar.ProximityRadio
import app.hovanki.radar.RadarTrace
import app.hovanki.shared.lab.PermFields
import app.hovanki.shared.protocol.LabCapabilities
import app.hovanki.shared.rules.AccountRules
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools

/**
 * Starts DI once per process. Idempotent because both entry points may call it more than once
 * (iOS creates a new view controller when the scene is recreated).
 */
fun initKoin(appDeclaration: KoinAppDeclaration = {}) {
    if (KoinPlatformTools.defaultContext().getOrNull() != null) return
    val koin = startKoin {
        appDeclaration()
        modules(commonModule, platformModule)
    }.koin
    // Crash reports (the field test build only) only with the tester's consent, as soon as it is known; FieldSession
    // keeps it up to date.
    if (koin.get<BuildInfo>().isFieldBuild) {
        CrashReporting.allow(runCatching { koin.get<ClientStorage>().fieldConsentAt != null }.getOrDefault(false))
    }
}

/**
 * The radio lab's own UWB radio (`uwb.ni`, docs/radar-run.md §5.3), bound by the platform modules next to the game's
 * `PrecisionRadio`, which stays a no-op: the game doesn't range yet.
 */
internal val LAB_PRECISION = named("lab.precision")

val commonModule: Module = module {
    single { ClientStorage(get()) }
    // One server: the build's (debug builds on an emulator: the development machine; the launch options' server, see
    // onAppStart).
    single { ServerUrl(defaultServerUrl(get())) }
    single { LaunchOptionsHolder() }
    // Crash reports of the field test build only (docs/adr/0018-field-test-build.md §7): the platform's entry point
    // installs the Sentry glue (before the first screen), in that build and with a DSN; everywhere else this is the no-op.
    single<CrashReporter> { CurrentCrashReporter }
    single<ErrorReporter> { get<CrashReporter>().asErrorReporter() }
    single { createHttpClient(get()) }
    single<GameApi> { HttpGameApi(get(), get()) }
    single<AccountApi> { HttpAccountApi(get(), get()) }
    single<SocialApi> { HttpSocialApi(get(), get()) }
    single<HistoryApi> { HttpHistoryApi(get(), get()) }
    single<BigGameApi> { HttpBigGameApi(get(), get()) }
    single<SpectatorApi> { HttpSpectatorApi(get(), get()) }
    // The live channel while the server has it on and it works, polling otherwise (docs/adr/0015-websockets.md).
    single<GameConnection> {
        AdaptiveGameConnection(
            WebSocketGameConnection(KtorGameSocketOpener(get(), get())),
            PollingGameConnection(get()),
        )
    }
    single { ServerClock() }
    // Debug builds only: the phone's measurements for the developer (a no-op in other builds).
    single { Diagnostics(isEnabled = get<BuildInfo>().isDebug) }
    // The radio lab (docs/radio-lab.md): debug builds, and the field test build for the staff's lab screen (ADR 0018
    // §4.D). It records only while the lab runs or the field log is on, and the field log is the only thing in it that
    // carries coordinates.
    single { LabLog(isEnabled = get<BuildInfo>().isDebug || get<BuildInfo>().isFieldBuild) }
    single<RadarTrace> { LabRadioTrace(get()) }
    single { DiagnosticsBench(get(), get(), get(), MainScope(), lab = get()) }
    single {
        val log = get<LabLog>()
        val api = get<GameApi>()
        val probes = get<LabProbes>()
        val buildInfo = get<BuildInfo>()
        val deviceInfo = get<DeviceInfo>()
        LabController(
            log = log,
            bench = get(),
            probes = probes,
            air = get(),
            screen = get(),
            haptics = get(),
            files = get(),
            radio = get(),
            carryMonitor = get(),
            backgroundTracker = get(),
            clockSync = LabClockSync({ api.serverTime() }, log::deviceNow, log::monoNow),
            about = {
                LabAbout(
                    deviceInfo.model,
                    probes.os,
                    "${buildInfo.version} (${buildInfo.buildNumber})",
                    buildInfo.commit,
                )
            },
            scope = MainScope(),
            inAGame = get<GameSessionManager>().state.map { it.session != null },
            modes = get(),
            link = get(),
            precision = get(LAB_PRECISION),
        )
    }
    single { LabRunner(get(), MainScope(), appState = get<LabProbes>()::appState) }
    // The lab's runs on the server (docs/adr/0017-radar-techniques-and-big-run.md §5): join by the admin's code, follow
    // the plan, upload the log. The screen exists in debug builds only; the server answers 404 while RADIO_LAB is off.
    single<LabApi> { HttpLabApi(get(), get()) }
    single { LabUploader(get(), get(), MainScope()) }
    single {
        val deviceInfo = get<DeviceInfo>()
        val radio = get<ProximityRadio>()
        val locationProvider = get<LocationProvider>()
        val controller = get<LabController>()
        val account = get<AccountManager>()
        LabRunFollower(
            get(),
            get(),
            get(),
            MainScope(),
            appState = get<LabProbes>()::appState,
            // The field test build's lab is the staff's: a test server lets only a staff account join a run.
            accountToken = { account.accountToken },
            capabilities = {
                LabCapabilities(
                    platform = deviceInfo.platform,
                    bluetooth = radio.state.value,
                    uwb = controller.canRange,
                    locationPermission = locationProvider.hasPermission(),
                )
            },
        )
    }
    // The field log of the field test build (docs/adr/0018-field-test-build.md §3): it exists in every build but does
    // nothing outside `preview` and before the tester's consent. The game tells it what happens (GameTrace).
    single {
        val log = get<LabLog>()
        val api = get<GameApi>()
        val probes = get<LabProbes>()
        val buildInfo = get<BuildInfo>()
        val deviceInfo = get<DeviceInfo>()
        val radio = get<ProximityRadio>()
        val locationProvider = get<LocationProvider>()
        val appPermissions = get<AppPermissions>()
        val scope = MainScope()
        FieldSession(
            log = log,
            api = get<LabApi>(),
            storage = get(),
            scope = scope,
            isFieldBuild = buildInfo.isFieldBuild,
            about = {
                LabAbout(
                    deviceInfo.model,
                    probes.os,
                    "${buildInfo.version} (${buildInfo.buildNumber})",
                    buildInfo.commit,
                )
            },
            capabilities = {
                LabCapabilities(
                    platform = deviceInfo.platform,
                    bluetooth = radio.state.value,
                    uwb = deviceInfo.hasUwb,
                    locationPermission = locationProvider.hasPermission(),
                )
            },
            probes = probes,
            clockSync = LabClockSync(
                { api.serverTime() },
                log::deviceNow,
                log::monoNow,
                spacingMillis = FIELD_CLOCK_SPACING_MILLIS,
            ),
            carryMonitor = get(),
            activityMonitor = get(),
            permissions = {
                // The platform's words win (location: always / when_in_use / denied); the common ones are the floor.
                mapOf(
                    PermFields.LOCATION to if (locationProvider.hasPermission()) "on" else "denied",
                    PermFields.BLUETOOTH to radio.state.value.name.lowercase(),
                ) + appPermissions.states()
            },
            errorReporter = get(),
            // The round's pocket in the field build (wave 4): the iPhone's Live Activity, the proximity sensor's screen.
            pocket = FieldPocket(get(), get(), scope) {
                PocketTexts(
                    title = getString(Res.string.app_name),
                    hider = getString(Res.string.hud_you_hide),
                    seeker = getString(Res.string.hud_you_seek),
                    none = getString(Res.string.hud_radar_none),
                    warm = getString(Res.string.hud_radar_warm),
                    hot = getString(Res.string.hud_radar_hot),
                    burning = getString(Res.string.hud_radar_burning),
                )
            },
        ).also { session ->
            // Crash reports go out only while the tester's consent stands (docs/adr/0018-field-test-build.md §7).
            if (session.isFieldBuild) scope.launch { session.consentAt.collect { CrashReporting.allow(it != null) } }
        }
    }
    single { FieldMarks() }
    single { AccountManager(get(), get(), get()) }
    single { SocialManager(get(), get()) }
    single { HistoryManager(get(), get()) }
    single { CityLocator(get(), get()) }
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
            diagnostics = get(),
            trace = get<FieldSession>(),
        )
    }
    single { BigGameManager(get(), get()) }
    single { SpectatorManager(get(), get()) }

    viewModelOf(::WelcomeViewModel)
    viewModelOf(::VerifyEmailViewModel)
    viewModelOf(::MainViewModel)
    viewModelOf(::PlayViewModel)
    viewModelOf(::FriendsViewModel)
    viewModelOf(::LeaderboardViewModel)
    viewModelOf(::AchievementsViewModel)
    viewModelOf(::GroupsViewModel)
    viewModelOf(::ProfileViewModel)
    viewModelOf(::HistoryViewModel)
    viewModelOf(::LobbyViewModel)
    viewModelOf(::GameViewModel)
    viewModelOf(::ResultsViewModel)
    viewModelOf(::ChatViewModel)
    viewModelOf(::InviteBannerViewModel)
    viewModelOf(::SpectatorViewModel)
    viewModelOf(::DiagnosticsViewModel)
    viewModelOf(::LabViewModel)
}

/** Hands debug start parameters (UI automation) to the screens; see [LaunchOptions]. */
fun offerLaunchOptions(options: LaunchOptions) {
    KoinPlatformTools.defaultContext().get().get<LaunchOptionsHolder>().offer(options)
}

/** The field log's clock questions a quarter of a second apart (docs/adr/0018-field-test-build.md §3). */
private const val FIELD_CLOCK_SPACING_MILLIS = 250L

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
 * ([app.hovanki.radar.ProximityRadio]), what the phone is ([app.hovanki.device.DeviceInfo]) and its
 * motion sensors ([app.hovanki.device.ActivityMonitor]).
 */
expect val platformModule: Module
