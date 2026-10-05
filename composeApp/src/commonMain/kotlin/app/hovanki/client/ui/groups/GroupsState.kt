package app.hovanki.client.ui.groups

import app.hovanki.client.session.SessionError
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.client.ui.common.StartStatus
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.GroupView
import app.hovanki.shared.protocol.GroupsResponse
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserSummary

/** What «Groups» and a group's panel show. */
data class GroupsUiState(
    /** Null until loaded. */
    val groups: GroupsResponse? = null,
    /** The player's friends, to pick for a group. */
    val friends: List<UserSummary> = emptyList(),
    /** The logged-in account; null: logged out. */
    val myId: UserId? = null,
    /** The new group form is open. */
    val isCreating: Boolean = false,
    /** The new group's name, or the new name when renaming. */
    val name: String = "",
    /** Friends picked for a new group, or to add to the open one. */
    val picked: Set<UserId> = emptySet(),
    /** The group whose panel is open; null: none. */
    val openGroupId: GroupId? = null,
    val panelMode: GroupPanelMode = GroupPanelMode.VIEW,
    val message: FormMessage? = null,
    val isBusy: Boolean = false,
    /** Playing with the open group: the game being made. */
    val startStatus: StartStatus = StartStatus(),
    val sessionError: SessionError? = null,
) {
    /** The open group's panel, once the group is loaded; null: the list. */
    val openGroup: GroupView? get() = openGroupId?.let { id -> groups?.groups?.firstOrNull { it.id == id } }

    fun isOwner(group: GroupView): Boolean = group.ownerId == myId

    /** Friends who are not in [group] yet, for its owner to add. */
    fun candidates(group: GroupView): List<UserSummary> {
        val memberIds = group.members.map { it.id }.toSet()
        return friends.filter { it.id !in memberIds }
    }
}

/** What the group's panel shows. */
enum class GroupPanelMode { VIEW, ADD_MEMBERS, RENAME, CONFIRM_DELETE }

sealed interface GroupsEvent {
    /** The tab is shown. */
    data object Refresh : GroupsEvent

    data object StartCreating : GroupsEvent

    data object CancelCreating : GroupsEvent

    data class NameChanged(val value: String) : GroupsEvent

    data class TogglePick(val userId: UserId) : GroupsEvent

    /** The new group opens right away. */
    data object Create : GroupsEvent

    data class Open(val groupId: GroupId) : GroupsEvent

    /** Back from a form of the panel to the panel, from the panel to the list. */
    data object Back : GroupsEvent

    data object ClosePanel : GroupsEvent

    data object StartAdding : GroupsEvent

    data object AddMembers : GroupsEvent

    data class RemoveMember(val userId: UserId) : GroupsEvent

    data class StartRenaming(val group: GroupView) : GroupsEvent

    data object SaveName : GroupsEvent

    data object AskToDelete : GroupsEvent

    data object Delete : GroupsEvent

    /** An owner who leaves hands the group over to the longest-standing member. */
    data object Leave : GroupsEvent

    /** After the location permission was asked for: a game centered here, then the whole group invited. */
    data class PlayWithGroup(val locationGranted: Boolean) : GroupsEvent

    /** The account still plays a round elsewhere: leave it, and play with the group as just tried. */
    data object LeaveOtherGameAndRetry : GroupsEvent

    data object DismissMessage : GroupsEvent
}
