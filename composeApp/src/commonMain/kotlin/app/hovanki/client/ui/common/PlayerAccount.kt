package app.hovanki.client.ui.common

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hovanki.client.account.AccountState
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.ic_person_add
import app.hovanki.client.resources.player_accept_friend
import app.hovanki.client.resources.player_add_friend
import app.hovanki.client.resources.player_blocked
import app.hovanki.client.resources.player_friend
import app.hovanki.client.resources.player_guest
import app.hovanki.client.resources.player_request_sent
import app.hovanki.client.social.UserRelation
import app.hovanki.client.social.userRelation
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.UserId
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Another player of the game as far as friends go (lobby and results). */
data class PlayerAccount(
    /** The player's account; null: a guest. */
    val userId: UserId?,
    /**
     * What they are to the viewer; null when that does not apply: a guest, the viewer themselves, or the viewer is a
     * guest.
     */
    val relation: UserRelation?,
) {
    val isGuest: Boolean get() = userId == null
}

/** [player] as the viewer ([me], their [account] and [friends]) sees them. */
fun playerAccount(player: PlayerView, me: PlayerId, account: AccountState, friends: FriendsResponse?): PlayerAccount {
    val userId = player.userId
    val selfId = account.user?.id
    val relation = when {
        userId == null || player.id == me || !account.isLoggedIn -> null
        else -> userRelation(userId, selfId, friends).takeIf { it != UserRelation.SELF }
    }
    return PlayerAccount(userId, relation)
}

/**
 * Next to a player's name: «guest», or what they are to the viewer, with a button to add them as a friend (or accept
 * their request); [onAddFriend] sends the request.
 */
@Composable
fun PlayerAccountBadge(playerId: PlayerId, account: PlayerAccount, isBusy: Boolean, onAddFriend: () -> Unit) {
    if (account.isGuest) {
        BadgeText(stringResource(Res.string.player_guest), Modifier.testTag(TestTags.playerGuest(playerId)))
        return
    }
    when (account.relation) {
        null, UserRelation.SELF -> Unit

        UserRelation.NONE, UserRelation.INCOMING -> TextButton(
            onClick = onAddFriend,
            enabled = !isBusy,
            modifier = Modifier.testTag(TestTags.addFriend(playerId)),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_person_add),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(
                    if (account.relation == UserRelation.INCOMING) {
                        Res.string.player_accept_friend
                    } else {
                        Res.string.player_add_friend
                    },
                ),
            )
        }

        UserRelation.OUTGOING -> RelationText(playerId, stringResource(Res.string.player_request_sent))

        UserRelation.FRIEND -> RelationText(playerId, stringResource(Res.string.player_friend))

        UserRelation.BLOCKED -> RelationText(playerId, stringResource(Res.string.player_blocked))
    }
}

@Composable
private fun RelationText(playerId: PlayerId, text: String) {
    BadgeText(text, Modifier.testTag(TestTags.playerRelation(playerId)))
}

@Composable
private fun BadgeText(text: String, modifier: Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
