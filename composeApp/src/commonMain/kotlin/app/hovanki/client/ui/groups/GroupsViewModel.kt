package app.hovanki.client.ui.groups

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.account.AccountManager
import app.hovanki.client.account.AccountState
import app.hovanki.client.automation.LaunchOptionsHolder
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.error_invalid_group_name
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.session.SessionError
import app.hovanki.client.social.SocialManager
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.client.ui.common.GameStarter
import app.hovanki.client.ui.common.Notice
import app.hovanki.client.ui.common.StartStatus
import app.hovanki.client.ui.common.notice
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.GroupView
import app.hovanki.shared.protocol.GroupsResponse
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.rules.GroupRules
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * «Groups» and a group's panel: create a group with some friends; in the panel the owner adds friends, removes
 * members, renames or deletes the group, anyone leaves it or plays with it (a game here, the group invited).
 */
class GroupsViewModel(
    private val social: SocialManager,
    private val account: AccountManager,
    private val sessionManager: GameSessionManager,
    launchOptions: LaunchOptionsHolder,
) : ViewModel() {
    private val starter = GameStarter(sessionManager, launchOptions, viewModelScope)
    private val commands = CommandRunner(viewModelScope)

    /** The new group form is open. */
    var isCreating by mutableStateOf(false)
        private set

    /** Compose state: text fields need synchronous updates. The new group's name, or the new name when renaming. */
    var name by mutableStateOf("")
        private set

    /** Friends picked for a new group, or to add to the open one. */
    var picked by mutableStateOf<Set<UserId>>(emptySet())
        private set

    /** The group whose panel is open; null: none. */
    var openGroupId by mutableStateOf<GroupId?>(null)
        private set

    var panelMode by mutableStateOf(GroupPanelMode.VIEW)
        private set

    /** Null until loaded. */
    val groups: StateFlow<GroupsResponse?> = social.groups
    val friends: StateFlow<FriendsResponse?> = social.friends
    val accountState: StateFlow<AccountState> = account.state
    val message: StateFlow<FormMessage?> = commands.message
    val isBusy: StateFlow<Boolean> = commands.isBusy
    val startStatus: StateFlow<StartStatus> = starter.status
    val sessionError: StateFlow<SessionError?> = sessionManager.state
        .map { it.lastError }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), sessionManager.state.value.lastError)

    init {
        // The open group is gone (deleted, left, removed from it, logged out): its panel closes.
        viewModelScope.launch {
            social.groups.collect { loaded ->
                val open = openGroupId
                if (open != null && loaded?.groups?.none { it.id == open } != false) closePanel()
            }
        }
    }

    /** The tab is shown: groups load on demand; friends to pick may have changed too. Errors only as a message. */
    fun refresh() {
        viewModelScope.launch {
            val problem = social.refreshGroups().notice()
            if (problem != null && !commands.isBusy.value) commands.show(problem)
        }
        viewModelScope.launch { social.refreshFriends() }
    }

    fun isOwner(group: GroupView): Boolean = group.ownerId == account.state.value.user?.id

    fun startCreating() {
        isCreating = true
        name = ""
        picked = emptySet()
        commands.dismiss()
    }

    fun cancelCreating() {
        isCreating = false
        picked = emptySet()
    }

    fun onNameChange(value: String) {
        name = value.take(GroupRules.NAME_MAX_LENGTH)
    }

    fun togglePick(userId: UserId) {
        picked = if (userId in picked) picked - userId else picked + userId
    }

    /** The new group opens right away. */
    fun create() {
        if (!GroupRules.isValidName(name)) {
            commands.show(Notice.Text(Res.string.error_invalid_group_name))
            return
        }
        commands.execute({ social.createGroup(name, picked.toList()) }) { group ->
            isCreating = false
            if (group != null) open(group.id)
        }
    }

    fun open(groupId: GroupId) {
        openGroupId = groupId
        panelMode = GroupPanelMode.VIEW
        picked = emptySet()
        commands.dismiss()
        starter.dismissProblems()
    }

    /** Back from a form of the panel to the panel, from the panel to the list. */
    fun back() {
        if (panelMode != GroupPanelMode.VIEW) {
            panelMode = GroupPanelMode.VIEW
            picked = emptySet()
        } else {
            closePanel()
        }
    }

    fun closePanel() {
        openGroupId = null
        panelMode = GroupPanelMode.VIEW
        picked = emptySet()
    }

    fun startAdding() {
        panelMode = GroupPanelMode.ADD_MEMBERS
        picked = emptySet()
        commands.dismiss()
    }

    fun addMembers() {
        val groupId = openGroupId ?: return
        if (picked.isEmpty()) return
        commands.execute({ social.addGroupMembers(groupId, picked.toList()) }) { back() }
    }

    fun removeMember(userId: UserId) {
        val groupId = openGroupId ?: return
        commands.execute({ social.removeGroupMember(groupId, userId) })
    }

    fun startRenaming(group: GroupView) {
        name = group.name
        panelMode = GroupPanelMode.RENAME
        commands.dismiss()
    }

    fun saveName() {
        val groupId = openGroupId ?: return
        if (!GroupRules.isValidName(name)) {
            commands.show(Notice.Text(Res.string.error_invalid_group_name))
            return
        }
        commands.execute({ social.renameGroup(groupId, name) }) { back() }
    }

    fun askToDelete() {
        panelMode = GroupPanelMode.CONFIRM_DELETE
        commands.dismiss()
    }

    fun delete() {
        val groupId = openGroupId ?: return
        commands.execute({ social.deleteGroup(groupId) }) { closePanel() }
    }

    /** An owner who leaves hands the group over to the longest-standing member. */
    fun leave() {
        val groupId = openGroupId ?: return
        commands.execute({ social.leaveGroup(groupId) }) { closePanel() }
    }

    /**
     * After the location permission was asked for: a game centered here, then the whole group invited. The lobby
     * shows when the game is created; a failed invite shows there too.
     */
    fun playWithGroup(locationGranted: Boolean) {
        val groupId = openGroupId ?: return
        val nickname = account.state.value.user?.nickname.orEmpty()
        starter.create(nickname, locationGranted) {
            closePanel()
            sessionManager.invite(groupId = groupId)
        }
    }

    /** The account still plays a round elsewhere: leave it, and play with the group as just tried. */
    fun leaveOtherGameAndRetry() = starter.leaveOtherGameAndRetry()

    fun dismissMessage() {
        commands.dismiss()
        starter.dismissProblems()
    }
}

/** What the group's panel shows. */
enum class GroupPanelMode { VIEW, ADD_MEMBERS, RENAME, CONFIRM_DELETE }
