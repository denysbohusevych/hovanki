package app.hovanki.client.ui.play

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.big_games_title
import app.hovanki.client.resources.history_players
import app.hovanki.client.resources.home_code_label
import app.hovanki.client.resources.home_create
import app.hovanki.client.resources.home_create_hint
import app.hovanki.client.resources.home_have_code
import app.hovanki.client.resources.home_join_short
import app.hovanki.client.resources.home_location_note
import app.hovanki.client.resources.home_watch
import app.hovanki.client.resources.home_watch_hint
import app.hovanki.client.resources.ic_arrow_right
import app.hovanki.client.resources.ic_chevron_right
import app.hovanki.client.resources.ic_close
import app.hovanki.client.resources.ic_eye
import app.hovanki.client.resources.ic_qr
import app.hovanki.client.resources.invite_accept
import app.hovanki.client.resources.invite_dismiss
import app.hovanki.client.resources.invite_from
import app.hovanki.client.resources.invite_from_group
import app.hovanki.client.resources.last_game_results
import app.hovanki.client.resources.last_game_title
import app.hovanki.client.resources.play_hello
import app.hovanki.client.resources.play_subtitle
import app.hovanki.client.resources.tab_profile
import app.hovanki.client.ui.common.Avatar
import app.hovanki.client.ui.common.CommandStatus
import app.hovanki.client.ui.common.Logo
import app.hovanki.client.ui.common.PopBorder
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopIconButton
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.PopSurface
import app.hovanki.client.ui.common.PopTextField
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.SectionTitle
import app.hovanki.client.ui.common.StartStatusBanners
import app.hovanki.client.ui.common.collectScreenState
import app.hovanki.client.ui.common.formatCountdown
import app.hovanki.client.ui.common.formatDateTime
import app.hovanki.client.ui.common.rememberLocationRequest
import app.hovanki.client.ui.history.HistoryEvent
import app.hovanki.client.ui.history.HistoryUiState
import app.hovanki.client.ui.history.outcome
import app.hovanki.client.ui.theme.Hovanki
import app.hovanki.client.ui.theme.Motion
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.verify.ConfirmEmailCard
import app.hovanki.client.ui.verify.VerifyEmailEvent
import app.hovanki.client.ui.verify.VerifyEmailUiState
import app.hovanki.shared.protocol.BigGameCard
import app.hovanki.shared.protocol.GameHistoryEntry
import app.hovanki.shared.protocol.GameInvite
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * «Play»: a hello, the offer to confirm the email (until confirmed or «Later»), invites, then the big «Create a game»
 * card, joining a game by its code (docs/design.md, «Главная»), and the big games to sign up for
 * (docs/adr/0010-big-games.md).
 */
@Composable
fun PlayTab(
    invites: List<GameInvite>,
    verify: VerifyEmailUiState,
    onVerifyEvent: (VerifyEmailEvent) -> Unit,
    history: HistoryUiState,
    onHistoryEvent: (HistoryEvent) -> Unit,
    onOpenProfile: () -> Unit,
    viewModel: PlayViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectScreenState()
    val onEvent = viewModel::onEvent
    val status = state.startStatus
    val requestLocationThenCreate = rememberLocationRequest { granted -> onEvent(PlayEvent.CreateGame(granted)) }
    // Asked before joining as well, so location is already on when the round starts.
    val requestLocationThenJoin = rememberLocationRequest { onEvent(PlayEvent.JoinGame) }
    var acceptedInvite by remember { mutableStateOf<GameInvite?>(null) }
    val requestLocationThenAccept = rememberLocationRequest {
        acceptedInvite?.let { onEvent(PlayEvent.AcceptInvite(it)) }
    }
    var joinedBigGame by remember { mutableStateOf<BigGameCard?>(null) }
    val requestLocationThenJoinBigGame = rememberLocationRequest {
        joinedBigGame?.let { onEvent(PlayEvent.JoinBigGame(it)) }
    }
    val isBusy = status.isBusy
    // The code field stays folded until asked for, or until a code is there (a link, the launch options).
    var codeOpen by rememberSaveable { mutableStateOf(state.joinCode.isNotEmpty()) }
    LaunchedEffect(state.joinCode.isNotEmpty()) { if (state.joinCode.isNotEmpty()) codeOpen = true }
    val userId = state.user?.id
    LaunchedEffect(userId) { if (userId != null) onHistoryEvent(HistoryEvent.Refresh) }

    ScreenColumn(modifier = Modifier.testTag(TestTags.HOME_SCREEN)) {
        state.user?.let { user ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(modifier = Modifier.weight(1f))
                val profile = stringResource(Res.string.tab_profile)
                PopSurface(
                    modifier = Modifier.size(48.dp).semantics { contentDescription = profile },
                    shape = CircleShape,
                    onClick = onOpenProfile,
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = user.nickname.trim().take(1).uppercase().ifEmpty { "?" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Column {
                Text(
                    text = stringResource(Res.string.play_hello, user.nickname),
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(Res.string.play_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.Ink2,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (!user.emailVerified && !verify.isCardDismissed) {
                ConfirmEmailCard(
                    email = user.email,
                    onOpen = { onVerifyEvent(VerifyEmailEvent.Open) },
                    onLater = { onVerifyEvent(VerifyEmailEvent.DismissCard) },
                )
            }
        }
        if (invites.isNotEmpty()) {
            invites.forEach { invite ->
                InviteCard(
                    invite = invite,
                    isBusy = isBusy,
                    onAccept = {
                        acceptedInvite = invite
                        requestLocationThenAccept()
                    },
                    onDismiss = { onEvent(PlayEvent.DismissInvite(invite)) },
                )
            }
        }

        CreateGameCard(
            enabled = !isBusy,
            onClick = requestLocationThenCreate,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp).testTag(TestTags.HOME_CREATE),
        )

        HaveCodeCard(
            expanded = codeOpen,
            onToggle = { codeOpen = !codeOpen },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                PopTextField(
                    value = state.joinCode,
                    onValueChange = { onEvent(PlayEvent.EditJoinCode(it)) },
                    label = { Text(stringResource(Res.string.home_code_label)) },
                    singleLine = true,
                    enabled = !isBusy,
                    textStyle = Hovanki.text.code.copy(fontSize = 20.sp, letterSpacing = 4.sp),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    modifier = Modifier.weight(1f).testTag(TestTags.HOME_JOIN_CODE),
                )
                PopButton(
                    text = stringResource(Res.string.home_join_short),
                    onClick = {
                        onEvent(PlayEvent.CheckJoinCode)
                        if (state.canJoin) requestLocationThenJoin()
                    },
                    enabled = !isBusy,
                    style = PopStyle.Dark,
                    modifier = Modifier.testTag(TestTags.HOME_JOIN),
                )
            }
            SecondaryText(stringResource(Res.string.home_location_note))
            // An open game can be watched without playing (docs/adr/0011-spectators-and-recordings.md).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryText(stringResource(Res.string.home_watch_hint), Modifier.weight(1f))
                PopButton(
                    text = stringResource(Res.string.home_watch),
                    onClick = { onEvent(PlayEvent.WatchGame) },
                    enabled = !isBusy,
                    style = PopStyle.Outline,
                    icon = Res.drawable.ic_eye,
                    height = 44.dp,
                    modifier = Modifier.testTag(TestTags.HOME_WATCH),
                )
            }
        }

        history.history.games.firstOrNull()?.let { game ->
            LastGameCard(
                game = game,
                enabled = !history.isBusy,
                onResults = {
                    onHistoryEvent(
                        if (game.hasRecording) HistoryEvent.OpenRecording(game) else HistoryEvent.Open,
                    )
                },
            )
        }

        if (state.bigGames.isNotEmpty()) {
            SectionTitle(stringResource(Res.string.big_games_title))
            state.bigGames.forEach { game ->
                BigGameCardView(
                    game = game,
                    isBusy = isBusy || state.isSigningUp,
                    onSignUp = { onEvent(PlayEvent.SignUp(game)) },
                    onCancel = { onEvent(PlayEvent.CancelSignup(game)) },
                    onJoin = {
                        joinedBigGame = game
                        requestLocationThenJoinBigGame()
                    },
                )
            }
        }

        StartStatusBanners(
            status = status,
            sessionError = state.sessionError,
            onDismiss = { onEvent(PlayEvent.DismissProblems) },
            onLeaveOtherGame = { onEvent(PlayEvent.LeaveOtherGameAndRetry) },
        )
        CommandStatus(
            isBusy = false,
            message = state.message,
            onDismiss = { onEvent(PlayEvent.DismissProblems) },
            errorTag = TestTags.SOCIAL_ERROR,
        )
    }
}

/** The screen's main action: a big green card with the zone drawn as rings. */
@Composable
private fun CreateGameCard(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PopSurface(
        modifier = modifier.fillMaxWidth().height(176.dp),
        shape = RoundedCornerShape(28.dp),
        color = if (enabled) Palette.Green else Palette.Sand,
        shadow = if (enabled) 6.dp else 0.dp,
        onClick = onClick,
        enabled = enabled,
    ) {
        ZoneRings(modifier = Modifier.align(Alignment.TopEnd).offset(x = 84.dp, y = (-72).dp).size(220.dp))
        Row(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f).widthIn(max = 240.dp)) {
                Text(text = stringResource(Res.string.home_create), style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = stringResource(Res.string.home_create_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.GreenInk,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Box(
                modifier = Modifier.size(52.dp).clip(CircleShape).background(Palette.Ink),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_arrow_right),
                    contentDescription = null,
                    tint = Palette.Green,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** The zone, the next zone (dashed) and a pink hider: decoration of the «Create a game» card. */
@Composable
private fun ZoneRings(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val stroke = PopBorder.toPx()
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Palette.Ink, radius = size.minDimension * 0.45f, center = c, style = Stroke(stroke))
        val next = Offset(c.x + size.width * 0.04f, c.y - size.height * 0.05f)
        drawCircle(
            Palette.Ink,
            radius = size.minDimension * 0.29f,
            center = next,
            style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 16f))),
        )
        drawCircle(Palette.Pink, radius = size.minDimension * 0.13f, center = next)
        drawCircle(Palette.Ink, radius = size.minDimension * 0.13f, center = next, style = Stroke(stroke))
        drawCircle(Palette.Ink, radius = size.minDimension * 0.035f, center = next)
    }
}

/** An invite into a game in its lobby: join it, or hide the invite. */
@Composable
private fun InviteCard(invite: GameInvite, isBusy: Boolean, onAccept: () -> Unit, onDismiss: () -> Unit) {
    PopSurface(
        modifier = Modifier.fillMaxWidth().testTag(TestTags.invite(invite.joinCode)),
        shape = RoundedCornerShape(20.dp),
        color = Palette.Pink,
        contentColor = Color.White,
        shadow = 4.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(
                name = invite.from.nickname,
                color = Palette.Green,
                size = 48.dp,
                modifier = Modifier.padding(top = 8.dp),
            )
            val groupName = invite.groupName
            Text(
                text = if (groupName != null) {
                    stringResource(Res.string.invite_from_group, invite.from.nickname, groupName)
                } else {
                    stringResource(Res.string.invite_from, invite.from.nickname)
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(top = 8.dp),
            )
            Column(horizontalAlignment = Alignment.End) {
                PopIconButton(
                    icon = Res.drawable.ic_close,
                    contentDescription = stringResource(Res.string.invite_dismiss),
                    onClick = onDismiss,
                    style = PopStyle.Pink,
                    size = 32.dp,
                    iconSize = 16.dp,
                    modifier = Modifier.testTag(TestTags.inviteDismiss(invite.joinCode)),
                )
                PopButton(
                    text = stringResource(Res.string.invite_accept),
                    onClick = onAccept,
                    enabled = !isBusy,
                    style = PopStyle.Dark,
                    height = 48.dp,
                    modifier = Modifier.testTag(TestTags.inviteAccept(invite.joinCode)),
                )
            }
        }
    }
}

/**
 * «I have a code from a friend» (docs/design.md, «Главная»): a white row with a chevron; open, [content] below it — the
 * code field, «Join» and «Watch».
 */
@Composable
private fun HaveCodeCard(expanded: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val turn by animateFloatAsState(if (expanded) 90f else 0f, Motion.fast())
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PopSurface(
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag(TestTags.HOME_HAVE_CODE),
            shape = RoundedCornerShape(20.dp),
            onClick = onToggle,
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(painterResource(Res.drawable.ic_qr), contentDescription = null, modifier = Modifier.size(26.dp))
                Text(
                    text = stringResource(Res.string.home_have_code),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painterResource(Res.drawable.ic_chevron_right),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp).rotate(turn),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit =
            shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

/**
 * The last game played (docs/design.md, «Главная»): an ink card with when, how it went, how long it took in big green
 * numbers, and «Results» — its recording, or the history when there is none.
 */
@Composable
private fun LastGameCard(game: GameHistoryEntry, enabled: Boolean, onResults: () -> Unit) {
    PopSurface(
        modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_LAST_GAME),
        shape = RoundedCornerShape(24.dp),
        color = Palette.Ink,
        contentColor = Color.White,
        border = null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(Res.string.last_game_title, formatDateTime(game.finishedAtMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = LAST_GAME_MUTED,
                )
                Text(
                    text = listOf(
                        stringResource(game.outcome()),
                        stringResource(Res.string.history_players, game.players),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = formatCountdown(game.finishedAtMillis - game.startedAtMillis),
                    style = Hovanki.text.timer,
                    color = Palette.Green,
                )
            }
            PopButton(
                text = stringResource(Res.string.last_game_results),
                onClick = onResults,
                enabled = enabled,
                style = PopStyle.DarkOutline,
                height = 44.dp,
                modifier = Modifier.testTag(TestTags.HOME_LAST_GAME_RESULTS),
            )
        }
    }
}

private val LAST_GAME_MUTED = Color(0xFFB4B4BE)
