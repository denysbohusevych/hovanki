package app.hovanki.client.network

import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.SyncRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * MVP transport: one `sync` call every [app.hovanki.shared.protocol.GameRules.syncIntervalSeconds]
 * that uploads the queued samples and returns the fresh snapshot. Failures are retried with exponential backoff.
 */
class PollingGameConnection(private val api: GameApi) : GameConnection {
    override fun connect(session: PlayerSession, outbox: LocationOutbox): Flow<ConnectionEvent> = flow {
        var backoffMillis = MIN_BACKOFF_MILLIS
        while (true) {
            val samples = outbox.drain()
            val snapshot = try {
                api.sync(session, SyncRequest(samples))
            } catch (e: CancellationException) {
                outbox.requeue(samples)
                throw e
            } catch (e: Exception) {
                val endReason = (e as? ApiException)?.endReason()
                if (endReason != null) {
                    emit(ConnectionEvent.Ended(endReason))
                    return@flow
                }
                // A 4xx rejects the request itself: sending the same samples again would fail forever.
                val rejected = e is ApiException && e.status in 400..499
                if (!rejected) outbox.requeue(samples)
                emit(ConnectionEvent.Problem(e, backoffMillis))
                delay(backoffMillis)
                backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
                continue
            }
            backoffMillis = MIN_BACKOFF_MILLIS
            emit(ConnectionEvent.Snapshot(snapshot))
            delay(snapshot.settings.rules.syncIntervalSeconds.coerceAtLeast(1) * 1000L)
        }
    }

    private fun ApiException.endReason(): EndReason? = when (status) {
        401 -> EndReason.SESSION_REJECTED
        404 -> EndReason.GAME_NOT_FOUND
        else -> null
    }

    companion object {
        const val MIN_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 15_000L
    }
}
