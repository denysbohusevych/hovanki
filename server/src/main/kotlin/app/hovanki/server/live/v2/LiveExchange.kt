package app.hovanki.server.live.v2

import app.hovanki.server.features.FeatureFlags
import app.hovanki.server.game.GameException
import app.hovanki.server.game.GameRegistry
import app.hovanki.server.game.GameService
import app.hovanki.server.live.v2.LiveMetrics.DownKind
import app.hovanki.server.metrics.SyncTransport
import app.hovanki.shared.live.LiveDownlink
import app.hovanki.shared.live.LiveSections
import app.hovanki.shared.live.LiveUplink
import app.hovanki.shared.live.v2.ChatMessage
import app.hovanki.shared.live.v2.CloseReason
import app.hovanki.shared.live.v2.Down
import app.hovanki.shared.live.v2.Up
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.SyncRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * What a live session says (docs/live-v2/spec.md §7–8, §11, §13; server.md §3.1, §4.5–4.6): an Up is v1's sync of the
 * same player by the same [GameService.sync], answered with the state cut from v1's snapshot ([LiveSections]) as a
 * delta against what the phone acknowledged; a push is the same state, built when the game pokes the player. So the
 * phone sees exactly what v1 shows it. Both go out under the session's lock, in order; nothing of them is logged.
 */
@Component
class LiveExchange(
    private val games: GameService,
    private val registry: GameRegistry,
    private val sessions: LiveSessions,
    private val features: FeatureFlags,
    private val metrics: LiveMetrics,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The phone's [up] of [session], come over [link]: synced, and the Down sent back on the same link. */
    fun up(session: LiveSession, up: Up, link: LiveLink) {
        session.locked {
            if (session.closed) return
            val now = clock.millis()
            session.lastUpAtMillis = now
            if (up.seq <= session.lastSeq) {
                // Overtaken by a later Up with the same fixes and report (spec §7.1): its acknowledgement only.
                session.states.acknowledge(up.ack_state)
                return
            }
            sessions.refusal(session, now)?.let { reason ->
                sessions.end(session, reason)
                return
            }
            session.states.acknowledge(up.ack_state)
            if (up.full) session.states.forgetAcknowledged()
            val wait = session.countUp(System.nanoTime())
            if (wait > 0) {
                val error = ApiError(ErrorCode.BAD_REQUEST, "Too many Ups", ErrorReason.TOO_MANY_REQUESTS)
                refuse(session, link, up.seq, now, error, retryAfterSeconds = (wait + 999) / 1000)
                return
            }
            up.device?.let { session.device = LiveUplink.deviceReport(it) }
            val request = SyncRequest(LiveUplink.samples(up), up.chat_after, LiveUplink.nearby(up), session.device)
            val snapshot = try {
                games.sync(session.ref, session.ref.gameId, request, SyncTransport.WS2)
            } catch (e: GameException) {
                val reason = closeReason(session, e)
                if (reason != null) {
                    sessions.end(session, reason)
                } else {
                    val error = ApiError(e.code, e.message.orEmpty(), e.reason, e.untilMillis)
                    refuse(session, link, up.seq, now, error, e.retryAfterSeconds)
                }
                return
            } catch (e: Exception) {
                log.error("Unhandled error in a live Up", e)
                refuse(session, link, up.seq, now, ApiError(ErrorCode.INTERNAL, "Internal error"), null)
                return
            }
            session.lastSeq = up.seq
            val chat = LiveDownlink.fitChat(snapshot.chat)
            send(link, stateDown(session, snapshot, up.seq, push = false, chat.messages, chat.more), DownKind.REPLY)
        }
    }

    /**
     * Pushes [session] what changed for its player (spec §8.7, §11): new chat messages as they are, then the state as a
     * delta against what the phone acknowledged, if it differs from the last state sent. Nothing without a link.
     */
    fun push(session: LiveSession, pending: LiveSession.Pending) {
        session.locked {
            val link = session.link ?: return
            if (session.closed) return
            val now = clock.millis()
            sessions.refusal(session, now)?.let { reason ->
                sessions.end(session, reason)
                return
            }
            if (pending.chat.isNotEmpty()) {
                val chat = LiveDownlink.fitChat(pending.chat)
                val down = Down(
                    ack_up = session.lastSeq,
                    server_time_ms = now,
                    chat = chat.messages,
                    chat_push = true,
                    chat_more = chat.more || pending.more,
                )
                send(link, down, DownKind.CHAT)
            }
            if (!pending.state) return
            val snapshot = try {
                // Read only: a push is no sign that the phone is there (docs/live-v2/server.md §3.2).
                games.withGame(session.ref, session.ref.gameId) { game, at ->
                    game.snapshotFor(session.ref.playerId, at, null, features.enabledNames())
                }
            } catch (e: GameException) {
                closeReason(session, e)?.let { sessions.end(session, it) }
                return
            }
            val down = stateDown(session, snapshot, replyTo = 0, push = true)
            if (down.state_id != 0) send(link, down, DownKind.PUSH)
        }
    }

    private fun stateDown(
        session: LiveSession,
        snapshot: GameSnapshot,
        replyTo: Int,
        push: Boolean,
        chat: List<ChatMessage> = emptyList(),
        chatMore: Boolean = false,
    ): Down {
        val next = session.states.next(LiveSections.fromSnapshot(snapshot, session.json), push)
        return Down(
            reply_to = replyTo,
            ack_up = session.lastSeq,
            server_time_ms = snapshot.serverTimeMillis,
            state_id = next?.stateId ?: 0,
            base_state_id = next?.baseStateId ?: 0,
            state = next?.state,
            chat = chat,
            chat_more = chatMore,
        )
    }

    /** The Up [seq] refused, the session stays (spec §13): v1's `ServerFrame.Error`. */
    private fun refuse(
        session: LiveSession,
        link: LiveLink,
        seq: Int,
        now: Long,
        error: ApiError,
        retryAfterSeconds: Long?,
    ) {
        val down = Down(
            reply_to = seq,
            ack_up = session.lastSeq,
            server_time_ms = now,
            error = LiveDownlink.error(error, retryAfterSeconds),
        )
        send(link, down, DownKind.REPLY)
    }

    private fun send(link: LiveLink, down: Down, kind: DownKind) {
        val bytes = Down.ADAPTER.encode(down)
        if (link.send(bytes)) metrics.down(kind, down, bytes.size)
    }

    /**
     * The errors that end the session rather than one Up (spec §13): the 401 and 404 of v1. A 404 of a game that is
     * still there is its player gone (left, between the game's change and the token's revocation): their session.
     */
    private fun closeReason(session: LiveSession, e: GameException): CloseReason? = when (e.code) {
        ErrorCode.UNAUTHORIZED, ErrorCode.FORBIDDEN -> CloseReason.CLOSE_SESSION_REJECTED

        ErrorCode.NOT_FOUND ->
            if (registry.get(session.ref.gameId) == null) {
                CloseReason.CLOSE_GAME_NOT_FOUND
            } else {
                CloseReason.CLOSE_SESSION_REJECTED
            }

        else -> null
    }
}
