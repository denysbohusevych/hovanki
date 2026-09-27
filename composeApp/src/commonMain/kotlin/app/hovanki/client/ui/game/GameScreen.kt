package app.hovanki.client.ui.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.automation.TestTags
import app.hovanki.client.catchcode.CatchCodeScanner
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_cancel
import app.hovanki.client.resources.action_confirm
import app.hovanki.client.resources.action_dispute
import app.hovanki.client.resources.action_leave
import app.hovanki.client.resources.back_in_zone
import app.hovanki.client.resources.building_rule_off
import app.hovanki.client.resources.catch_auto_in
import app.hovanki.client.resources.catch_seeker_hint
import app.hovanki.client.resources.chat_open
import app.hovanki.client.resources.claim_disputed_mine
import app.hovanki.client.resources.claim_enter_code
import app.hovanki.client.resources.claim_time_left
import app.hovanki.client.resources.hider_claim_hint
import app.hovanki.client.resources.hider_claim_title
import app.hovanki.client.resources.hider_code_next
import app.hovanki.client.resources.hider_disputed
import app.hovanki.client.resources.hint_hider_hiding
import app.hovanki.client.resources.hud_me
import app.hovanki.client.resources.hud_more
import app.hovanki.client.resources.ic_chat
import app.hovanki.client.resources.ic_exit
import app.hovanki.client.resources.ic_navigation
import app.hovanki.client.resources.in_building_revealed
import app.hovanki.client.resources.in_building_warning
import app.hovanki.client.resources.leave_text
import app.hovanki.client.resources.leave_text_account
import app.hovanki.client.resources.leave_title
import app.hovanki.client.resources.no_hiders_to_claim
import app.hovanki.client.resources.out_of_zone_warning
import app.hovanki.client.resources.seeker_found_title
import app.hovanki.client.resources.status_caught
import app.hovanki.client.resources.status_eliminated
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
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.PopSurface
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.SessionBanners
import app.hovanki.client.ui.common.Toast
import app.hovanki.client.ui.common.formatCountdown
import app.hovanki.client.ui.common.rememberHaptics
import app.hovanki.client.ui.common.rememberReduceMotion
import app.hovanki.client.ui.common.rememberToastVisible
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * The round (docs/design.md, «Игра»): the map over the whole screen with the HUD on it, and a sheet at the bottom for
 * what needs the player's hands (the seeker's catch, a vote). A claim against the hider covers everything with the
 * code to show.
 */
@Composable
fun GameScreen(viewModel: GameViewModel = koinViewModel(), chat: ChatViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val chatState by chat.uiState.collectAsStateWithLifecycle()
    val state = uiState
    if (state == null) {
        LoadingScreen()
        return
    }
    // The chat panel covers the round instead of replacing it: the map keeps its tiles and camera.
    Box(modifier = Modifier.fillMaxSize()) {
        GameContent(state, viewModel, chatUnread = chatState.unread, onOpenChat = chat::open)
        if (chatState.isOpen) {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
            ) {
                ChatPanel(chat)
            }
        }
    }
}

@Composable
private fun GameContent(state: GameUiState, viewModel: GameViewModel, chatUnread: Int, onOpenChat: () -> Unit) {
    var showLeaveDialog by rememberSaveable { mutableStateOf(false) }
    var recenter by remember { mutableIntStateOf(0) }
    val reduceMotion = rememberReduceMotion()
    GameHaptics(state)
    val alert = when {
        state.outOfZoneMillisLeft != null -> GameAlert.OUT_OF_ZONE
        state.insideBuildingMillisLeft != null -> GameAlert.IN_BUILDING
        else -> null
    }
    val claimAgainstMe = state.claimAgainstMe?.takeIf {
        it.status == CatchStatus.AWAITING_CODE && state.myStatus == PlayerStatus.ACTIVE
    }
    val hasSheet = hasBottomSheet(state)
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
    // Edge to edge: the map runs under the system bars, the HUD and the controls stay clear of them. Without the sheet
    // the controls and the map credit keep above the navigation bar; the sheet keeps clear of it (and the keyboard)
    // itself.
    val bottomInset = if (hasSheet) {
        PaddingValues(0.dp)
    } else {
        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()
    }

    Box(modifier = Modifier.fillMaxSize().testTag(TestTags.GAME_SCREEN)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                GameMap(
                    zone = state.zone,
                    cue = state.zoneMoment.cue,
                    myLocation = state.myLocation,
                    myRole = state.myRole,
                    markers = state.markers,
                    buildings = state.buildings,
                    recenterRequests = recenter,
                    reduceMotion = reduceMotion,
                    attributionPadding = bottomInset,
                    modifier = Modifier.fillMaxSize(),
                )
                if (state.myRole == Role.SEEKER && state.phase == GamePhase.HIDING) {
                    SeekerWaitLayer(millisLeft = state.phaseMillisLeft)
                }
                if (alert != null) EdgeVignette(alert, reduceMotion)
                // The HUD grows with the system font size up to 1.3×, so the capsule still fits.
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(HUD_MAX_FONT_SCALE)),
                ) {
                    TopHud(
                        state,
                        viewModel,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                            ),
                    )
                }
                Toast(
                    visible = rememberToastVisible(backInZone),
                    text = stringResource(Res.string.back_in_zone),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottomInset).padding(bottom = 132.dp),
                )
                BottomControls(
                    chatUnread = chatUnread,
                    onOpenChat = onOpenChat,
                    onRecenter = { recenter++ },
                    canRecenter = state.myLocation != null,
                    onMore = { showLeaveDialog = true },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottomInset).padding(bottom = 26.dp),
                )
            }
            if (hasSheet) BottomSheet(state, viewModel)
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
                codePeriodMillis = state.codePeriodMillis,
                claimTimeoutMillis = state.claimTimeoutMillis,
                isBusy = state.isBusy,
                onDispute = { viewModel.dispute(claim.id) },
            )
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
                Text(
                    if (state.hasAccount) {
                        stringResource(Res.string.leave_text_account, state.joinCode)
                    } else {
                        stringResource(Res.string.leave_text)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::leave) { Text(stringResource(Res.string.action_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }
}

/** The capsule, the chips, the alerts and the notices, stacked at the top of the map. */
@Composable
private fun TopHud(state: GameUiState, viewModel: GameViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HudCapsule(state)
        HudChips(state)
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
    }
}

/** Chat on the left, «where am I» in the middle, «more» (leaving the game) on the right. */
@Composable
private fun BottomControls(
    chatUnread: Int,
    onOpenChat: () -> Unit,
    onRecenter: () -> Unit,
    canRecenter: Boolean,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
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
        RoundControl(
            icon = Res.drawable.ic_navigation,
            label = stringResource(Res.string.hud_me),
            onClick = onRecenter,
            style = if (canRecenter) PopStyle.Primary else PopStyle.Quiet,
            size = 72.dp,
        )
        RoundControl(
            icon = Res.drawable.ic_exit,
            label = stringResource(Res.string.hud_more),
            onClick = onMore,
        )
    }
}

/**
 * Under the map: the seeker's catch, disputes to vote on, how the round ended for the player, the hint of the
 * hiding phase. Nothing when there is nothing to do: the map gets the room.
 */
@Composable
private fun BottomSheet(state: GameUiState, viewModel: GameViewModel) {
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
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
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
            if (seeking) SeekerCatch(state, viewModel)
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
private fun ColumnScope.SeekerCatch(state: GameUiState, viewModel: GameViewModel) {
    val claim = state.myClaim
    when {
        claim != null && claim.status == CatchStatus.AWAITING_CODE -> EnterCode(
            claim = claim,
            codeDigits = state.codeDigits,
            isBusy = state.isBusy,
            error = state.error,
            onConfirm = { code -> viewModel.confirmCatch(claim.id, code) },
            onScanned = { text -> viewModel.onCodeScanned(claim, text) },
        )

        claim != null -> Banner(text = stringResource(Res.string.claim_disputed_mine, claim.hiderName))

        else -> HiderChips(state.huntableHiders, isBusy = state.isBusy, onClaim = viewModel::claimCatch)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HiderChips(hiders: List<PlayerView>, isBusy: Boolean, onClaim: (PlayerId) -> Unit) {
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
            PopButton(
                text = hider.name,
                onClick = { onClaim(hider.id) },
                enabled = !isBusy,
                style = PopStyle.Seeker,
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
    onScanned: (String) -> Unit,
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
    CatchCodeScanner(onScanned = onScanned, modifier = Modifier.fillMaxWidth())
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
 * The hider's side of a catch, over the whole screen in violet: who says they caught the hider, the code as big
 * tiles (to read out, or for the seeker's camera once QR is there), when it changes, and «Dispute».
 */
@Composable
private fun ShowCodeLayer(
    claim: ClaimUi,
    code: CatchCode?,
    codePeriodMillis: Long,
    claimTimeoutMillis: Long,
    isBusy: Boolean,
    onDispute: () -> Unit,
) {
    KeepScreenBright()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Violet)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(Res.string.hider_claim_title, claim.seekerName),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
        )
        Text(
            text = stringResource(Res.string.hider_claim_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
        )
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
        Spacer(Modifier.weight(1f))
        if (code != null) {
            // TODO(QR): also render CatchCodePayload(gameId, myId, code).encode() as a QR code for the seeker's camera.
            CodeTiles(
                code = code.code,
                tag = TestTags.CATCH_CODE,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
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
        Spacer(Modifier.weight(1f))
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
 * Vibration for what happens in the round (docs/design.md, «Вибрация и звук»): the zone about to shrink (then 3-2-1)
 * and done shrinking, the seekers going out, alerts (on entering, then every 10 s, every second at the end), a claim
 * against the hider, a hider caught.
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
