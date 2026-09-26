package app.hovanki.client.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SystemBackHandler
import app.hovanki.client.ui.play.PlayTab
import app.hovanki.client.ui.profile.ProfileTab
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Logged in with a confirmed email, not in a game: «Play», «Friends», «Groups» and «Profile» in a navigation bar.
 * While it is shown, the inbox (game invites, friend requests) is polled for the tabs and their badges.
 */
@Composable
fun MainScreen(viewModel: MainViewModel = koinViewModel()) {
    val inbox by viewModel.inbox.collectAsStateWithLifecycle()
    val tab = viewModel.tab
    // Back from another tab goes to «Play»; from «Play» it leaves the app as usual.
    SystemBackHandler(enabled = tab != MainTab.PLAY, onBack = { viewModel.select(MainTab.PLAY) })

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                MainTab.PLAY -> PlayTab(invites = inbox.invites)
                MainTab.FRIENDS -> TabPlaceholder(Res.string.tab_friends, TestTags.FRIENDS_SCREEN)
                MainTab.GROUPS -> TabPlaceholder(Res.string.tab_groups, TestTags.GROUPS_SCREEN)
                MainTab.PROFILE -> ProfileTab()
            }
        }
        NavigationBar {
            MainTab.entries.forEach { item ->
                val badge = when (item) {
                    MainTab.PLAY -> inbox.invites.size
                    MainTab.FRIENDS -> inbox.friendRequests.size
                    MainTab.GROUPS, MainTab.PROFILE -> 0
                }
                NavigationBarItem(
                    selected = item == tab,
                    onClick = { viewModel.select(item) },
                    icon = { TabIcon(item.icon(), badge) },
                    label = { Text(stringResource(item.title())) },
                    modifier = Modifier.testTag(item.tag()),
                )
            }
        }
    }
}

/** The tab's icon, with the number of new things in it. */
@Composable
private fun TabIcon(icon: DrawableResource, badge: Int) {
    BadgedBox(badge = { if (badge > 0) Badge { Text(badge.toString()) } }) {
        Icon(painter = painterResource(icon), contentDescription = null)
    }
}

// TODO(friends, groups): their tabs are still empty.
@Composable
private fun TabPlaceholder(title: StringResource, tag: String) {
    ScreenColumn(modifier = Modifier.testTag(tag)) {
        Text(text = stringResource(title), style = MaterialTheme.typography.headlineSmall)
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
