package app.hovanki.client.history

import app.hovanki.client.account.AccountManager
import app.hovanki.client.network.ApiResult
import app.hovanki.client.network.HistoryApi
import app.hovanki.client.network.apiResult
import app.hovanki.shared.protocol.GameHistoryEntry
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameRecording
import app.hovanki.shared.protocol.GameRoute
import app.hovanki.shared.protocol.PlayerStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The logged-in player's own history (docs/adr/0007-game-history-and-routes.md): statistics and games, loaded when a
 * screen asks ([refresh], [loadMore]), saved routes, and the consent to keep them ([setSaveRoutes]). App-scoped; runs
 * on the main thread. Everything needs an account; the state is cleared when the player logs out or another account
 * logs in, and a 401 logs the player out. Commands return an [ApiResult]; none throws.
 */
class HistoryManager(
    private val api: HistoryApi,
    private val account: AccountManager,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val mutableState = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = mutableState.asStateFlow()

    /** The account the state was loaded for; another one's is never shown. */
    private var loadedFor: String? = null

    init {
        scope.launch {
            account.tokens.collect { token -> if (token != loadedFor) clear() }
        }
    }

    /** The statistics and the newest games, again. */
    suspend fun refresh(): ApiResult<Unit> = command { token ->
        val stats = api.stats(token)
        val page = api.games(token)
        apply(token) { HistoryState(stats, page.games, page.nextBefore, isLoaded = true) }
    }

    /** The next page of older games, if there is one. */
    suspend fun loadMore(): ApiResult<Unit> {
        val before = state.value.nextBefore ?: return ApiResult.Success(Unit)
        return command { token ->
            val page = api.games(token, before)
            apply(token) { current ->
                val known = current.games.map { it.gameId }.toSet()
                val older = page.games.filter { it.gameId !in known }
                current.copy(games = current.games + older, nextBefore = page.nextBefore)
            }
        }
    }

    /** The player's saved route of [gameId]; rejected with 404 (`NOT_FOUND`) when none is saved. */
    suspend fun route(gameId: GameId): ApiResult<GameRoute> = command { token -> api.route(token, gameId) }

    /**
     * The recording of [gameId] (docs/adr/0011-spectators-and-recordings.md): everybody's way through it; rejected with
     * 404 (`NOT_FOUND`) when it is no longer kept.
     */
    suspend fun recording(gameId: GameId): ApiResult<GameRecording> = command { token -> api.recording(token, gameId) }

    /** Deletes the saved route of [gameId]; the game stays in the history. */
    suspend fun deleteRoute(gameId: GameId): ApiResult<Unit> = command { token ->
        api.deleteRoute(token, gameId)
        apply(token) { current -> current.withoutRoutes { it.gameId == gameId } }
    }

    /**
     * Keep the routes of the player's games, or not ([AccountManager.setSaveRoutes]). Off deletes every saved route:
     * the history shows none from then on. On also keeps the games that just finished, saved a moment later: the
     * history shows them after the next [refresh].
     */
    suspend fun setSaveRoutes(enabled: Boolean): ApiResult<Unit> {
        val token = account.accountToken
        val result = account.setSaveRoutes(enabled)
        if (result is ApiResult.Success && !enabled && token != null) {
            apply(token) { current -> current.withoutRoutes { true } }
        }
        return result
    }

    private suspend fun <T> command(call: suspend (token: String) -> T): ApiResult<T> {
        val token = account.accountToken ?: return checkNotNull(account.missingAccount())
        return apiResult(onRejected = { if (it.status == UNAUTHORIZED) account.onTokenRejected(token) }) { call(token) }
    }

    /** Changes the state with a response for [token], unless the player logged out or switched accounts meanwhile. */
    private fun apply(token: String, change: (HistoryState) -> HistoryState) {
        if (account.accountToken != token) return
        if (loadedFor != token) {
            clear()
            loadedFor = token
        }
        mutableState.update(change)
    }

    private fun clear() {
        loadedFor = null
        mutableState.value = HistoryState()
    }

    private companion object {
        const val UNAUTHORIZED = 401
    }
}

/** What the history screens show. */
data class HistoryState(
    /** Null until loaded. */
    val stats: PlayerStats? = null,
    /** Newest first. */
    val games: List<GameHistoryEntry> = emptyList(),
    /** Where the next page starts; null: no more games. */
    val nextBefore: Long? = null,
    /** False until the first [HistoryManager.refresh] succeeded. */
    val isLoaded: Boolean = false,
) {
    val hasMore: Boolean get() = nextBefore != null

    internal fun withoutRoutes(which: (GameHistoryEntry) -> Boolean) =
        copy(games = games.map { if (which(it)) it.copy(hasRoute = false) else it })
}
