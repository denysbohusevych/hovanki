package app.hovanki.client.spectator

import app.hovanki.client.account.AccountManager
import app.hovanki.client.network.ApiException
import app.hovanki.client.network.ApiResult
import app.hovanki.client.network.SpectatorApi
import app.hovanki.client.network.apiResult
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.SpectatorSession
import app.hovanki.shared.protocol.SpectatorSnapshot
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.WatchRequest
import app.hovanki.shared.rules.StreetZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Watching an open game (docs/adr/0011-spectators-and-recordings.md): the logged-in player watches a game by its code,
 * the game's delay behind, without playing: polled every few seconds, from the server only. Nothing of it is saved on
 * the device: closing the app ends the watching. App-scoped; runs on the main thread. Commands return an [ApiResult];
 * none throws.
 */
class SpectatorManager(
    private val api: SpectatorApi,
    private val account: AccountManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val time: TimeSource = TimeSource.Monotonic,
) {
    private val mutableState = MutableStateFlow(SpectatorState())
    val state: StateFlow<SpectatorState> = mutableState.asStateFlow()

    private var polling: Job? = null

    /** The account the watching belongs to: logging out, or in as someone else, ends it. */
    private var watchingAs: String? = null

    init {
        scope.launch {
            account.tokens.collect { token -> if (watchingAs != null && token != watchingAs) stop() }
        }
    }

    /** Starts watching the open game of [joinCode]: rejected when it is not open, or the player plays in it. */
    suspend fun watch(joinCode: String): ApiResult<Unit> {
        val token = account.accountToken ?: return checkNotNull(account.missingAccount())
        val result = apiResult(onRejected = { if (it.status == UNAUTHORIZED) account.onTokenRejected(token) }) {
            api.watch(token, WatchRequest(joinCode.trim()))
        }
        val response = when (result) {
            is ApiResult.Success -> result.value
            is ApiResult.Rejected -> return result
            is ApiResult.Network -> return result
        }
        // Logged out meanwhile: nobody to watch for.
        if (account.accountToken != token) return ApiResult.Success(Unit)
        stop()
        watchingAs = token
        mutableState.value = SpectatorState(response.session, response.snapshot, time.markNow())
        polling = scope.launch { poll(response.session) }
        return ApiResult.Success(Unit)
    }

    /** Stops watching; the server is told in the background, and forgets the spectator in half a minute anyway. */
    fun stop() {
        polling?.cancel()
        polling = null
        watchingAs = null
        val session = mutableState.value.session
        mutableState.value = SpectatorState()
        if (session != null) {
            scope.launch {
                try {
                    api.leave(session)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Gone already, or no network: the server stops counting the spectator by itself.
                }
            }
        }
    }

    private suspend fun poll(session: SpectatorSession) {
        while (true) {
            val snapshot = mutableState.value.snapshot ?: return
            // The end as the spectators see it: nothing changes after it.
            if (snapshot.phase == GamePhase.FINISHED) return
            delay(snapshot.settings.rules.syncIntervalSeconds * MILLIS_PER_SECOND)
            val next = try {
                api.spectate(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // 401/403/404: the game is over and gone, or its host closed it to spectators.
                if (e.status in ENDED_STATUSES) {
                    mutableState.update { if (it.session == session) it.copy(ended = true) else it }
                    return
                }
                mutableState.update { if (it.session == session) it.copy(isReconnecting = true) else it }
                continue
            } catch (e: Exception) {
                mutableState.update { if (it.session == session) it.copy(isReconnecting = true) else it }
                continue
            }
            mutableState.update {
                if (it.session == session) {
                    it.copy(snapshot = next, receivedAt = time.markNow(), isReconnecting = false)
                } else {
                    it
                }
            }
            loadStreetZone(session, next)
        }
    }

    /** The zone by streets, once per map revision; a failed attempt is retried with the next snapshot. */
    private suspend fun loadStreetZone(session: SpectatorSession, snapshot: SpectatorSnapshot) {
        if (snapshot.streetZone != StreetZoneState.READY) return
        if (mutableState.value.streetZoneRevision == snapshot.mapRevision) return
        val zone = try {
            api.streetZone(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        if (zone.state != StreetZoneState.READY || zone.mapRevision != snapshot.mapRevision) return
        val streets = zone.stages.takeIf { it.isNotEmpty() }?.let { stages ->
            runCatching { StreetZone(stages) }.getOrNull()
        } ?: return
        mutableState.update {
            if (it.session == session) it.copy(streetZone = streets, streetZoneRevision = zone.mapRevision) else it
        }
    }

    private companion object {
        const val UNAUTHORIZED = 401
        const val MILLIS_PER_SECOND = 1000L
        val ENDED_STATUSES = setOf(401, 403, 404)
    }
}

/** What the spectator's screen shows. */
data class SpectatorState(
    val session: SpectatorSession? = null,
    val snapshot: SpectatorSnapshot? = null,
    /** When [snapshot] arrived: the game shown moves on until the next one. */
    val receivedAt: TimeMark? = null,
    /** The zone by streets of a game played with one, once loaded. */
    val streetZone: StreetZone? = null,
    val streetZoneRevision: Int? = null,
    /** The last poll failed: what is shown is getting old. */
    val isReconnecting: Boolean = false,
    /** The server ended the watching: the game is over and gone, or its host closed it to spectators. */
    val ended: Boolean = false,
) {
    val isWatching: Boolean get() = session != null

    /** The moment of the game shown right now (server time): the snapshot's, plus the time since it arrived. */
    fun shownAtMillis(): Long? {
        val snapshot = snapshot ?: return null
        val elapsed = receivedAt?.elapsedNow()?.inWholeMilliseconds ?: 0
        return snapshot.atMillis + elapsed
    }
}
