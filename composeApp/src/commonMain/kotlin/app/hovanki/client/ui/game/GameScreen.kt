package app.hovanki.client.ui.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.automation.TestTags
import app.hovanki.client.catchcode.CatchCodeScanner
import app.hovanki.client.catchcode.QrCodeImage
import app.hovanki.client.lab.FieldSession
import app.hovanki.client.lab.FieldStatus
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_cancel
import app.hovanki.client.resources.action_confirm
import app.hovanki.client.resources.action_dispute
import app.hovanki.client.resources.action_leave
import app.hovanki.client.resources.action_leave_and_go
import app.hovanki.client.resources.back_in_zone
import app.hovanki.client.resources.building_rule_off
import app.hovanki.client.resources.catch_auto_in
import app.hovanki.client.resources.catch_or_say
import app.hovanki.client.resources.catch_or_type
import app.hovanki.client.resources.catch_pick_name
import app.hovanki.client.resources.catch_seeker_hint
import app.hovanki.client.resources.chat_open
import app.hovanki.client.resources.checkpoint_scan_close
import app.hovanki.client.resources.checkpoint_scan_hint
import app.hovanki.client.resources.checkpoint_taken
import app.hovanki.client.resources.claim_disputed_mine
import app.hovanki.client.resources.claim_enter_code
import app.hovanki.client.resources.claim_time_left
import app.hovanki.client.resources.field_wrong_menu
import app.hovanki.client.resources.hider_claim_hint
import app.hovanki.client.resources.hider_claim_title
import app.hovanki.client.resources.hider_code_next
import app.hovanki.client.resources.hider_disputed
import app.hovanki.client.resources.hider_qr
import app.hovanki.client.resources.hint_hider_hiding
import app.hovanki.client.resources.hud_bluetooth_off
import app.hovanki.client.resources.hud_bluetooth_off_now
import app.hovanki.client.resources.hud_catch
import app.hovanki.client.resources.hud_me
import app.hovanki.client.resources.hud_more
import app.hovanki.client.resources.hud_my_code
import app.hovanki.client.resources.ic_camera
import app.hovanki.client.resources.ic_chat
import app.hovanki.client.resources.ic_close
import app.hovanki.client.resources.ic_exit
import app.hovanki.client.resources.ic_eye_off
import app.hovanki.client.resources.ic_navigation
import app.hovanki.client.resources.ic_qr
import app.hovanki.client.resources.in_building_revealed
import app.hovanki.client.resources.in_building_warning
import app.hovanki.client.resources.invite_in_round
import app.hovanki.client.resources.leave_text
import app.hovanki.client.resources.leave_text_account
import app.hovanki.client.resources.leave_title
import app.hovanki.client.resources.my_code_close
import app.hovanki.client.resources.my_code_hint
import app.hovanki.client.resources.my_code_title
import app.hovanki.client.resources.no_hiders_to_claim
import app.hovanki.client.resources.out_of_zone_warning
import app.hovanki.client.resources.perk_pick_point
import app.hovanki.client.resources.perk_put_here
import app.hovanki.client.resources.scanner_close
import app.hovanki.client.resources.scanner_hint
import app.hovanki.client.resources.scanner_open
import app.hovanki.client.resources.seeker_found_title
import app.hovanki.client.resources.status_caught
import app.hovanki.client.resources.status_eliminated
import app.hovanki.client.resources.street_zone_off
import app.hovanki.client.resources.vote_confirm
import app.hovanki.client.resources.vote_done
import app.hovanki.client.resources.vote_reject
import app.hovanki.client.resources.vote_title
import app.hovanki.client.session.CatchCode
import app.hovanki.client.session.SessionError
import app.hovanki.client.session.ZoneCue
import app.hovanki.client.ui.chat.ChatPanel
import app.hovanki.client.ui.chat.ChatViewModel
import app.hovanki.client.ui.common.Banner
import app.hovanki.client.ui.common.CapsText
import app.hovanki.client.ui.common.CountdownRing
import app.hovanki.client.ui.common.Haptic
import app.hovanki.client.ui.common.KeepScreenBright
import app.hovanki.client.ui.common.LoadingScreen
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopIconButton
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.PopSurface
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.SessionBanners
import app.hovanki.client.ui.common.SystemBackHandler
import app.hovanki.client.ui.common.Toast
import app.hovanki.client.ui.common.appSafeDrawing
import app.hovanki.client.ui.common.appSafeDrawingPadding
import app.hovanki.client.ui.common.bandTitle
import app.hovanki.client.ui.common.formatCountdown
import app.hovanki.client.ui.common.rememberHaptics
import app.hovanki.client.ui.common.rememberReduceMotion
import app.hovanki.client.ui.common.rememberToastVisible
import app.hovanki.client.ui.field.FieldMarks
import app.hovanki.client.ui.field.PocketHint
import app.hovanki.client.ui.invite.InviteBannerViewModel
import app.hovanki.client.ui.theme.Motion
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.GameInvite
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.protocol.Role
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * The round (docs/design.md, «Игра»): the map over the whole screen with the HUD on it, and a sheet at the bottom for
 * what needs the player's hands (the seeker's catch, a vote). A claim against the hider covers everything with the
 * code to show.
 */
@Composable
fun GameScreen(
    viewModel: GameViewModel = koinViewModel(),
    chat: ChatViewModel = koinViewModel(),
    invites: InviteBannerViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val chatState by chat.uiState.collectAsStateWithLifecycle()
    // An invitation into another game: no banner in a round, only a badge on «More» (see the leave dialog).
    val invite by invites.invite.collectAsStateWithLifecycle()
    val state = uiState
    if (state == null) {
        LoadingScreen()
        return
    }
    // The chat panel covers the round instead of replacing it: the map keeps its tiles and camera.
    Box(modifier = Modifier.fillMaxSize()) {
        GameContent(
            state,
            viewModel,
            chatUnread = chatState.unread,
            onOpenChat = chat::open,
            invite = invite,
            onGoToInvite = { invites.go(it, leaveRound = true) },
        )
        if (chatState.isOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .appSafeDrawingPadding(),
            ) {
                ChatPanel(chat)
            }
        }
    }
}

@Composable
private fun GameContent(
    state: GameUiState,
    viewModel: GameViewModel,
    chatUnread: Int,
    onOpenChat: () -> Unit,
    invite: GameInvite?,
    onGoToInvite: (GameInvite) -> Unit,
) {
    var showLeaveDialog by rememberSaveable { mutableStateOf(false) }
    // The field test build's «Something is wrong» in the menu, while its log runs (nothing in other builds).
    val fieldSession = koinInject<FieldSession>()
    val fieldMarks = koinInject<FieldMarks>()
    val fieldState by fieldSession.state.collectAsStateWithLifecycle()
    var recenter by remember { mutableIntStateOf(0) }
    val reduceMotion = rememberReduceMotion()
    GameHaptics(state)
    val alert = when {
        state.outOfZoneMillisLeft != null -> GameAlert.OUT_OF_ZONE
        state.insideBuildingMillisLeft != null -> GameAlert.IN_BUILDING
        state.bluetoothMillisLeft != null -> GameAlert.BLUETOOTH_OFF
        else -> null
    }
    // The quests and the perks (docs/adr/0013) as panels over the round; the decoy is placed on the map itself.
    var panel by remember { mutableStateOf<GamePanel?>(null) }
    var placingDecoy by remember { mutableStateOf(false) }
    var decoyPick by remember { mutableStateOf<GeoPoint?>(null) }
    // The camera at a checkpoint's code, while there is one left to scan.
    var scanningCheckpoint by remember { mutableStateOf(false) }
    LaunchedEffect(state.canScanCheckpoint) { if (!state.canScanCheckpoint) scanningCheckpoint = false }
    // A checkpoint reached (by GPS or by its code): a toast and a vibration.
    var checkpointsSeen by remember { mutableIntStateOf(state.checkpointsTaken) }
    var checkpointToasts by remember { mutableIntStateOf(0) }
    val claimAgainstMe = state.claimAgainstMe?.takeIf {
        it.status == CatchStatus.AWAITING_CODE && state.myStatus == PlayerStatus.ACTIVE
    }
    val hasSheet = hasBottomSheet(state)
    // The seeker's camera for the hider's QR code, while that claim waits for the code.
    var scanning by remember { mutableStateOf<CatchId?>(null) }
    val scannedClaim = state.myClaim?.takeIf { it.id == scanning && it.status == CatchStatus.AWAITING_CODE }
    // «Found!»: the camera with no claim yet (one scan), while the seeker can still find somebody.
    var scanningFree by remember { mutableStateOf(false) }
    val freeScan = scanningFree && state.canScan
    // «My code»: the hider shows the code without a claim; a claim or the end of the search takes it away.
    var showingMyCode by rememberSaveable { mutableStateOf(false) }
    val myCodeShown = showingMyCode && state.myCode != null
    LaunchedEffect(state.myCode == null) { if (state.myCode == null) showingMyCode = false }
    val main = mainControl(
        state,
        onFound = { scanningFree = true },
        onMyCode = { showingMyCode = true },
    )
    // Where the HUD ends and the controls begin: the arrow back into the zone keeps between them.
    var hudBottom by remember { mutableIntStateOf(0) }
    var controlsTop by remember { mutableIntStateOf(0) }
    var recenterTop by remember { mutableIntStateOf(Int.MAX_VALUE) }
    var mapHeight by remember { mutableIntStateOf(0) }
    var cameraBearing by remember { mutableStateOf(0.0) }
    // Back inside after being out: a toast and a short vibration.
    val isOut = state.outOfZoneMillisLeft != null
    var wasOut by remember { mutableStateOf(isOut) }
    var backInZone by remember { mutableIntStateOf(0) }
    val haptics = rememberHaptics()
    LaunchedEffect(isOut) {
        if (wasOut && !isOut && state.myStatus == PlayerStatus.ACTIVE) {
            backInZone++
            haptics(Haptic.TICK)
        }
        wasOut = isOut
    }
    LaunchedEffect(state.checkpointsTaken) {
        if (state.checkpointsTaken > checkpointsSeen) {
            checkpointToasts++
            haptics(Haptic.SUCCESS)
        }
        checkpointsSeen = state.checkpointsTaken
    }
    // Edge to edge: the map runs under the system bars, the HUD and the controls stay clear of them. Without the sheet
    // the controls and the map credit keep above the navigation bar; the sheet keeps clear of it (and the keyboard)
    // itself.
    val bottomInset = if (hasSheet) {
        PaddingValues(0.dp)
    } else {
        WindowInsets.appSafeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()
    }

    Box(modifier = Modifier.fillMaxSize().testTag(TestTags.GAME_SCREEN)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                GameMap(
                    zone = state.zoneTimeline,
                    serverNow = viewModel::serverNow,
                    cue = state.zoneMoment.cue,
                    myLocation = state.myLocation,
                    myRole = state.myRole,
                    markers = state.markers,
                    buildings = state.buildings,
                    recenterRequests = recenter,
                    reduceMotion = reduceMotion,
                    attributionPadding = bottomInset,
                    onCameraBearing = { cameraBearing = it },
                    items = state.items,
                    pickedPoint = decoyPick.takeIf { placingDecoy },
                    onMapClick = if (placingDecoy) {
                        { point -> decoyPick = point }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxSize().onSizeChanged { mapHeight = it.height },
                )
                if (state.myRole == Role.SEEKER && state.phase == GamePhase.HIDING) {
                    SeekerWaitLayer(millisLeft = state.phaseMillisLeft)
                }
                if (alert != null) EdgeVignette(alert, reduceMotion)
                val density = LocalDensity.current
                state.bearingToZone?.let { bearing ->
                    ZoneArrow(
                        angleDegrees = (bearing - cameraBearing).toFloat(),
                        top = with(density) { hudBottom.toDp() },
                        bottom = with(density) {
                            val top = if (main != null) minOf(controlsTop, recenterTop) else controlsTop
                            (mapHeight - top).coerceAtLeast(0).toDp()
                        },
                        reduceMotion = reduceMotion,
                    )
                }
                // The HUD grows with the system font size up to 1.3×, so the capsule still fits.
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(HUD_MAX_FONT_SCALE)),
                ) {
                    TopHud(
                        state,
                        viewModel,
                        reduceMotion = reduceMotion,
                        onOpenQuests = { panel = GamePanel.QUESTS },
                        onOpenPerks = { panel = GamePanel.PERKS },
                        onScanCheckpoint = { scanningCheckpoint = true },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .onGloballyPositioned { hudBottom = it.boundsInParent().bottom.toInt() }
                            .windowInsetsPadding(
                                WindowInsets.appSafeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                            ),
                    )
                }
                // The field build: «put the phone in your pocket unlocked» once the proximity sensor's screen is on.
                PocketHint(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = with(density) { hudBottom.toDp() })
                        .padding(horizontal = 16.dp),
                )
                Toast(
                    visible = rememberToastVisible(backInZone),
                    text = stringResource(Res.string.back_in_zone),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottomInset).padding(bottom = 132.dp),
                )
                Toast(
                    visible = rememberToastVisible(checkpointToasts),
                    text = stringResource(Res.string.checkpoint_taken),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottomInset).padding(bottom = 132.dp),
                )
                if (placingDecoy) {
                    DecoyBar(
                        canPut = decoyPick != null && !state.isBusy,
                        onPut = {
                            decoyPick?.let { viewModel.usePerk(PerkKind.DECOY, point = it) }
                            placingDecoy = false
                            decoyPick = null
                        },
                        onCancel = {
                            placingDecoy = false
                            decoyPick = null
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottomInset)
                            .padding(start = 16.dp, end = 16.dp, bottom = 26.dp)
                            .onGloballyPositioned { controlsTop = it.boundsInParent().top.toInt() },
                    )
                } else {
                    BottomControls(
                        chatUnread = chatUnread,
                        onOpenChat = onOpenChat,
                        main = main,
                        onRecenter = { recenter++ },
                        canRecenter = state.myLocation != null,
                        onMore = { showLeaveDialog = true },
                        moreBadge = if (invite != null) 1 else 0,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottomInset)
                            .padding(bottom = 26.dp)
                            .onGloballyPositioned { controlsTop = it.boundsInParent().top.toInt() },
                    )
                }
                // With a main action in the middle, «where am I» moves up to the side, like in map apps.
                if (main != null && !placingDecoy) {
                    PopIconButton(
                        icon = Res.drawable.ic_navigation,
                        contentDescription = stringResource(Res.string.hud_me),
                        onClick = { recenter++ },
                        style = if (state.myLocation != null) PopStyle.Outline else PopStyle.Quiet,
                        size = 48.dp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottomInset)
                            .padding(end = 16.dp, bottom = 136.dp)
                            .onGloballyPositioned { recenterTop = it.boundsInParent().top.toInt() },
                    )
                }
            }
            if (hasSheet) BottomSheet(state, viewModel, onOpenScanner = { scanning = state.myClaim?.id })
        }

        // The last claim, kept while its layer slides out after the claim is gone.
        var shownClaim by remember { mutableStateOf(claimAgainstMe) }
        if (claimAgainstMe != null) shownClaim = claimAgainstMe
        AnimatedVisibility(
            visible = claimAgainstMe != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            val claim = shownClaim ?: return@AnimatedVisibility
            ShowCodeLayer(
                claim = claim,
                code = state.catchCode,
                qr = state.catchQr,
                codePeriodMillis = state.codePeriodMillis,
                claimTimeoutMillis = state.claimTimeoutMillis,
                isBusy = state.isBusy,
                onDispute = { viewModel.dispute(claim.id) },
            )
        }
        // The code without a claim, kept while its layer slides out (caught meanwhile, or the search is over).
        var shownMyCode by remember { mutableStateOf(state.myCode to state.myQr) }
        if (state.myCode != null) shownMyCode = state.myCode to state.myQr
        AnimatedVisibility(
            visible = myCodeShown,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            MyCodeLayer(
                code = shownMyCode.first,
                qr = shownMyCode.second,
                codePeriodMillis = state.codePeriodMillis,
                onClose = { showingMyCode = false },
            )
        }
        AnimatedVisibility(
            visible = scannedClaim != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            ScannerLayer(
                onScanned = { text -> scannedClaim != null && viewModel.onCodeScanned(scannedClaim, text) },
                onClose = { scanning = null },
                manualText = stringResource(Res.string.catch_or_type),
            )
        }
        AnimatedVisibility(
            visible = freeScan,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            ScannerLayer(
                onScanned = viewModel::onFreeScan,
                onClose = { scanningFree = false },
                manualText = stringResource(Res.string.catch_pick_name),
            )
        }
        AnimatedVisibility(
            visible = scanningCheckpoint && state.canScanCheckpoint,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            ScannerLayer(
                onScanned = viewModel::onCheckpointScanned,
                onClose = { scanningCheckpoint = false },
                manualText = stringResource(Res.string.checkpoint_scan_close),
                hint = stringResource(Res.string.checkpoint_scan_hint),
            )
        }
        panel?.let { open ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .appSafeDrawingPadding(),
            ) {
                when (open) {
                    GamePanel.QUESTS -> QuestsPanel(state, viewModel, onClose = { panel = null })

                    GamePanel.PERKS -> PerksPanel(
                        state,
                        viewModel,
                        onClose = { panel = null },
                        onPickDecoy = {
                            panel = null
                            decoyPick = null
                            placingDecoy = true
                        },
                    )
                }
            }
        }
        PhaseFlash(phase = state.phase, role = state.myRole, reduceMotion = reduceMotion)
        if (state.myRole == Role.SEEKER) CatchCelebration(state.myConfirmedCatches, reduceMotion)
        if (state.myRole == Role.HIDER) CaughtLayer(state.myStatus)
        StartCountdown(state.hidingElapsedMillis, state.myRole, reduceMotion)
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(Res.string.leave_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (state.hasAccount) {
                            stringResource(Res.string.leave_text_account, state.joinCode)
                        } else {
                            stringResource(Res.string.leave_text)
                        },
                    )
                    // The invitation behind the badge on «More»: leaving this round can take the player there.
                    invite?.let {
                        Text(
                            text = stringResource(Res.string.invite_in_round, it.from.nickname),
                            color = Palette.PinkInk,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    if (fieldState.status == FieldStatus.ON) {
                        TextButton(
                            onClick = {
                                showLeaveDialog = false
                                fieldMarks.request()
                            },
                        ) { Text(stringResource(Res.string.field_wrong_menu)) }
                    }
                }
            },
            confirmButton = {
                Row {
                    invite?.let {
                        TextButton(
                            onClick = {
                                showLeaveDialog = false
                                onGoToInvite(it)
                            },
                            modifier = Modifier.testTag(TestTags.LEAVE_AND_GO),
                        ) { Text(stringResource(Res.string.action_leave_and_go)) }
                    }
                    TextButton(onClick = viewModel::leave) { Text(stringResource(Res.string.action_leave)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }
}

/** A panel over the round: the quests or the perks (docs/adr/0013-quests-sparks-and-sensors.md). */
private enum class GamePanel { QUESTS, PERKS }

/** The capsule, the chips, the alerts and the notices, stacked at the top of the map. */
@Composable
private fun TopHud(
    state: GameUiState,
    viewModel: GameViewModel,
    reduceMotion: Boolean,
    onOpenQuests: () -> Unit,
    onOpenPerks: () -> Unit,
    onScanCheckpoint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HudCapsule(state)
        HudChips(
            state,
            reduceMotion = reduceMotion,
            onOpenQuests = onOpenQuests,
            onOpenPerks = onOpenPerks,
            onScanCheckpoint = onScanCheckpoint,
        )
        state.bluetoothMillisLeft?.let { millisLeft ->
            AlertPill(
                alert = GameAlert.BLUETOOTH_OFF,
                text = if (millisLeft > 0) {
                    stringResource(Res.string.hud_bluetooth_off, formatCountdown(millisLeft))
                } else {
                    stringResource(Res.string.hud_bluetooth_off_now)
                },
                tag = TestTags.GAME_BLUETOOTH_OFF,
            )
        }
        state.outOfZoneMillisLeft?.let { millisLeft ->
            AlertPill(
                alert = GameAlert.OUT_OF_ZONE,
                text = stringResource(Res.string.out_of_zone_warning, formatCountdown(millisLeft)),
                tag = TestTags.GAME_OUT_OF_ZONE,
            )
        }
        state.insideBuildingMillisLeft?.let { millisLeft ->
            AlertPill(
                alert = GameAlert.IN_BUILDING,
                text = if (millisLeft > 0) {
                    stringResource(Res.string.in_building_warning, formatCountdown(millisLeft))
                } else {
                    stringResource(Res.string.in_building_revealed)
                },
                tag = TestTags.GAME_IN_BUILDING,
            )
        }
        SessionBanners(
            connectionStatus = state.connectionStatus,
            isSharingLocation = state.isSharingLocation,
            error = state.error,
            onDismissError = viewModel::dismissError,
            onLocationPermissionGranted = viewModel::onLocationPermissionGranted,
        )
        if (state.isBuildingRuleOff) {
            Banner(
                text = stringResource(Res.string.building_rule_off),
                modifier = Modifier.testTag(TestTags.BUILDING_RULE_OFF),
            )
        }
        if (state.isStreetZoneOff) {
            Banner(
                text = stringResource(Res.string.street_zone_off),
                modifier = Modifier.testTag(TestTags.STREET_ZONE_OFF),
            )
        }
    }
}

/** The big button in the middle of the controls: the role's main action right now. */
private class MainControl(
    val icon: DrawableResource,
    val label: String,
    val style: PopStyle,
    val tag: String,
    val onClick: () -> Unit,
)

/** The seeker's «Found!» (one scan) or the hider's «My code», when they can use it; else null. */
@Composable
private fun mainControl(state: GameUiState, onFound: () -> Unit, onMyCode: () -> Unit): MainControl? = when {
    state.canScan -> MainControl(
        icon = Res.drawable.ic_camera,
        label = stringResource(Res.string.hud_catch),
        style = PopStyle.Seeker,
        tag = TestTags.FOUND_OPEN,
        onClick = onFound,
    )

    state.myCode != null -> MainControl(
        icon = Res.drawable.ic_qr,
        label = stringResource(Res.string.hud_my_code),
        style = PopStyle.Hider,
        tag = TestTags.MY_CODE_OPEN,
        onClick = onMyCode,
    )

    else -> null
}

/**
 * Chat on the left, «more» (leaving the game) on the right; in the middle the role's [main] action, or «where am I»
 * when there is none.
 */
@Composable
private fun BottomControls(
    chatUnread: Int,
    onOpenChat: () -> Unit,
    main: MainControl?,
    onRecenter: () -> Unit,
    canRecenter: Boolean,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    moreBadge: Int = 0,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(26.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        RoundControl(
            icon = Res.drawable.ic_chat,
            label = stringResource(Res.string.chat_open),
            onClick = onOpenChat,
            badge = chatUnread,
            badgeTag = TestTags.CHAT_UNREAD,
            buttonModifier = Modifier.testTag(TestTags.CHAT_OPEN),
        )
        if (main != null) {
            RoundControl(
                icon = main.icon,
                label = main.label,
                onClick = main.onClick,
                style = main.style,
                size = 72.dp,
                buttonModifier = Modifier.testTag(main.tag),
            )
        } else {
            RoundControl(
                icon = Res.drawable.ic_navigation,
                label = stringResource(Res.string.hud_me),
                onClick = onRecenter,
                style = if (canRecenter) PopStyle.Primary else PopStyle.Quiet,
                size = 72.dp,
            )
        }
        RoundControl(
            icon = Res.drawable.ic_exit,
            label = stringResource(Res.string.hud_more),
            onClick = onMore,
            badge = moreBadge,
            badgeTag = TestTags.ROUND_INVITE,
        )
    }
}

/**
 * Under the map: the seeker's catch, disputes to vote on, how the round ended for the player, the hint of the
 * hiding phase. Nothing when there is nothing to do: the map gets the room.
 */
@Composable
private fun BottomSheet(state: GameUiState, viewModel: GameViewModel, onOpenScanner: () -> Unit) {
    val status = statusText(state)
    val hint = if (state.myStatus == PlayerStatus.ACTIVE && state.phase == GamePhase.HIDING) {
        stringResource(Res.string.hint_hider_hiding)
    } else {
        null
    }
    val seeking = isSeekingNow(state)
    val hiderDisputed = isHiderDisputed(state)

    PopSurface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = Palette.Paper,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.appSafeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                )
                .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Palette.Line),
            )
            status?.let { (text, tag) -> Banner(text = text, modifier = Modifier.testTag(tag)) }
            hint?.let { Text(text = it, style = MaterialTheme.typography.titleMedium) }
            if (hiderDisputed) Banner(text = stringResource(Res.string.hider_disputed))
            if (seeking) SeekerCatch(state, viewModel, onOpenScanner)
            state.votes.forEach { claim ->
                VoteCard(
                    claim = claim,
                    voteTimeoutMillis = state.voteTimeoutMillis,
                    isBusy = state.isBusy,
                    onVote = { confirm -> viewModel.vote(claim.id, confirm) },
                )
            }
        }
    }
}

/** Whether the sheet under the map has anything: see [BottomSheet]. */
private fun hasBottomSheet(state: GameUiState): Boolean = state.myStatus != PlayerStatus.ACTIVE ||
    // The seeker waits out the hiding phase on SeekerWaitLayer.
    (state.phase == GamePhase.HIDING && state.myRole == Role.HIDER) ||
    isSeekingNow(state) ||
    state.votes.isNotEmpty() ||
    isHiderDisputed(state)

private fun isSeekingNow(state: GameUiState): Boolean =
    state.myRole == Role.SEEKER && state.myStatus == PlayerStatus.ACTIVE && state.phase == GamePhase.SEEKING

private fun isHiderDisputed(state: GameUiState): Boolean =
    state.myRole == Role.HIDER && state.claimAgainstMe?.status == CatchStatus.DISPUTED

/** «You were found» or «you are out», with its tag. */
@Composable
private fun statusText(state: GameUiState): Pair<String, String>? = when (state.myStatus) {
    PlayerStatus.ACTIVE -> null
    PlayerStatus.CAUGHT -> stringResource(Res.string.status_caught) to TestTags.GAME_CAUGHT
    PlayerStatus.ELIMINATED -> stringResource(Res.string.status_eliminated) to TestTags.GAME_ELIMINATED
}

/** The seeker: tap who was caught, then type their code; or wait for the vote on a disputed claim. */
@Composable
private fun ColumnScope.SeekerCatch(state: GameUiState, viewModel: GameViewModel, onOpenScanner: () -> Unit) {
    val claim = state.myClaim
    when {
        claim != null && claim.status == CatchStatus.AWAITING_CODE -> EnterCode(
            claim = claim,
            codeDigits = state.codeDigits,
            isBusy = state.isBusy,
            error = state.error,
            onConfirm = { code -> viewModel.confirmCatch(claim.id, code) },
            onOpenScanner = onOpenScanner,
        )

        claim != null -> Banner(text = stringResource(Res.string.claim_disputed_mine, claim.hiderName))

        else -> HiderChips(state.huntableHiders, state.radar, isBusy = state.isBusy, onClaim = viewModel::claimCatch)
    }
}

/** The decoy (docs/adr/0013): tap the map, then «Put it here»; in place of the controls meanwhile. */
@Composable
private fun DecoyBar(canPut: Boolean, onPut: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    PopSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Palette.Violet,
        shadow = 4.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(Res.string.perk_pick_point),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PopButton(
                    text = stringResource(Res.string.perk_put_here),
                    onClick = onPut,
                    enabled = canPut,
                    height = 48.dp,
                    modifier = Modifier.weight(1f).testTag(TestTags.DECOY_PUT),
                )
                PopButton(
                    text = stringResource(Res.string.action_cancel),
                    onClick = onCancel,
                    style = PopStyle.Outline,
                    height = 48.dp,
                    modifier = Modifier.testTag(TestTags.DECOY_CANCEL),
                )
            }
        }
    }
}

/** The hiders to claim; with the radar (docs/adr/0012), how warm each one is on it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HiderChips(
    hiders: List<PlayerView>,
    radar: Map<PlayerId, RadarBand>,
    isBusy: Boolean,
    onClaim: (PlayerId) -> Unit,
) {
    Text(text = stringResource(Res.string.seeker_found_title), style = MaterialTheme.typography.headlineSmall)
    if (hiders.isEmpty()) {
        SecondaryText(stringResource(Res.string.no_hiders_to_claim))
        return
    }
    SecondaryText(stringResource(Res.string.catch_seeker_hint))
    // Wrapped, not scrolled: every hider stays on screen, however many there are.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        hiders.forEach { hider ->
            val band = radar[hider.id] ?: RadarBand.NONE
            PopButton(
                text = if (band == RadarBand.NONE) hider.name else "${hider.name} · ${bandTitle(band)}",
                onClick = { onClaim(hider.id) },
                enabled = !isBusy,
                style = if (band >= RadarBand.HOT) PopStyle.Pink else PopStyle.Seeker,
                height = 52.dp,
                modifier = Modifier.testTag(TestTags.claimButton(hider.id)),
            )
        }
    }
    // Room for the shadows of the chips.
    Spacer(Modifier.height(2.dp))
}

/** The seeker's side of a catch: scan the QR code or type the digits the hider shows. */
@Composable
private fun EnterCode(
    claim: ClaimUi,
    codeDigits: Int,
    isBusy: Boolean,
    error: SessionError?,
    onConfirm: (String) -> Unit,
    onOpenScanner: () -> Unit,
) {
    var code by rememberSaveable(claim.id.value) { mutableStateOf("") }
    var shakes by remember { mutableIntStateOf(0) }
    val haptics = rememberHaptics()
    // The server refused the code: shake the tiles and let the seeker type again.
    LaunchedEffect(error) {
        if (error is SessionError.Rejected) {
            shakes++
            code = ""
            haptics(Haptic.ERROR)
        }
    }
    Text(
        text = stringResource(Res.string.claim_enter_code, claim.hiderName),
        style = MaterialTheme.typography.titleMedium,
    )
    claim.millisLeft?.let { millisLeft ->
        CapsText(stringResource(Res.string.claim_time_left, formatCountdown(millisLeft)), color = Palette.OrangeInk)
    }
    PopButton(
        text = stringResource(Res.string.scanner_open),
        onClick = onOpenScanner,
        enabled = !isBusy,
        style = PopStyle.Outline,
        height = 48.dp,
        icon = Res.drawable.ic_qr,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.SCANNER_OPEN),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        CodeInput(
            value = code,
            onValueChange = { code = it },
            digits = codeDigits,
            enabled = !isBusy,
            shakes = shakes,
            tag = TestTags.CODE_INPUT,
        )
    }
    PopButton(
        text = stringResource(Res.string.action_confirm),
        onClick = { onConfirm(code) },
        enabled = code.length == codeDigits && !isBusy,
        style = PopStyle.Seeker,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.CODE_CONFIRM),
    )
}

@Composable
private fun VoteCard(claim: ClaimUi, voteTimeoutMillis: Long, isBusy: Boolean, onVote: (confirm: Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(Res.string.vote_title, claim.seekerName, claim.hiderName),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            claim.millisLeft?.let { millisLeft ->
                CountdownRing(
                    progress = if (voteTimeoutMillis > 0) millisLeft.toFloat() / voteTimeoutMillis else 0f,
                    text = formatCountdown(millisLeft),
                    color = Palette.Orange,
                    trackColor = Palette.Line,
                    textColor = Palette.Ink,
                    size = 48.dp,
                )
            }
        }
        if (claim.canVote) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PopButton(
                    text = stringResource(Res.string.vote_confirm),
                    onClick = { onVote(true) },
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f).testTag(TestTags.voteConfirm(claim.id)),
                )
                PopButton(
                    text = stringResource(Res.string.vote_reject),
                    onClick = { onVote(false) },
                    enabled = !isBusy,
                    style = PopStyle.Outline,
                    modifier = Modifier.weight(1f).testTag(TestTags.voteReject(claim.id)),
                )
            }
        } else {
            SecondaryText(stringResource(Res.string.vote_done))
        }
    }
}

/**
 * The hider's side of a catch, over the whole screen in violet: who says they caught the hider, how long until it
 * counts by itself, the code and «Dispute».
 */
@Composable
private fun ShowCodeLayer(
    claim: ClaimUi,
    code: CatchCode?,
    qr: String?,
    codePeriodMillis: Long,
    claimTimeoutMillis: Long,
    isBusy: Boolean,
    onDispute: () -> Unit,
) {
    CodeLayer(
        title = stringResource(Res.string.hider_claim_title, claim.seekerName),
        hint = stringResource(Res.string.hider_claim_hint),
        code = code,
        qr = qr,
        codePeriodMillis = codePeriodMillis,
        codeTag = TestTags.CATCH_CODE,
        qrTag = TestTags.CATCH_QR,
        top = {
            claim.millisLeft?.let { millisLeft ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(Res.string.catch_auto_in, formatCountdown(millisLeft)),
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                    val fraction = if (claimTimeoutMillis > 0) {
                        (millisLeft.toFloat() / claimTimeoutMillis).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Palette.Ink),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(Palette.Lime),
                        )
                    }
                }
            }
        },
    ) {
        PopButton(
            text = stringResource(Res.string.action_dispute),
            onClick = onDispute,
            enabled = !isBusy,
            style = PopStyle.Dark,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.CATCH_DISPUTE),
        )
    }
}

/**
 * «My code»: the hider shows the code without a claim (one scan), when a seeker really found them; the seeker's camera
 * confirms the catch at once. Back or «Hide the code» closes it.
 */
@Composable
private fun MyCodeLayer(code: CatchCode?, qr: String?, codePeriodMillis: Long, onClose: () -> Unit) {
    SystemBackHandler(enabled = true, onBack = onClose)
    CodeLayer(
        title = stringResource(Res.string.my_code_title),
        hint = stringResource(Res.string.my_code_hint),
        code = code,
        qr = qr,
        codePeriodMillis = codePeriodMillis,
        codeTag = TestTags.MY_CODE_DIGITS,
        qrTag = null,
        modifier = Modifier.testTag(TestTags.MY_CODE),
    ) {
        PopButton(
            text = stringResource(Res.string.my_code_close),
            onClick = onClose,
            style = PopStyle.Dark,
            icon = Res.drawable.ic_eye_off,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.MY_CODE_CLOSE),
        )
    }
}

/**
 * The catch code over the whole screen in violet: [title] and [hint], what [top] adds, the QR code as big as the room
 * allows (for the seeker's camera), the digits as tiles (to read out) with the ring until the next code, and [bottom].
 */
@Composable
private fun CodeLayer(
    title: String,
    hint: String,
    code: CatchCode?,
    qr: String?,
    codePeriodMillis: Long,
    codeTag: String,
    qrTag: String?,
    modifier: Modifier = Modifier,
    top: @Composable ColumnScope.() -> Unit = {},
    bottom: @Composable ColumnScope.() -> Unit,
) {
    KeepScreenBright()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Palette.Violet)
            .appSafeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Text(text = hint, style = MaterialTheme.typography.bodyLarge, color = Color.White)
        top()
        if (qr != null) {
            // As big as the room allows: the seeker's camera reads it from a step away.
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(
                    targetState = qr,
                    animationSpec = tween(Motion.FAST_MILLIS),
                    modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                ) { text ->
                    PopSurface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White,
                        shadow = 4.dp,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        QrCodeImage(
                            text = text,
                            contentDescription = stringResource(Res.string.hider_qr),
                            color = Palette.Ink,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(6.dp)
                                .then(if (qrTag != null) Modifier.testTag(qrTag) else Modifier),
                        )
                    }
                }
            }
            Text(
                text = stringResource(Res.string.catch_or_say),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (code != null) {
            CodeTiles(code = code.code, tag = codeTag, modifier = Modifier.align(Alignment.CenterHorizontally))
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CountdownRing(
                    progress = if (codePeriodMillis > 0) code.millisUntilNext.toFloat() / codePeriodMillis else 0f,
                    text = formatCountdown(code.millisUntilNext),
                    color = Palette.Lime,
                    trackColor = Palette.Ink,
                    textColor = Color.White,
                    size = 52.dp,
                )
                Text(
                    text = stringResource(Res.string.hider_code_next, formatCountdown(code.millisUntilNext)),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    textAlign = TextAlign.Start,
                )
            }
        }
        // Without the QR code the tiles sit in the middle; with it the code takes the room.
        if (qr == null) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(4.dp))
        bottom()
    }
}

/**
 * The seeker's camera over the whole round: the hider's QR code in the frame confirms the catch
 * ([GameViewModel.onCodeScanned] for an open claim, [GameViewModel.onFreeScan] for one scan); [manualText] goes back
 * to typing the digits or picking the name.
 */
@Composable
private fun ScannerLayer(
    onScanned: (String) -> Boolean,
    onClose: () -> Unit,
    manualText: String,
    hint: String = stringResource(Res.string.scanner_hint),
) {
    SystemBackHandler(enabled = true, onBack = onClose)
    Box(modifier = Modifier.fillMaxSize().background(Palette.Ink).testTag(TestTags.SCANNER)) {
        CatchCodeScanner(
            onScanned = { text -> if (onScanned(text)) onClose() },
            modifier = Modifier.fillMaxSize(),
        )
        // Where to hold the code.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(250.dp)
                .border(4.dp, Palette.Lime, RoundedCornerShape(28.dp)),
        )
        Column(
            modifier = Modifier.fillMaxSize().appSafeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PopIconButton(
                    icon = Res.drawable.ic_close,
                    contentDescription = stringResource(Res.string.scanner_close),
                    onClick = onClose,
                    style = PopStyle.Outline,
                    size = 44.dp,
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.weight(1f))
            PopButton(
                text = manualText,
                onClick = onClose,
                style = PopStyle.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Vibration for what happens in the round (docs/design.md, «Вибрация и звук»): the zone about to shrink (then 3-2-1)
 * and done shrinking, the seekers going out, alerts (on entering, then every 10 s, every second at the end), a claim
 * against the hider, a hider caught, the glow (a hider still playing feels it coming, 3-2-1, and starting; a seeker
 * feels it starting).
 */
@Composable
private fun GameHaptics(state: GameUiState) {
    val play = rememberHaptics()
    val cue = state.zoneMoment.cue
    LaunchedEffect(cue) {
        when (cue) {
            ZoneCue.SOON -> play(Haptic.TICK)
            ZoneCue.SHRUNK -> play(Haptic.SUCCESS)
            else -> Unit
        }
    }
    val secondsToShrink = state.zoneMoment.millisLeft?.takeIf { cue == ZoneCue.COUNTDOWN }?.let { (it + 999) / 1000 }
    LaunchedEffect(secondsToShrink) {
        if (secondsToShrink != null && secondsToShrink <= COUNTDOWN_TICKS) play(Haptic.TICK)
    }

    var phase by remember { mutableStateOf(state.phase) }
    LaunchedEffect(state.phase) {
        if (phase == GamePhase.HIDING && state.phase == GamePhase.SEEKING) play(Haptic.SUCCESS)
        phase = state.phase
    }

    val outSeconds = state.outOfZoneMillisLeft?.let { (it + 999) / 1000 }
    LaunchedEffect(outSeconds != null) { if (outSeconds != null) play(Haptic.ERROR) }
    LaunchedEffect(outSeconds) {
        if (outSeconds != null &&
            (outSeconds <= LAST_SECONDS || outSeconds % OUT_OF_ZONE_EVERY == 0L)
        ) {
            play(Haptic.TICK)
        }
    }
    val buildingSeconds = state.insideBuildingMillisLeft?.takeIf { it > 0 }?.let { (it + 999) / 1000 }
    LaunchedEffect(state.insideBuildingMillisLeft != null) {
        if (state.insideBuildingMillisLeft != null) play(Haptic.ERROR)
    }
    LaunchedEffect(buildingSeconds) {
        if (buildingSeconds != null && buildingSeconds % IN_BUILDING_EVERY == 0L) play(Haptic.TICK)
    }

    val isHiding = state.myRole == Role.HIDER && state.myStatus == PlayerStatus.ACTIVE
    val glowing = state.glow?.isGlowing == true
    LaunchedEffect(glowing) {
        if (glowing) play(if (isHiding) Haptic.ERROR else Haptic.SUCCESS)
    }
    val secondsToGlow = state.glow?.takeIf { !it.isGlowing && isHiding }?.let { (it.millisLeft + 999) / 1000 }
    LaunchedEffect(secondsToGlow) {
        if (secondsToGlow != null && secondsToGlow <= COUNTDOWN_TICKS) play(Haptic.TICK)
    }

    val claimAgainstMe = state.claimAgainstMe?.takeIf { it.status == CatchStatus.AWAITING_CODE }?.id
    LaunchedEffect(claimAgainstMe) { if (claimAgainstMe != null) play(Haptic.HEAVY) }
    var hidersLeft by remember { mutableIntStateOf(state.hidersLeft) }
    LaunchedEffect(state.hidersLeft) {
        if (state.hidersLeft < hidersLeft && state.myRole == Role.SEEKER) play(Haptic.SUCCESS)
        hidersLeft = state.hidersLeft
    }
    LaunchedEffect(state.myStatus) { if (state.myStatus == PlayerStatus.CAUGHT) play(Haptic.HEAVY) }
}

private const val COUNTDOWN_TICKS = 3
private const val HUD_MAX_FONT_SCALE = 1.3f
private const val LAST_SECONDS = 5
private const val OUT_OF_ZONE_EVERY = 10
private const val IN_BUILDING_EVERY = 15
