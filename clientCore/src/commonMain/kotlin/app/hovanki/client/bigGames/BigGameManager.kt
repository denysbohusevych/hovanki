package app.hovanki.client.bigGames

import app.hovanki.client.account.AccountManager
import app.hovanki.client.network.ApiResult
import app.hovanki.client.network.BigGameApi
import app.hovanki.client.network.apiResult
import app.hovanki.shared.protocol.BigGameCard
import app.hovanki.shared.protocol.BigGameId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The big games of the «Play» tab (docs/adr/0010-big-games.md): the list, polled every [intervalMillis] while someone
 * collects [state] (the tab is open), and the player's sign-ups. Coming into a lobby is
 * [app.hovanki.client.session.GameSessionManager.joinBigGame]. App-scoped; runs on the main thread. Needs an account;
 * the state is cleared when the player logs out or another account logs in, and a 401 logs the player out. Commands
 * return an [ApiResult]; none throws.
 */
class BigGameManager(
    private val api: BigGameApi,
    private val account: AccountManager,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val intervalMillis: Long = INTERVAL_MILLIS,
) {
    private val mutableState = MutableStateFlow(BigGamesState())

    /** The big games ahead and going on, soonest first; collecting it keeps it fresh. */
    val state: StateFlow<BigGamesState> = mutableState.asStateFlow()

    /** The account the state was loaded for; another one's is never shown. */
    private var loadedFor: String? = null

    init {
        scope.launch {
            account.tokens.collect { token -> if (token != loadedFor) clear() }
        }
        scope.launch {
            val watched = mutableState.subscriptionCount.map { it > 0 }.distinctUntilChanged()
            combine(watched, account.tokens) { isWatched, token -> token.takeIf { isWatched } }
                .distinctUntilChanged()
                .collectLatest { token ->
                    while (token != null) {
                        refresh()
                        delay(intervalMillis)
                    }
                }
        }
    }

    suspend fun refresh(): ApiResult<Unit> = command { token ->
        val games = api.list(token).games
        apply(token) { BigGamesState(games, isLoaded = true) }
    }

    /** Signs the player up; [BigGameCard.canJoin] once the lobby is open. */
    suspend fun signUp(id: BigGameId): ApiResult<Unit> = command { token -> replace(token, api.signUp(token, id)) }

    suspend fun cancelSignup(id: BigGameId): ApiResult<Unit> =
        command { token -> replace(token, api.cancelSignup(token, id)) }

    private fun replace(token: String, card: BigGameCard) = apply(token) { current ->
        current.copy(games = current.games.map { if (it.id == card.id) card else it })
    }

    private suspend fun <T> command(call: suspend (token: String) -> T): ApiResult<T> {
        val token = account.accountToken ?: return checkNotNull(account.missingAccount())
        return apiResult(onRejected = { if (it.status == UNAUTHORIZED) account.onTokenRejected(token) }) { call(token) }
    }

    /** Changes the state with a response for [token], unless the player logged out or switched accounts meanwhile. */
    private fun apply(token: String, change: (BigGamesState) -> BigGamesState) {
        if (account.accountToken != token) return
        if (loadedFor != token) {
            clear()
            loadedFor = token
        }
        mutableState.update(change)
    }

    private fun clear() {
        loadedFor = null
        mutableState.value = BigGamesState()
    }

    private companion object {
        const val UNAUTHORIZED = 401

        /** The count of sign-ups and the lobby opening are news within a minute. */
        const val INTERVAL_MILLIS = 30_000L
    }
}

/** What the «Play» tab shows of the big games. */
data class BigGamesState(
    /** Ahead and going on, soonest first. */
    val games: List<BigGameCard> = emptyList(),
    /** False until the first [BigGameManager.refresh] succeeded. */
    val isLoaded: Boolean = false,
)
