package app.hovanki.client.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.ic_friends
import app.hovanki.client.resources.ic_groups
import app.hovanki.client.resources.ic_play
import app.hovanki.client.resources.ic_profile
import app.hovanki.client.resources.tab_friends
import app.hovanki.client.resources.tab_groups
import app.hovanki.client.resources.tab_play
import app.hovanki.client.resources.tab_profile
import app.hovanki.client.ui.common.CountBadge
import app.hovanki.client.ui.common.SystemBackHandler
import app.hovanki.client.ui.common.collectScreenState
import app.hovanki.client.ui.friends.FriendsTab
import app.hovanki.client.ui.groups.GroupPanel
import app.hovanki.client.ui.groups.GroupsTab
import app.hovanki.client.ui.groups.GroupsViewModel
import app.hovanki.client.ui.history.HistoryPanel
import app.hovanki.client.ui.history.HistoryViewModel
import app.hovanki.client.ui.history.RecordingPanel
import app.hovanki.client.ui.history.RoutePanel
import app.hovanki.client.ui.play.PlayTab
import app.hovanki.client.ui.profile.ProfileTab
import app.hovanki.client.ui.theme.Motion
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.verify.EmailConfirmedNotice
import app.hovanki.client.ui.verify.VerifyEmailEvent
import app.hovanki.client.ui.verify.VerifyEmailPanel
import app.hovanki.client.ui.verify.VerifyEmailViewModel
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Logged in (the email confirmed or not), not in a game: «Play», «Friends», «Groups» and «Profile» in a navigation bar,
 * or a panel over all of it (confirming the email, a group, the game history, a saved route and a game's recording).
 * While it is shown, the inbox (game invites, friend requests) is polled for the tabs and their badges.
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel = koinViewModel(),
    groupsViewModel: GroupsViewModel = koinViewModel(),
    verifyViewModel: VerifyEmailViewModel = koinViewModel(),
    historyViewModel: HistoryViewModel = koinViewModel(),
) {
    val history by historyViewModel.uiState.collectScreenState()
    val state by viewModel.uiState.collectScreenState()
    val verify by verifyViewModel.uiState.collectScreenState()
    val inbox = state.inbox
    val groups by groupsViewModel.uiState.collectScreenState()
    if (verify.isOpen) {
        VerifyEmailPanel(verify, verifyViewModel::onEvent)
        return
    }
    val openGroup = groups.openGroup
    if (openGroup != null) {
        GroupPanel(group = openGroup, state = groups, onEvent = groupsViewModel::onEvent)
        return
    }
    val openRoute = history.route
    if (openRoute != null) {
        RoutePanel(history, openRoute, historyViewModel::onEvent)
        return
    }
    val openRecording = history.recording
    if (openRecording != null) {
        RecordingPanel(history, openRecording, historyViewModel::onEvent)
        return
    }
    if (history.isOpen) {
        HistoryPanel(history, historyViewModel::onEvent)
        return
    }

    val tab = state.tab
    // Back from another tab goes to «Play»; from «Play» it leaves the app as usual.
    SystemBackHandler(enabled = tab != MainTab.PLAY, onBack = { viewModel.onEvent(MainEvent.SelectTab(MainTab.PLAY)) })

    Column(modifier = Modifier.fillMaxSize()) {
        if (verify.showConfirmed) {
            EmailConfirmedNotice(
                onDismiss = { verifyViewModel.onEvent(VerifyEmailEvent.DismissConfirmed) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                MainTab.PLAY -> PlayTab(
                    invites = inbox.invites,
                    verify = verify,
                    onVerifyEvent = verifyViewModel::onEvent,
                    history = history,
                    onHistoryEvent = historyViewModel::onEvent,
                    onOpenProfile = { viewModel.onEvent(MainEvent.SelectTab(MainTab.PROFILE)) },
                )

                MainTab.FRIENDS -> FriendsTab()

                MainTab.GROUPS -> GroupsTab(groups, groupsViewModel::onEvent)

                MainTab.PROFILE -> ProfileTab(onVerifyEvent = verifyViewModel::onEvent, history = historyViewModel)
            }
        }
        FloatingTabBar(selected = tab, onSelect = { viewModel.onEvent(MainEvent.SelectTab(it)) }, badges = { item ->
            when (item) {
                MainTab.PLAY -> inbox.invites.size
                MainTab.FRIENDS -> inbox.friendRequests.size
                MainTab.GROUPS, MainTab.PROFILE -> 0
            }
        })
    }
}

/**
 * The tabs as an ink capsule floating above the bottom of the screen; the selected one is a green pill
 * (docs/design.md, «Компоненты»).
 */
@Composable
private fun FloatingTabBar(selected: MainTab, onSelect: (MainTab) -> Unit, badges: (MainTab) -> Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(68.dp)
            .clip(RoundedCornerShape(34.dp))
            .background(Palette.Ink)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MainTab.entries.forEach { item ->
            val isSelected = item == selected
            val background by animateColorAsState(if (isSelected) Palette.Green else Palette.Ink, Motion.base())
            val content by animateColorAsState(if (isSelected) Palette.Ink else TAB_IDLE, Motion.base())
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(26.dp))
                    .background(background)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(item) })
                    .testTag(item.tag()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                TabIcon(item.icon(), badges(item), tint = content)
                Text(
                    text = stringResource(item.title()),
                    color = content,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}

private val TAB_IDLE = Color(0xFFC9C9D2)

/** The tab's icon, with the number of new things in it. */
@Composable
private fun TabIcon(icon: DrawableResource, badge: Int, tint: Color) {
    Box {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.padding(horizontal = 6.dp).size(22.dp),
        )
        if (badge >
            0
        ) {
            CountBadge(count = badge, modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp))
        }
    }
}

private fun MainTab.title(): StringResource = when (this) {
    MainTab.PLAY -> Res.string.tab_play
    MainTab.FRIENDS -> Res.string.tab_friends
    MainTab.GROUPS -> Res.string.tab_groups
    MainTab.PROFILE -> Res.string.tab_profile
}

private fun MainTab.icon(): DrawableResource = when (this) {
    MainTab.PLAY -> Res.drawable.ic_play
    MainTab.FRIENDS -> Res.drawable.ic_friends
    MainTab.GROUPS -> Res.drawable.ic_groups
    MainTab.PROFILE -> Res.drawable.ic_profile
}

private fun MainTab.tag(): String = when (this) {
    MainTab.PLAY -> TestTags.TAB_PLAY
    MainTab.FRIENDS -> TestTags.TAB_FRIENDS
    MainTab.GROUPS -> TestTags.TAB_GROUPS
    MainTab.PROFILE -> TestTags.TAB_PROFILE
}
