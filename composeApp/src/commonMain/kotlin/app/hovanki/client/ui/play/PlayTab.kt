package app.hovanki.client.ui.play

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.automation.TestTags
import app.hovanki.client.location.rememberLocationPermissionRequester
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.home_code_label
import app.hovanki.client.resources.home_create
import app.hovanki.client.resources.home_create_hint
import app.hovanki.client.resources.home_join_short
import app.hovanki.client.resources.home_location_note
import app.hovanki.client.resources.home_or_join
import app.hovanki.client.resources.ic_arrow_right
import app.hovanki.client.resources.ic_close
import app.hovanki.client.resources.invite_accept
import app.hovanki.client.resources.invite_dismiss
import app.hovanki.client.resources.invite_from
import app.hovanki.client.resources.invite_from_group
import app.hovanki.client.resources.invites_title
import app.hovanki.client.resources.play_hello
import app.hovanki.client.resources.play_subtitle
import app.hovanki.client.ui.common.Avatar
import app.hovanki.client.ui.common.CommandStatus
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
import app.hovanki.client.ui.theme.Hovanki
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.verify.ConfirmEmailCard
import app.hovanki.client.ui.verify.VerifyEmailViewModel
import app.hovanki.shared.protocol.GameInvite
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * «Play»: a hello, the offer to confirm the email (until confirmed or «Later»), invites, then the big «Create a game»
 * card and joining a game by its code (docs/design.md, «Главная»).
 */
@Composable
fun PlayTab(invites: List<GameInvite>, verify: VerifyEmailViewModel, viewModel: PlayViewModel = koinViewModel()) {
    val account by viewModel.accountState.collectAsStateWithLifecycle()
    val status by viewModel.startStatus.collectAsStateWithLifecycle()
    val sessionError by viewModel.sessionError.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val requestLocationThenCreate = rememberLocationPermissionRequester { granted -> viewModel.createGame(granted) }
    // Asked before joining as well, so location is already on when the round starts.
    val requestLocationThenJoin = rememberLocationPermissionRequester { viewModel.joinGame() }
    var acceptedInvite by remember { mutableStateOf<GameInvite?>(null) }
    val requestLocationThenAccept = rememberLocationPermissionRequester {
        acceptedInvite?.let(viewModel::acceptInvite)
    }
    val isBusy = status.isBusy

    ScreenColumn(modifier = Modifier.testTag(TestTags.HOME_SCREEN)) {
        account.user?.let { user ->
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
                ConfirmEmailCard(email = user.email, onOpen = verify::open, onLater = verify::dismissCard)
            }
        }
        if (invites.isNotEmpty()) {
            SectionTitle(stringResource(Res.string.invites_title))
            invites.forEach { invite ->
                InviteCard(
                    invite = invite,
                    isBusy = isBusy,
                    onAccept = {
                        acceptedInvite = invite
                        requestLocationThenAccept()
                    },
                    onDismiss = { viewModel.dismissInvite(invite) },
                )
            }
        }

        CreateGameCard(
            enabled = !isBusy,
            onClick = requestLocationThenCreate,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp).testTag(TestTags.HOME_CREATE),
        )

        SectionTitle(stringResource(Res.string.home_or_join))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            PopTextField(
                value = viewModel.joinCode,
                onValueChange = viewModel::onJoinCodeChange,
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
                onClick = { if (viewModel.canJoinGame()) requestLocationThenJoin() },
                enabled = !isBusy,
                style = PopStyle.Hider,
                modifier = Modifier.testTag(TestTags.HOME_JOIN),
            )
        }
        SecondaryText(stringResource(Res.string.home_location_note))

        StartStatusBanners(status = status, sessionError = sessionError, onDismiss = viewModel::dismissProblems)
        CommandStatus(
            isBusy = false,
            message = message,
            onDismiss = viewModel::dismissProblems,
            errorTag = TestTags.SOCIAL_ERROR,
        )
    }
}

/** The screen's main action: a big lime card with the zone drawn as rings. */
@Composable
private fun CreateGameCard(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PopSurface(
        modifier = modifier.fillMaxWidth().height(176.dp),
        shape = RoundedCornerShape(28.dp),
        color = if (enabled) Palette.Lime else Palette.Sand,
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
                    color = Palette.LimeInk,
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
                    tint = Palette.Lime,
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
        shadow = 4.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Avatar(name = invite.from.nickname, color = Palette.Orange, size = 40.dp)
                val groupName = invite.groupName
                Text(
                    text = if (groupName != null) {
                        stringResource(Res.string.invite_from_group, invite.from.nickname, groupName)
                    } else {
                        stringResource(Res.string.invite_from, invite.from.nickname)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                PopIconButton(
                    icon = Res.drawable.ic_close,
                    contentDescription = stringResource(Res.string.invite_dismiss),
                    onClick = onDismiss,
                    style = PopStyle.Pink,
                    size = 36.dp,
                    iconSize = 18.dp,
                    modifier = Modifier.testTag(TestTags.inviteDismiss(invite.joinCode)),
                )
            }
            PopButton(
                text = stringResource(Res.string.invite_accept),
                onClick = onAccept,
                enabled = !isBusy,
                style = PopStyle.Dark,
                height = 48.dp,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.inviteAccept(invite.joinCode)),
            )
        }
    }
}
