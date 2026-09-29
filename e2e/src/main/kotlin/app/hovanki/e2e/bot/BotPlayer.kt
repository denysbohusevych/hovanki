package app.hovanki.e2e.bot

import app.hovanki.client.account.AccountManager
import app.hovanki.client.account.AccountState
import app.hovanki.client.bigGames.BigGameManager
import app.hovanki.client.history.HistoryManager
import app.hovanki.client.history.HistoryState
import app.hovanki.client.network.ApiException
import app.hovanki.client.network.ApiResult
import app.hovanki.client.network.HttpAccountApi
import app.hovanki.client.network.HttpBigGameApi
import app.hovanki.client.network.HttpGameApi
import app.hovanki.client.network.HttpHistoryApi
import app.hovanki.client.network.HttpSocialApi
import app.hovanki.client.network.HttpSpectatorApi
import app.hovanki.client.network.PollingGameConnection
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.createHttpClient
import app.hovanki.client.radio.NoopProximityRadio
import app.hovanki.client.radio.ProximityRadio
import app.hovanki.client.session.CatchCode
import app.hovanki.client.session.ChatLine
import app.hovanki.client.session.DraftZone
import app.hovanki.client.session.DraftZonePreview
import app.hovanki.client.session.DraftZoneState
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.session.ServerClock
import app.hovanki.client.session.SessionError
import app.hovanki.client.session.SessionState
import app.hovanki.client.session.catchCodeToShow
import app.hovanki.client.session.chatLines
import app.hovanki.client.session.myCatchCode
import app.hovanki.client.session.unreadChatCount
import app.hovanki.client.social.SocialManager
import app.hovanki.client.social.UserRelation
import app.hovanki.client.spectator.SpectatorManager
import app.hovanki.client.spectator.SpectatorState
import app.hovanki.client.storage.ClientStorage
import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.scenario.Timeline
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.Audience
import app.hovanki.shared.protocol.BigGameCard
import app.hovanki.shared.protocol.BigGameId
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Carry
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameRecording
import app.hovanki.shared.protocol.GameRoute
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.GroupView
import app.hovanki.shared.protocol.Inbox
import app.hovanki.shared.protocol.InviteId
import app.hovanki.shared.protocol.ItemId
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.PlaceItemRequest
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.QuestId
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.SpectatorSnapshot
import app.hovanki.shared.protocol.TracksResponse
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.protocol.WatchResponse
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.QuestCatalog
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A headless player: the app's real client stack ([GameSessionManager], [AccountManager], [SocialManager], the HTTP
 * APIs over Ktor/OkHttp, [PollingGameConnection], [ServerClock]) on a simulated phone ([FakeGps], [FakeNetwork],
 * [DeviceClock], [PhoneStorage]), wired like the app does it.
 *
 * Scenarios steer it like a person: walk, press buttons ([claimCatch], [dispute], [vote], [sendChat]), fill in forms
 * ([register], [logIn], [resetPassword]), read another phone's screen ([shownCode]), switch GPS or network off, kill
 * the app. [behavior] covers the reactions a person has without being told (show the code, vote). Every game response
 * it receives is checked by [SnapshotAudit]. The app keeps its session and account in [storage], so after [killApp]
 * and [launchApp] it is logged in again and resumes the game, like on a phone. What the person knows (nickname, email,
 * password) is not on the phone: the scenario keeps it ([BotAccount]).
 */
class BotPlayer(
    val name: String,
    start: GeoPoint,
    noise: GpsNoise,
    @Volatile var behavior: BotBehavior,
    private val serverUrl: String,
    private val timeline: Timeline,
    private val metrics: SyncMetrics? = null,
    /** Log observed state changes to the timeline; off for crowds (load tests). */
    private val logChanges: Boolean = true,
    /** What kind of phone: matters for who hears whom over Bluetooth ([RadioWorld]). */
    val platform: Platform = Platform.ANDROID,
    /** The air the phone's Bluetooth is in; null: a phone without the radar. */
    radioWorld: RadioWorld? = null,
) {
    val clock = DeviceClock()
    val gps = FakeGps(start, noise, clock)

    /** Where the phone is: in the hand or in the pocket (docs/adr/0012-nearby-radar.md, «Карман»). */
    val carry = MutableStateFlow(Carry.IN_HAND)
    val radio: ProximityRadio =
        radioWorld?.let { FakeRadio(it, platform, { gps.truePosition }, { carry.value }, clock::now) }
            ?: NoopProximityRadio()

    /** The pulse the phone beats with (docs/adr/0012-nearby-radar.md, «Пульс»). */
    val pulse = FakePocketPulse()
    val network = FakeNetwork(::onExchange)
    val backgroundTracker = FakeBackgroundTracker { running ->
        if (logChanges) log(if (running) "background tracking started" else "background tracking stopped")
    }
    val storage = PhoneStorage()

    private val violations = CopyOnWriteArrayList<String>()

    /** Privacy rule violations in anything this bot received (see [SnapshotAudit]). */
    val privacyViolations: List<String> get() = violations.toList()

    private val reveals = Collections.synchronizedSet(LinkedHashSet<Pair<PlayerId, VisibilityReason>>())

    /** Every (player, reason) this bot was ever shown a position for. */
    val revealsSeen: Set<Pair<PlayerId, VisibilityReason>> get() = synchronized(reveals) { reveals.toSet() }

    @Volatile private var app: App? = App()

    @Volatile var playerId: PlayerId? = null
        private set

    val id: PlayerId get() = checkNotNull(playerId) { "$name has not joined a game" }

    val isAppRunning: Boolean get() = app != null

    val state: SessionState get() = app?.session?.state?.value ?: SessionState()

    val snapshot: GameSnapshot? get() = state.snapshot

    /** "Now" as the app believes the server clock is; null while the app is not running. */
    fun serverNow(): Long? = app?.serverClock?.now()

    /** The band the phone beats with right now. */
    val pulseBand: RadarBand get() = pulse.band

    /** The Bluetooth switch of the phone; nothing on a phone without the radar. */
    fun turnBluetooth(on: Boolean) {
        (radio as? FakeRadio)?.state?.value = if (on) BluetoothState.ON else BluetoothState.OFF
    }

    /** The phone goes into a pocket (the screen off, the app in the background) or comes out into the hand. */
    fun putInPocket(inPocket: Boolean) {
        carry.value = if (inPocket) Carry.IN_POCKET else Carry.IN_HAND
        val running = app ?: return
        running.scope.launch { running.session.onScreenChanged(!inPocket) }
    }

    // ---- Game ----

    /** [leaveOtherGame]: the player confirmed leaving a round in progress elsewhere (their account plays one game). */
    suspend fun createGame(settings: GameSettings, leaveOtherGame: Boolean = false): CommandResult =
        command("creates a game") { it.create(name, settings, leaveOtherGame) }.also(::onEntered)

    /** Also how an invite is accepted: its join code, while logged in. */
    suspend fun join(joinCode: String, leaveOtherGame: Boolean = false): CommandResult =
        command("joins with code $joinCode") { it.join(joinCode, name, leaveOtherGame) }.also(::onEntered)

    suspend fun startGame(seekers: Collection<BotPlayer>): CommandResult =
        command("starts the game, seekers: ${seekers.joinToString { it.name }}") { session ->
            session.start(seekers.map { it.id })
        }

    /** The host taps the role pills: [seekers] seek, everybody sees it. */
    suspend fun picksSeekers(seekers: Collection<BotPlayer>): CommandResult =
        command("picks the seekers: ${seekers.joinToString { it.name }}") { it.setSeekers(seekers.map { p -> p.id }) }

    /** The host taps «Random»: the server draws [count] seekers. */
    suspend fun drawsSeekers(count: Int): CommandResult =
        command("draws $count seekers at random") { it.drawSeekers(count) }

    /** The host changes the setup in the lobby. */
    suspend fun changesSettings(settings: GameSettings): CommandResult =
        command("changes the settings") { it.updateSettings(settings) }

    /**
     * The host looks at [draft] in the settings before saving it, as the settings' map does
     * (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 2.3): the zone by streets the server built for
     * it; null when nothing came [within] (or there is nothing to build).
     */
    suspend fun looksAtDraft(draft: GameSettings, within: Duration = 30.seconds): DraftZone? {
        val running = app ?: run {
            notRunning("looks at a draft of the settings")
            return null
        }
        val preview = DraftZonePreview(running.scope, running.session::previewSettings)
        withContext(running.mainThread) { preview.show(draft, running.session.state.value.snapshot?.settings) }
        val zone = withTimeoutOrNull(within) { preview.zone.first { it?.state != DraftZoneState.LOADING } }
        withContext(running.mainThread) { preview.show(null, saved = null) }
        log("looks at a draft of the settings: the zone by streets ${zone?.state ?: "did not come"}")
        return zone
    }

    /**
     * The host taps the building at [point] on the map of the zone's buildings: it opens for hiding, or closes again
     * (docs/adr/0014-settings-lobby-redesign-open-buildings.md).
     */
    suspend fun togglesBuildingAt(point: GeoPoint): CommandResult =
        command("taps the building at ${point.lat}, ${point.lon}") { it.toggleOpenBuilding(point) }

    /** The host taps «Play anyway» on the warning of a crowded zone (docs/adr/0010-big-games.md). */
    suspend fun playsAnyway(): CommandResult = command("plays anyway in a crowded zone") { it.acceptCrowding() }

    suspend fun claimCatch(hider: BotPlayer): CommandResult = claimCatch(hider.id, hider.name)

    /** Claim on any player, e.g. one playing on a device. */
    suspend fun claimCatch(hiderId: PlayerId, hiderName: String = hiderId.value): CommandResult =
        command("claims a catch on $hiderName") { it.claimCatch(hiderId) }

    /** Types [code] into the open claim of this seeker. */
    suspend fun confirmCatch(code: String): CommandResult {
        val claim = snapshot?.catches?.lastOrNull { it.seekerId == playerId && it.status == CatchStatus.AWAITING_CODE }
            ?: return CommandResult.Rejected(null, "$name has no claim awaiting a code")
        return command("enters code $code") { it.confirmCatch(claim.id, code) }
    }

    /**
     * Sends [code] for claim [claimId], whoever's it is: what the app never offers for another seeker's claim, and a
     * modified app could still send.
     */
    suspend fun confirmCatch(claimId: CatchId, code: String): CommandResult =
        command("enters code $code for claim ${claimId.value}") { it.confirmCatch(claimId, code) }

    /** Presses "Dispute" on the claim against this hider. */
    suspend fun dispute(): CommandResult {
        val claim = snapshot?.catches?.lastOrNull { it.hiderId == playerId && it.status == CatchStatus.AWAITING_CODE }
            ?: return CommandResult.Rejected(null, "$name has no claim to dispute")
        return command("disputes the claim") { it.dispute(claim.id) }
    }

    /** Disputes claim [claimId], whoever it is against (see [confirmCatch] with a claim id). */
    suspend fun dispute(claimId: CatchId): CommandResult =
        command("disputes claim ${claimId.value}") { it.dispute(claimId) }

    // ---- The board, the quests and the perks (docs/adr/0013-quests-sparks-and-sensors.md) ----

    suspend fun placeItem(request: PlaceItemRequest): CommandResult =
        command("places a ${request.kind.name.lowercase().replace('_', ' ')} on the board") { it.placeItem(request) }

    suspend fun removeItem(itemId: ItemId): CommandResult =
        command("removes item ${itemId.value} from the board") { it.removeItem(itemId) }

    /** The camera read [text] at a checkpoint. */
    suspend fun scanCheckpoint(text: String): CommandResult =
        command("scans a checkpoint's code") { it.scanCheckpoint(text) }

    suspend fun usePerk(perk: PerkKind, targetId: PlayerId? = null, point: GeoPoint? = null): CommandResult =
        command("uses the perk ${perk.name.lowercase().replace('_', ' ')}") { it.usePerk(perk, targetId, point) }

    suspend fun addQuest(
        text: String,
        audience: Audience = Audience.ALL,
        sparks: Int = QuestCatalog.CUSTOM_DEFAULT_SPARKS,
    ): CommandResult = command("adds the quest «$text»") { it.addQuest(text, audience, sparks) }

    suspend fun questDone(questId: QuestId): CommandResult =
        command("says quest ${questId.value} is done") { it.questDone(questId) }

    suspend fun reviewQuest(questId: QuestId, playerId: PlayerId, approved: Boolean): CommandResult =
        command("${if (approved) "confirms" else "refuses"} quest ${questId.value} for ${playerName(playerId)}") {
            it.reviewQuest(questId, playerId, approved)
        }

    suspend fun vote(claimId: CatchId, confirm: Boolean): CommandResult =
        command(if (confirm) "votes to confirm" else "votes to reject") { it.vote(claimId, confirm) }

    /** What this hider's screen shows right now, if they decided to show it (see [ClaimReaction.ShowCode]). */
    fun shownCode(): CatchCode? {
        val running = app ?: return null
        val snapshot = running.session.state.value.snapshot ?: return null
        val claim = snapshot.catches.lastOrNull { it.hiderId == playerId && it.status == CatchStatus.AWAITING_CODE }
        if (claim == null || claim.id != running.showingCodeFor) return null
        return snapshot.catchCodeToShow(running.serverClock.now())
    }

    /** Opens «My code» on this hider's phone: the code shows without a claim, for the seeker's camera (one scan). */
    fun opensMyCode() {
        app?.showingMyCode = true
        log("shows the code without a claim")
    }

    fun closesMyCode() {
        app?.showingMyCode = false
        log("hides the code")
    }

    /** What this hider's «My code» shows right now, while it is open and the phone offers it. */
    fun shownMyCode(): CatchCode? {
        val running = app ?: return null
        if (!running.showingMyCode) return null
        val snapshot = running.session.state.value.snapshot ?: return null
        return snapshot.myCatchCode(running.serverClock.now())
    }

    /**
     * «Found!»: points the camera at [hider]'s screen and scans the QR code on it, «My code» or the code of a claim.
     * The camera reads [hider]'s id with the code, like the app's scanner.
     */
    suspend fun scansCodeOf(hider: BotPlayer): CommandResult {
        val code = hider.shownMyCode() ?: hider.shownCode()
            ?: return CommandResult.Rejected(null, "${hider.name} shows no code").also { log("$name finds no code") }
        return scanCatch(hider.id, code.code, hider.name)
    }

    /** One scan with [code], whatever it is: an old photo of a code, a code typed into a fake QR. */
    suspend fun scanCatch(hiderId: PlayerId, code: String, hiderName: String = hiderId.value): CommandResult =
        command("scans the code $code of $hiderName") { it.catchByScan(hiderId, code) }

    /** The replay the results screen shows: everybody's way through the round, once the app loaded it. */
    val tracks: TracksResponse? get() = state.tracks

    /**
     * Asks the server for the tracks directly, in any phase: what the app does only on the results screen and a
     * modified app could do earlier.
     */
    suspend fun requestsTracks(): CommandResult {
        val running = app ?: return notRunning("asks for the tracks")
        val session = running.session.state.value.session ?: return CommandResult.Failed("not in a game")
        val result = try {
            running.api.tracks(session)
            CommandResult.Ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            CommandResult.Rejected(e.error?.code, e.error?.message.orEmpty(), e.reason)
        } catch (e: Exception) {
            CommandResult.Failed(e.message)
        }
        log(if (result == CommandResult.Ok) "gets the tracks" else "asks for the tracks: $result")
        return result
    }

    /** Invites friends and/or everyone in a group into this game's lobby. */
    suspend fun invite(userIds: List<UserId> = emptyList(), groupId: GroupId? = null): CommandResult {
        val whom = (userIds.map(::nameOf) + listOfNotNull(groupId?.let { "group ${groupName(it)}" })).joinToString()
        return command("invites $whom") { it.invite(userIds, groupId) }
    }

    /** Leaves the game, or closes the results screen: the app stops polling and forgets the game. */
    suspend fun leave(): CommandResult {
        val running = app ?: return notRunning("leaves the game")
        withContext(running.mainThread) { running.session.leave() }
        log("leaves the game")
        return CommandResult.Ok
    }

    // ---- Chat ----

    /** The chat panel: this game's messages this player may see, minus those of users they blocked. */
    val chat: List<ChatLine> get() = state.chatLines(blockedIds)

    /** The number on the chat button. */
    val unreadChatCount: Int get() = state.unreadChatCount(blockedIds)

    /** Sends [text] to everyone, or to the own team ([team]). */
    suspend fun sendChat(text: String, team: Boolean = false): CommandResult =
        command(if (team) "says to the team: $text" else "says: $text") { it.sendChat(text, team) }

    /** Reports the chat message [seq] to the moderators. */
    suspend fun reportChat(seq: Long): CommandResult {
        val line = chat.firstOrNull { it.seq == seq }
        return command("reports message $seq${line?.let { " of ${it.senderName}: ${it.text}" }.orEmpty()}") {
            it.reportChat(seq)
        }
    }

    /** Opens the chat panel: everything in it counts as read. */
    suspend fun readChat() {
        val running = app ?: return
        withContext(running.mainThread) { running.session.markChatRead() }
    }

    // ---- Account ----

    val accountState: AccountState get() = app?.account?.state?.value ?: AccountState()

    /** The logged-in account; null for a guest (or while the app is not running). */
    val user: UserProfile? get() = accountState.user

    val userId: UserId? get() = user?.id

    /**
     * Signs up: logged in and usable right away. The email is unconfirmed; the code to confirm it (optional,
     * [verifyEmail]) goes to [BotAccount.email].
     */
    suspend fun register(account: BotAccount, language: String = "en"): CommandResult =
        accountCommand("registers as $account") {
            it.account.register(account.nickname, account.email, account.password, language)
        }

    /** Types the emailed code into the «confirm the email» panel (optional: the account works either way). */
    suspend fun verifyEmail(code: String): CommandResult =
        accountCommand("enters the email code $code") { it.account.verifyEmail(code) }

    /** "Send the code again". */
    suspend fun resendCode(): CommandResult = accountCommand("asks for a new email code") { it.account.resendCode() }

    /** Fixes a mistyped, not yet confirmed email; the app asks for the current [password] too. */
    suspend fun changeEmail(email: String, password: String): CommandResult =
        accountCommand("changes the email to $email") { it.account.changeEmail(email, password) }

    /** [login]: the nickname or the email. */
    suspend fun logIn(login: String, password: String): CommandResult =
        accountCommand("logs in as $login") { it.account.logIn(login, password) }

    /** "Forgot password?": a code to [email]. */
    suspend fun requestPasswordReset(email: String): CommandResult =
        accountCommand("asks for a password reset code for $email") { it.account.requestPasswordReset(email) }

    /** A new password with the emailed code; logged in afterwards. */
    suspend fun resetPassword(email: String, code: String, newPassword: String): CommandResult =
        accountCommand("sets a new password with the code $code") { it.account.resetPassword(email, code, newPassword) }

    suspend fun changePassword(currentPassword: String, newPassword: String): CommandResult =
        accountCommand("changes the password") { it.account.changePassword(currentPassword, newPassword) }

    suspend fun deleteAccount(password: String): CommandResult =
        accountCommand("deletes the account") { it.account.deleteAccount(password) }

    /** Reloads the profile (`GET /me`), like the app does at start: a revoked session logs the phone out. */
    suspend fun refreshAccount(): CommandResult = accountCommand("reloads the profile") { it.account.refresh() }

    /** Logs out at once; the server hears about it in the background. */
    suspend fun logOut(): CommandResult {
        val running = app ?: return notRunning("logs out")
        withContext(running.mainThread) { running.account.logOut() }
        log("logs out")
        return CommandResult.Ok
    }

    // ---- The player's own history (docs/adr/0007-game-history-and-routes.md) ----

    /** Statistics and games as the app last loaded them ([refreshHistory]). */
    val history: HistoryState get() = app?.history?.state?.value ?: HistoryState()

    /** The route the last [openRoute] showed; null until one loaded. */
    @Volatile var openedRoute: GameRoute? = null
        private set

    /** «Save my routes» in the profile, or on the results screen (on). */
    suspend fun setSaveRoutes(enabled: Boolean): CommandResult =
        apiCommand(if (enabled) "turns on «save my routes»" else "turns off «save my routes»") {
            it.history.setSaveRoutes(enabled)
        }

    /** Opens the profile's statistics and history. */
    suspend fun refreshHistory(): CommandResult = apiCommand("opens the game history") { it.history.refresh() }

    suspend fun openRoute(gameId: GameId): CommandResult = apiCommand("opens the route of game ${gameId.value}") {
        it.history.route(gameId).also { result -> if (result is ApiResult.Success) openedRoute = result.value }
    }

    suspend fun deleteRoute(gameId: GameId): CommandResult =
        apiCommand("deletes the route of game ${gameId.value}") { it.history.deleteRoute(gameId) }

    // ---- Big games (docs/adr/0010-big-games.md) ----

    /** The big games as the «Play» tab last loaded them ([refreshBigGames]). */
    val bigGames: List<BigGameCard> get() = app?.bigGames?.state?.value?.games.orEmpty()

    suspend fun refreshBigGames(): CommandResult = apiCommand("opens the big games") { it.bigGames.refresh() }

    suspend fun signsUpFor(id: BigGameId): CommandResult =
        apiCommand("signs up for big game ${id.value}") { it.bigGames.signUp(id) }

    suspend fun cancelsSignup(id: BigGameId): CommandResult =
        apiCommand("takes back the sign-up for big game ${id.value}") { it.bigGames.cancelSignup(id) }

    /** «Into the lobby» on the big game's card. */
    suspend fun joinBigGame(id: BigGameId, leaveOtherGame: Boolean = false): CommandResult =
        command("comes into the lobby of big game ${id.value}") { it.joinBigGame(id, leaveOtherGame) }
            .also(::onEntered)

    /** The recording the last [openRecording] showed; null until one loaded. */
    @Volatile var openedRecording: GameRecording? = null
        private set

    /** Opens a game's recording from the history (docs/adr/0011-spectators-and-recordings.md). */
    suspend fun openRecording(gameId: GameId): CommandResult =
        apiCommand("opens the recording of game ${gameId.value}") {
            it.history.recording(gameId).also { result ->
                if (result is ApiResult.Success) openedRecording = result.value
            }
        }

    // ---- Watching an open game (docs/adr/0011-spectators-and-recordings.md) ----

    /** What the spectator's screen shows; empty while not watching. */
    val watching: SpectatorState get() = app?.spectator?.state?.value ?: SpectatorState()

    /** «Watch» on the «Play» tab with [joinCode]. */
    suspend fun watch(joinCode: String): CommandResult =
        apiCommand("watches the game $joinCode") { it.spectator.watch(joinCode) }

    /** «Stop watching». */
    suspend fun stopWatching(): CommandResult {
        val running = app ?: return notRunning("stops watching")
        withContext(running.mainThread) { running.spectator.stop() }
        log("stops watching")
        return CommandResult.Ok
    }

    // ---- Friends, groups, invites ----

    /** Friends, requests both ways and blocked users as the app last loaded them; null until loaded. */
    val friends: FriendsResponse? get() = app?.social?.friends?.value

    /** The groups this player is in, as last loaded ([refreshGroups]). */
    val groups: List<GroupView> get() = app?.social?.groups?.value?.groups.orEmpty()

    /** Game invites and incoming friend requests, as last loaded ([refreshInbox]). */
    val inbox: Inbox get() = app?.social?.inbox?.value ?: Inbox()

    val blockedIds: Set<UserId> get() = app?.social?.blockedIds?.value.orEmpty()

    fun relationTo(userId: UserId): UserRelation = app?.social?.relationTo(userId) ?: UserRelation.NONE

    suspend fun refreshFriends(): CommandResult = socialCommand("opens the friends list") { it.refreshFriends() }

    suspend fun refreshGroups(): CommandResult = socialCommand("opens the groups") { it.refreshGroups() }

    suspend fun refreshInbox(): CommandResult = socialCommand("checks the inbox") { it.refreshInbox() }

    /**
     * Keeps a screen with the inbox open (the start screen): while it is open, the app polls the inbox by itself, like
     * the real one ([closesInbox]). Gone with the app process.
     */
    fun opensInbox() {
        val running = app ?: return
        running.inboxScreen?.cancel()
        running.inboxScreen = running.scope.launch { running.social.inbox.collect {} }
        log("opens the inbox")
    }

    fun closesInbox() {
        app?.inboxScreen?.cancel()
        app?.inboxScreen = null
        log("closes the inbox")
    }

    /** By the exact nickname, as typed into the friends screen. */
    suspend fun sendFriendRequest(nickname: String): CommandResult =
        socialCommand("asks $nickname to be friends") { it.sendFriendRequest(nickname) }

    /** To a player seen in a game ([app.hovanki.shared.protocol.PlayerView.userId]). */
    suspend fun sendFriendRequest(userId: UserId): CommandResult =
        socialCommand("asks ${nameOf(userId)} to be friends") { it.sendFriendRequest(userId) }

    suspend fun acceptFriendRequest(from: UserId): CommandResult =
        socialCommand("accepts the friend request of ${nameOf(from)}") { it.acceptFriendRequest(from) }

    /** Declines [userId]'s request, or withdraws the own request to them. */
    suspend fun declineFriendRequest(userId: UserId): CommandResult =
        socialCommand("declines the friend request of ${nameOf(userId)}") { it.declineFriendRequest(userId) }

    suspend fun removeFriend(userId: UserId): CommandResult =
        socialCommand("removes ${nameOf(userId)} from friends") { it.removeFriend(userId) }

    suspend fun block(userId: UserId): CommandResult = socialCommand("blocks ${nameOf(userId)}") { it.block(userId) }

    suspend fun unblock(userId: UserId): CommandResult =
        socialCommand("unblocks ${nameOf(userId)}") { it.unblock(userId) }

    /** A new group owned by this player; it is in [groups] afterwards. */
    suspend fun createGroup(groupName: String, memberIds: List<UserId> = emptyList()): CommandResult =
        socialCommand("creates the group $groupName with ${memberIds.joinToString { nameOf(it) }}") {
            it.createGroup(groupName, memberIds)
        }

    suspend fun addGroupMembers(groupId: GroupId, userIds: List<UserId>): CommandResult =
        socialCommand("adds ${userIds.joinToString { nameOf(it) }} to ${groupName(groupId)}") {
            it.addGroupMembers(groupId, userIds)
        }

    suspend fun removeGroupMember(groupId: GroupId, userId: UserId): CommandResult =
        socialCommand("removes ${nameOf(userId)} from ${groupName(groupId)}") { it.removeGroupMember(groupId, userId) }

    suspend fun leaveGroup(groupId: GroupId): CommandResult =
        socialCommand("leaves ${groupName(groupId)}") { it.leaveGroup(groupId) }

    suspend fun renameGroup(groupId: GroupId, newName: String): CommandResult =
        socialCommand("renames ${groupName(groupId)} to $newName") { it.renameGroup(groupId, newName) }

    suspend fun deleteGroup(groupId: GroupId): CommandResult =
        socialCommand("deletes ${groupName(groupId)}") { it.deleteGroup(groupId) }

    suspend fun dismissInvite(inviteId: InviteId): CommandResult =
        socialCommand("dismisses an invite") { it.dismissInvite(inviteId) }

    // ---- The phone ----

    /** Swipes the app away: the process with its outbox and connection is gone; GPS, network, clock and storage stay. */
    fun killApp() {
        val running = app ?: return
        app = null
        running.close()
        log("app killed")
    }

    /**
     * Starts the app again, like tapping its icon: a fresh process that restores the account and resumes the game
     * saved in [storage].
     */
    fun launchApp() {
        if (app != null) return
        app = App()
        log("app launched")
    }

    fun log(text: String) = timeline.log(name, text)

    fun close() {
        app?.close()
        app = null
    }

    private fun onEntered(result: CommandResult) {
        val running = app ?: return
        val session = running.session.state.value.session ?: return
        playerId = session.playerId
        // What the start screen does once a name worked; a logged-in player plays under the nickname instead.
        if (result == CommandResult.Ok && !running.account.state.value.isLoggedIn) {
            running.clientStorage.rememberPlayer(name)
        }
    }

    /** Someone's name as this player's screen shows it: from the friends list or the current game. */
    private fun playerName(playerId: PlayerId): String =
        snapshot?.players?.firstOrNull { it.id == playerId }?.name ?: playerId.value

    private fun nameOf(userId: UserId): String {
        val friends = friends
        val known = friends?.let { it.friends + it.incoming + it.outgoing + it.blocked }.orEmpty()
        return known.firstOrNull { it.id == userId }?.nickname
            ?: snapshot?.players?.firstOrNull { it.userId == userId }?.name
            ?: groups.flatMap { it.members }.firstOrNull { it.id == userId }?.nickname
            ?: userId.value
    }

    private fun groupName(groupId: GroupId): String = groups.firstOrNull { it.id == groupId }?.name ?: groupId.value

    private suspend fun command(description: String, call: suspend (GameSessionManager) -> Boolean): CommandResult {
        val running = app ?: return notRunning(description)
        val result = withContext(running.mainThread) {
            if (call(running.session)) {
                CommandResult.Ok
            } else {
                when (val error = running.session.state.value.lastError) {
                    is SessionError.Rejected ->
                        CommandResult.Rejected(
                            error.code,
                            error.message,
                            error.reason,
                            error.retryAfterSeconds,
                            error.untilMillis,
                        )

                    is SessionError.Network -> CommandResult.Failed(error.details)

                    SessionError.SessionLost -> CommandResult.Failed("session lost")

                    SessionError.SavedGameFinished -> CommandResult.Failed("saved game finished")

                    SessionError.SavedGameGone -> CommandResult.Failed("saved game gone")

                    null -> CommandResult.Failed("unknown")
                }
            }
        }
        log(if (result == CommandResult.Ok) description else "$description: $result")
        return result
    }

    private suspend fun accountCommand(description: String, call: suspend (App) -> ApiResult<*>): CommandResult =
        apiCommand(description, call)

    private suspend fun socialCommand(
        description: String,
        call: suspend (SocialManager) -> ApiResult<*>,
    ): CommandResult = apiCommand(description) { call(it.social) }

    /** An [AccountManager] or [SocialManager] command, on the app's main thread like a tap. */
    private suspend fun apiCommand(description: String, call: suspend (App) -> ApiResult<*>): CommandResult {
        val running = app ?: return notRunning(description)
        val result = when (val outcome = withContext(running.mainThread) { call(running) }) {
            is ApiResult.Success -> CommandResult.Ok

            is ApiResult.Rejected ->
                CommandResult.Rejected(
                    outcome.code,
                    outcome.message,
                    outcome.reason,
                    outcome.retryAfterSeconds,
                    outcome.untilMillis,
                )

            is ApiResult.Network -> CommandResult.Failed(outcome.details)
        }
        log(if (result == CommandResult.Ok) description else "$description: $result")
        return result
    }

    private fun notRunning(description: String): CommandResult {
        log("$description: app not running")
        return CommandResult.Failed("the app is not running")
    }

    private fun onExchange(exchange: Exchange) {
        metrics?.record(exchange)
        val body = exchange.body ?: return
        if (exchange.status != 200 || !exchange.path.startsWith(ApiRoutes.GAMES)) return
        // Building outlines and the zone by streets (the host's draft's too) are map data, not a snapshot; the tracks
        // come only after the round (the server refuses them before, see PrivacyTest).
        val notSnapshots = listOf("/buildings", "/street-zone", "/settings/preview", "/tracks")
        if (notSnapshots.any(exchange.path::endsWith)) return
        // A spectator's view (docs/adr/0011-spectators-and-recordings.md): nothing newer than the delay allows.
        if (exchange.path == ApiRoutes.WATCH || exchange.path.endsWith(SPECTATE_SUFFIX)) {
            val view = try {
                if (exchange.path == ApiRoutes.WATCH) {
                    protocolJson.decodeFromString<WatchResponse>(body).snapshot
                } else {
                    protocolJson.decodeFromString<SpectatorSnapshot>(body)
                }
            } catch (e: SerializationException) {
                violations += "$name: unreadable response from ${exchange.path}: ${e.message}"
                return
            }
            SnapshotAudit.checkSpectator(view).forEach { violations += "$name: $it" }
            return
        }
        val snapshot = try {
            if (exchange.path == ApiRoutes.GAMES || exchange.path == ApiRoutes.JOIN) {
                protocolJson.decodeFromString<SessionResponse>(body).snapshot
            } else {
                protocolJson.decodeFromString<GameSnapshot>(body)
            }
        } catch (e: SerializationException) {
            violations += "$name: unreadable response from ${exchange.path}: ${e.message}"
            return
        }
        SnapshotAudit.check(snapshot, body).forEach { violations += "$name: $it" }
        snapshot.players.forEach { player -> player.location?.let { reveals += player.id to it.exactReason } }
    }

    /** One run of the app process, wired like the app's DI (composeApp `Koin.kt`) and started like `onAppStart`. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private inner class App {
        /** The app's "main thread": the managers are confined to it, as on the phone. */
        val mainThread = Dispatchers.Default.limitedParallelism(1)
        val scope = CoroutineScope(SupervisorJob() + mainThread)
        private val httpClient = createHttpClient(OkHttp.create { addInterceptor(network) }, logRequests = false)
        val serverClock = ServerClock(clock::now)
        private val url = ServerUrl(serverUrl)
        val api = HttpGameApi(httpClient, url)
        val clientStorage = ClientStorage(storage)
        val account = AccountManager(HttpAccountApi(httpClient, url), clientStorage, url, scope)
        val social = SocialManager(HttpSocialApi(httpClient, url), account, scope)
        val history = HistoryManager(HttpHistoryApi(httpClient, url), account, scope)
        val bigGames = BigGameManager(HttpBigGameApi(httpClient, url), account, scope)
        val spectator = SpectatorManager(HttpSpectatorApi(httpClient, url), account, scope)
        val session = GameSessionManager(
            api,
            PollingGameConnection(api),
            serverClock,
            gps,
            backgroundTracker,
            url,
            clientStorage,
            scope,
            account = account,
            radio = radio,
            deviceInfo = BotDeviceInfo(platform),
            pocketPulse = pulse,
            carryMonitor = FakeCarryMonitor(carry),
        )

        @Volatile var showingCodeFor: CatchId? = null

        /** «My code» is open (one scan). */
        @Volatile var showingMyCode = false

        /** A screen that shows the inbox, while open ([opensInbox]). */
        @Volatile var inboxScreen: Job? = null
        private val handledClaims = HashSet<CatchId>()
        private val handledVotes = HashSet<CatchId>()
        private var previous = SessionState()
        private var previousAccount = AccountState()

        init {
            scope.launch {
                session.state.collect { state ->
                    if (logChanges) logChanges(previous, state)
                    react(state)
                    previous = state
                }
            }
            scope.launch {
                account.state.collect { state ->
                    if (logChanges) logAccountChanges(previousAccount, state)
                    previousAccount = state
                }
            }
            // What the app does at start: back into the saved account, then into a saved game.
            scope.launch {
                account.restore()
                session.resumeSavedGame()
            }
        }

        fun close() {
            scope.cancel()
            httpClient.close()
        }

        private fun react(state: SessionState) {
            val snapshot = state.snapshot ?: return
            val me = snapshot.me.playerId
            val claim = snapshot.catches.lastOrNull { it.hiderId == me && it.status == CatchStatus.AWAITING_CODE }
            if (claim != null && handledClaims.add(claim.id)) {
                when (val reaction = behavior.onClaim) {
                    is ClaimReaction.ShowCode -> scope.launch {
                        delay(reaction.after)
                        showingCodeFor = claim.id
                        log("shows the catch code")
                    }

                    is ClaimReaction.Dispute -> scope.launch {
                        delay(reaction.after)
                        command("disputes the claim") { it.dispute(claim.id) }
                    }

                    ClaimReaction.Ignore -> log("ignores the claim")
                }
            }
            for (dispute in snapshot.catches.filter { it.canVote }) {
                val reaction = behavior.onDispute as? VoteReaction.Vote ?: continue
                if (!handledVotes.add(dispute.id)) continue
                scope.launch {
                    delay(reaction.after)
                    vote(dispute.id, reaction.confirm)
                }
            }
        }

        private fun logAccountChanges(before: AccountState, after: AccountState) {
            val user = after.user
            when {
                before.user?.id != user?.id && user != null ->
                    log("is logged in as ${user.nickname}" + if (user.emailVerified) "" else " (email not confirmed)")

                before.user != null && user == null ->
                    log(if (after.sessionExpired) "is logged out: the server ended the session" else "is logged out")

                before.user?.emailVerified == false && user?.emailVerified == true -> log("email confirmed")
            }
        }

        private fun logChanges(before: SessionState, after: SessionState) {
            if (!before.isResuming && after.isResuming) log("resumes the saved game")
            if (before.isResuming && !after.isResuming && after.session != null) log("is back in the game")
            if (before.connectionStatus != after.connectionStatus) log("connection ${after.connectionStatus}")
            val error = after.lastError
            if (error != null && error != before.lastError) log("error: $error")
            val old = before.snapshot
            val new = after.snapshot ?: return
            val names = new.players.associate { it.id to it.name }
            if (old?.phase != new.phase) log("sees phase ${new.phase}")
            if (old != null && old.me.status != new.me.status) log("is ${new.me.status}")
            val deadline = new.me.outOfZoneDeadlineMillis
            val buildingReveal = new.me.insideBuildingRevealAtMillis
            if (old?.me?.insideBuildingRevealAtMillis == null && buildingReveal != null) {
                log("warned: inside a building, seen in ${(buildingReveal - new.serverTimeMillis) / 1000} s")
            } else if (old?.me?.insideBuildingRevealAtMillis != null && buildingReveal == null) {
                log("building warning lifted")
            }
            if (old?.me?.outOfZoneDeadlineMillis == null && deadline != null) {
                log("warned: outside the zone, ${(deadline - new.serverTimeMillis) / 1000} s to return")
            } else if (old?.me?.outOfZoneDeadlineMillis != null && deadline == null) {
                log("out-of-zone warning lifted")
            }
            val oldCatches = old?.catches.orEmpty().associate { it.id to it.status }
            for (claim in new.catches) {
                if (oldCatches[claim.id] != claim.status) {
                    log("sees claim ${names[claim.seekerId]} → ${names[claim.hiderId]}: ${claim.status}")
                }
            }
            val oldVisible = old?.players.orEmpty().mapNotNull { p ->
                p.location?.let { p.id to it.exactReason }
            }.toMap()
            val newVisible = new.players.mapNotNull { p -> p.location?.let { p.id to it.exactReason } }.toMap()
            for ((id, reason) in newVisible) if (oldVisible[id] != reason) log("sees ${names[id]} ($reason)")
            for (id in oldVisible.keys - newVisible.keys) log("no longer sees ${names[id]}")
            val seen = before.chat.lastOrNull()?.seq ?: 0L
            val arrived = after.chat.filter { it.seq > seen }
            for (line in chatLines(arrived, new.players, new.me.playerId, social.blockedIds.value)) {
                if (!line.isMine) log("reads ${line.senderName} (${line.channel}): ${line.text}")
            }
        }
    }
}

/** The spectator's view of a game: `ApiRoutes.SPECTATE` ends with it. */
private const val SPECTATE_SUFFIX = "/spectate"

sealed interface CommandResult {
    data object Ok : CommandResult

    /**
     * The server refused; [code] is its [ErrorCode], [reason] the exact cause when it sent one, [retryAfterSeconds]
     * when to try again after a rate limit.
     */
    data class Rejected(
        val code: ErrorCode?,
        val message: String,
        val reason: ErrorReason? = null,
        val retryAfterSeconds: Long? = null,
        /** When a ban or a chat ban ends; null: forever, or not one. */
        val untilMillis: Long? = null,
    ) : CommandResult

    /** Network or local failure. */
    data class Failed(val details: String?) : CommandResult
}
