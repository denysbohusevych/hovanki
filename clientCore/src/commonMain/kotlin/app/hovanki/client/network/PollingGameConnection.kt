package app.hovanki.client.network

import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.SyncRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * MVP transport: one `sync` call every [app.hovanki.shared.protocol.GameRules.syncIntervalSeconds] (or the pause the
 * caller asks for, see [GameConnection.connect]) that uploads the queued samples and returns the fresh snapshot.
 * Failures are retried with exponential backoff.
 */
class PollingGameConnection(private val api: GameApi) : GameConnection {
    override fun connect(
        session: PlayerSession,
        outbox: LocationOutbox,
        chatAfter: () -> Long?,
        extras: () -> SyncExtras,
        intervalMillis: (GameSnapshot) -> Long,
    ): Flow<ConnectionEvent> = flow {
        var backoffMillis = MIN_BACKOFF_MILLIS
        while (true) {
            val samples = outbox.drain()
            val snapshot = try {
                // Sightings are not sent again after a failure: by then they are stale.
                val (nearby, device) = extras()
                api.sync(session, SyncRequest(samples, chatAfter(), nearby, device))
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
            delay(intervalMillis(snapshot).coerceAtLeast(MIN_INTERVAL_MILLIS))
        }
    }

    private fun ApiException.endReason(): EndReason? = when (status) {
        401 -> EndReason.SESSION_REJECTED
        404 -> EndReason.GAME_NOT_FOUND
        else -> null
    }

    companion object {
        /** Never faster than this, whatever the caller asks. */
        const val MIN_INTERVAL_MILLIS = 500L

        const val MIN_BACKOFF_MILLIS = 1_000L

        /**
         * Short even so: a failed sync is usually a bad mobile network, not a busy server, and a player without a sync
         * for `staleLocationRevealSeconds` (45 s) is revealed to the seekers. Still no more requests than polling.
         */
        const val MAX_BACKOFF_MILLIS = 5_000L
    }
}
