package app.hovanki.client.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.account.AccountManager
import app.hovanki.client.automation.LaunchOptionsHolder
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.error_invalid_group_name
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.social.SocialManager
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.client.ui.common.GameStarter
import app.hovanki.client.ui.common.Notice
import app.hovanki.client.ui.common.notice
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.rules.GroupRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * «Groups» and a group's panel: create a group with some friends; in the panel the owner adds friends, removes
 * members, renames or deletes the group, anyone leaves it or plays with it (a game here, the group invited).
 *
 * One state for the tab and the panel, [uiState], and one way in, [onEvent] (docs/architecture.md, «Состояние
 * экрана»). The forms change the state at once, on the caller's thread: a text field's edit is there before the next
 * frame.
 */
class GroupsViewModel(
    private val social: SocialManager,
    private val account: AccountManager,
    private val sessionManager: GameSessionManager,
    launchOptions: LaunchOptionsHolder,
) : ViewModel() {
    private val starter = GameStarter(sessionManager, launchOptions, viewModelScope)
    private val commands = CommandRunner(viewModelScope)

    private val mutableUiState = MutableStateFlow(
        GroupsUiState(
            groups = social.groups.value,
            friends = social.friends.value?.friends.orEmpty(),
            myId = account.state.value.user?.id,
            message = commands.message.value,
            isBusy = commands.isBusy.value,
            startStatus = starter.status.value,
            sessionError = sessionManager.state.value.lastError,
        ),
    )
    val uiState: StateFlow<GroupsUiState> = mutableUiState.asStateFlow()

    init {
        val people = combine(social.groups, social.friends, account.state) { groups, friends, accountState ->
            Triple(groups, friends?.friends.orEmpty(), accountState.user?.id)
        }
        val commandState = combine(commands.message, commands.isBusy) { message, busy -> message to busy }
        val start = combine(starter.status, sessionManager.state) { status, session -> status to session.lastError }
        viewModelScope.launch {
            combine(people, commandState, start, ::Triple).collect { (loaded, command, starting) ->
                val (groups, friends, myId) = loaded
                val (message, busy) = command
                val (status, sessionError) = starting
                mutableUiState.update {
                    it.copy(
                        groups = groups,
                        friends = friends,
                        myId = myId,
                        message = message,
                        isBusy = busy,
                        startStatus = status,
                        sessionError = sessionError,
                    )
                }
            }
        }
        // The open group is gone (deleted, left, removed from it, logged out): its panel closes.
        viewModelScope.launch {
            social.groups.collect { loaded ->
                val open = uiState.value.openGroupId
                if (open != null && loaded?.groups?.none { it.id == open } != false) closePanel()
            }
        }
    }

    fun onEvent(event: GroupsEvent) {
        when (event) {
            GroupsEvent.Refresh -> refresh()

            GroupsEvent.StartCreating -> {
                update { it.copy(isCreating = true, name = "", picked = emptySet()) }
                commands.dismiss()
            }

            GroupsEvent.CancelCreating -> update { it.copy(isCreating = false, picked = emptySet()) }

            is GroupsEvent.NameChanged -> update { it.copy(name = event.value.take(GroupRules.NAME_MAX_LENGTH)) }

            is GroupsEvent.TogglePick -> update {
                it.copy(picked = if (event.userId in it.picked) it.picked - event.userId else it.picked + event.userId)
            }

            GroupsEvent.Create -> create()

            is GroupsEvent.Open -> open(event.groupId)

            GroupsEvent.Back -> back()

            GroupsEvent.ClosePanel -> closePanel()

            GroupsEvent.StartAdding -> {
                update { it.copy(panelMode = GroupPanelMode.ADD_MEMBERS, picked = emptySet()) }
                commands.dismiss()
            }

            GroupsEvent.AddMembers -> addMembers()

            is GroupsEvent.RemoveMember -> {
                val groupId = uiState.value.openGroupId ?: return
                commands.execute({ social.removeGroupMember(groupId, event.userId) })
            }

            is GroupsEvent.StartRenaming -> {
                update { it.copy(name = event.group.name, panelMode = GroupPanelMode.RENAME) }
                commands.dismiss()
            }

            GroupsEvent.SaveName -> saveName()

            GroupsEvent.AskToDelete -> {
                update { it.copy(panelMode = GroupPanelMode.CONFIRM_DELETE) }
                commands.dismiss()
            }

            GroupsEvent.Delete -> {
                val groupId = uiState.value.openGroupId ?: return
                commands.execute({ social.deleteGroup(groupId) }) { closePanel() }
            }

            GroupsEvent.Leave -> {
                val groupId = uiState.value.openGroupId ?: return
                commands.execute({ social.leaveGroup(groupId) }) { closePanel() }
            }

            is GroupsEvent.PlayWithGroup -> playWithGroup(event.locationGranted)

            GroupsEvent.LeaveOtherGameAndRetry -> starter.leaveOtherGameAndRetry()

            GroupsEvent.DismissMessage -> {
                commands.dismiss()
                starter.dismissProblems()
            }
        }
    }

    private fun update(change: (GroupsUiState) -> GroupsUiState) = mutableUiState.update(change)

    /** The tab is shown: groups load on demand; friends to pick may have changed too. Errors only as a message. */
    private fun refresh() {
        viewModelScope.launch {
            val problem = social.refreshGroups().notice()
            if (problem != null && !commands.isBusy.value) commands.show(problem)
        }
        viewModelScope.launch { social.refreshFriends() }
    }

    private fun create() {
        val state = uiState.value
        if (!GroupRules.isValidName(state.name)) {
            commands.show(Notice.Text(Res.string.error_invalid_group_name))
            return
        }
        commands.execute({ social.createGroup(state.name, state.picked.toList()) }) { group ->
            update { it.copy(isCreating = false) }
            if (group != null) open(group.id)
        }
    }

    private fun open(groupId: GroupId) {
        update { it.copy(openGroupId = groupId, panelMode = GroupPanelMode.VIEW, picked = emptySet()) }
        commands.dismiss()
        starter.dismissProblems()
    }

    private fun back() {
        if (uiState.value.panelMode != GroupPanelMode.VIEW) {
            update { it.copy(panelMode = GroupPanelMode.VIEW, picked = emptySet()) }
        } else {
            closePanel()
        }
    }

    private fun closePanel() {
        update { it.copy(openGroupId = null, panelMode = GroupPanelMode.VIEW, picked = emptySet()) }
    }

    private fun addMembers() {
        val state = uiState.value
        val groupId = state.openGroupId ?: return
        if (state.picked.isEmpty()) return
        commands.execute({ social.addGroupMembers(groupId, state.picked.toList()) }) { back() }
    }

    private fun saveName() {
        val state = uiState.value
        val groupId = state.openGroupId ?: return
        if (!GroupRules.isValidName(state.name)) {
            commands.show(Notice.Text(Res.string.error_invalid_group_name))
            return
        }
        commands.execute({ social.renameGroup(groupId, state.name) }) { back() }
    }

    /**
     * After the location permission was asked for: a game centered here, then the whole group invited. The lobby
     * shows when the game is created; a failed invite shows there too.
     */
    private fun playWithGroup(locationGranted: Boolean) {
        val groupId = uiState.value.openGroupId ?: return
        val nickname = account.state.value.user?.nickname.orEmpty()
        starter.create(nickname, locationGranted) {
            closePanel()
            sessionManager.invite(groupId = groupId)
        }
    }
}
