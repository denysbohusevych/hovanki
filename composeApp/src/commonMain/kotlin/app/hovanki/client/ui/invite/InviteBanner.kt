package app.hovanki.client.ui.invite

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.ic_close
import app.hovanki.client.resources.invite_dismiss
import app.hovanki.client.resources.invite_from
import app.hovanki.client.resources.invite_from_group
import app.hovanki.client.resources.invite_go
import app.hovanki.client.ui.common.Avatar
import app.hovanki.client.ui.common.Haptic
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopIconButton
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.PopSurface
import app.hovanki.client.ui.common.collectScreenState
import app.hovanki.client.ui.common.rememberHaptics
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.GameInvite
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * The newest invitation into another game, sliding in at the top of the lobby or the results (docs/design.md,
 * «Приглашения»): who invites, «Go» to that game's lobby, × to dismiss it. A light tap on the phone when one arrives.
 */
@Composable
fun InviteBanner(modifier: Modifier = Modifier, viewModel: InviteBannerViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectScreenState()
    InviteBannerContent(state, viewModel::onEvent, modifier)
}

@Composable
private fun InviteBannerContent(
    state: InviteBannerUiState,
    onEvent: (InviteBannerEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val invite = state.invite
    val haptics = rememberHaptics()
    LaunchedEffect(invite?.id) { if (invite != null) haptics(Haptic.TICK) }
    // The last invitation, kept while the banner slides out.
    var shown by remember { mutableStateOf(invite) }
    invite?.let { shown = it }
    AnimatedVisibility(
        visible = invite != null,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        shown?.let { current ->
            InviteBannerCard(
                invite = current,
                isBusy = state.isBusy,
                onGo = { onEvent(InviteBannerEvent.Go(current)) },
                onDismiss = { onEvent(InviteBannerEvent.Dismiss(current)) },
            )
        }
    }
}

@Composable
private fun InviteBannerCard(invite: GameInvite, isBusy: Boolean, onGo: () -> Unit, onDismiss: () -> Unit) {
    PopSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(TestTags.INVITE_BANNER),
        shape = RoundedCornerShape(20.dp),
        color = Palette.Pink,
        contentColor = Color.White,
        shadow = 4.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Avatar(name = invite.from.nickname, color = Palette.Green, size = 36.dp)
            val groupName = invite.groupName
            Text(
                text = if (groupName != null) {
                    stringResource(Res.string.invite_from_group, invite.from.nickname, groupName)
                } else {
                    stringResource(Res.string.invite_from, invite.from.nickname)
                },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            PopButton(
                text = stringResource(Res.string.invite_go),
                onClick = onGo,
                enabled = !isBusy,
                style = PopStyle.Dark,
                height = 40.dp,
                modifier = Modifier.testTag(TestTags.INVITE_BANNER_GO),
            )
            PopIconButton(
                icon = Res.drawable.ic_close,
                contentDescription = stringResource(Res.string.invite_dismiss),
                onClick = onDismiss,
                style = PopStyle.Pink,
                size = 36.dp,
                iconSize = 18.dp,
                modifier = Modifier.testTag(TestTags.INVITE_BANNER_DISMISS),
            )
        }
    }
}
