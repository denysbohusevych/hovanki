package app.hovanki.client.ui.results

import app.hovanki.client.account.AccountState
import app.hovanki.client.session.SessionState
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.client.ui.common.PlayerAccount
import app.hovanki.client.ui.common.playerAccount
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.TracksResponse
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.rules.StreetZone

/**
 * What the results screen shows besides the final snapshot (docs/architecture.md, «Состояние экрана»). The standings
 * and awards come from the snapshot itself.
 */
data class ResultsUiState(
    /** The players of the finished game as far as friends go, by player. */
    val accounts: Map<PlayerId, PlayerAccount> = emptyMap(),
    /** Everybody's way through the round for the replay; null until loaded. */
    val tracks: TracksResponse? = null,
    /** The zone by streets of the game, for the replay; null when it played with circles. */
    val streetZone: StreetZone? = null,
    /**
     * Whether the player keeps their routes; null: this game has no history of theirs (played as a guest, or logged in
     * as someone else since).
     */
    val saveRoutes: Boolean? = null,
    /** A friend request (or «Save my routes») failed. */
    val message: FormMessage? = null,
    val isBusy: Boolean = false,
)

sealed interface ResultsEvent {
    data class AddFriend(val userId: UserId) : ResultsEvent

    /** «Save my routes» on: from now on, and this game's route too (the server still has the game). */
    data object TurnOnSaveRoutes : ResultsEvent

    data object DismissMessage : ResultsEvent

    /** Back to the start: the game is over for this phone (polling for the chat stops too). */
    data object Leave : ResultsEvent
}

/** Builds [ResultsUiState]; keeps the zone by streets it built while its stages stay the same, not one per poll. */
internal class ResultsStateBuilder {
    private var streetZone: StreetZone? = null

    fun build(
        session: SessionState,
        friends: FriendsResponse?,
        account: AccountState,
        message: FormMessage?,
        isBusy: Boolean,
    ): ResultsUiState {
        val zone = streetZoneOf(session)
        if (zone?.stages != streetZone?.stages) streetZone = zone
        return ResultsUiState(
            accounts = playerAccounts(session, friends, account),
            tracks = session.tracks,
            streetZone = streetZone,
            saveRoutes = saveRoutes(session, account),
            message = message,
            isBusy = isBusy,
        )
    }
}

/** The game's zone by streets, when it is the one of the game's map and has every stage. */
internal fun streetZoneOf(state: SessionState): StreetZone? {
    val snapshot = state.snapshot ?: return null
    val zone = state.streetZone ?: return null
    val usable = zone.mapRevision == snapshot.mapRevision &&
        zone.stages.size == snapshot.settings.zone.stages.size + 1 &&
        zone.stages.all { it.outline.size >= 4 }
    return if (usable) StreetZone(zone.stages) else null
}

/** Whether the player keeps their routes; null unless this game was played by the logged-in account. */
internal fun saveRoutes(state: SessionState, accountState: AccountState): Boolean? {
    val user = accountState.user ?: return null
    val snapshot = state.snapshot ?: return null
    val me = snapshot.players.firstOrNull { it.id == snapshot.me.playerId }
    return user.saveRoutes.takeIf { me?.userId == user.id }
}

private fun playerAccounts(
    state: SessionState,
    friends: FriendsResponse?,
    accountState: AccountState,
): Map<PlayerId, PlayerAccount> {
    val snapshot = state.snapshot ?: return emptyMap()
    return snapshot.players.associate { it.id to playerAccount(it, snapshot.me.playerId, accountState, friends) }
}
