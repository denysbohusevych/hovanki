package app.hovanki.server.live.v2

import app.hovanki.shared.live.v2.CloseReason
import app.hovanki.shared.live.v2.Down
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * The meters of the live channel v2 (docs/live-v2/server.md §5): counts and bytes, never a player, a game, a session or
 * what a message carries. The syncs themselves are `hovanki.game.sync{transport=ws2}` of
 * [app.hovanki.server.metrics.ServerMetrics]; the sessions are [LiveSessions]' gauge.
 */
@Component
class LiveMetrics(private val registry: MeterRegistry) {
    /** The kinds of Down: an answer to an Up, a push of the state, a push of the chat. */
    enum class DownKind { REPLY, PUSH, CHAT }

    private val downs = DownKind.entries.associateWith { kind ->
        listOf(NONE, FULL, DELTA).associateWith { state ->
            Counter.builder("hovanki.live.down")
                .description("Downs sent, by kind and by the state they carry")
                .tags("kind", kind.name.lowercase(), "state", state)
                .register(registry)
        }
    }
    private val bytesIn = bytes("in")
    private val bytesOut = bytes("out")
    private val rare = ConcurrentHashMap<String, Counter>()

    /** An open answered: `ok`, `disabled`, `rejected`, `not_found`, `too_many`. */
    fun opened(result: String) =
        count("hovanki.live.open", "Opens of a live session, by their answer", "result", result)

    /** A Down sent, [bytes] long. */
    fun down(kind: DownKind, down: Down, bytes: Int) {
        val state = when {
            down.state_id == 0 -> NONE
            down.base_state_id == 0 -> FULL
            else -> DELTA
        }
        downs.getValue(kind).getValue(state).increment()
        bytesOut.increment(bytes.toDouble())
    }

    /** An Up came, [bytes] long. */
    fun up(bytes: Int) = bytesIn.increment(bytes.toDouble())

    /** A live session ended for [reason] (spec §13). */
    fun closed(reason: CloseReason) = count(
        "hovanki.live.close",
        "Live sessions ended, by reason",
        "reason",
        reason.name.removePrefix("CLOSE_").lowercase(),
    )

    /** A frame that is no Up: skipped, the link stays. */
    fun badMessage() = count("hovanki.live.bad_message", "Frames of the live channel v2 that are no Up")

    /** A frame over [app.hovanki.shared.live.LiveLimits.MAX_WS_FRAME_BYTES]: its WebSocket closed. */
    fun oversize() = count("hovanki.live.oversize", "Frames over the live channel v2's limit")

    private fun bytes(dir: String) = Counter.builder("hovanki.live.bytes")
        .description("Bytes of the live channel v2's messages, by direction and transport")
        .tags("dir", dir, "transport", WS2)
        .register(registry)

    private fun count(name: String, about: String, vararg tags: String) =
        rare.computeIfAbsent(name + tags.joinToString(",", "{", "}")) {
            Counter.builder(name).description(about).tags(*tags).register(registry)
        }.increment()

    companion object {
        /** The tag of the WebSocket v2 ([app.hovanki.server.metrics.SyncTransport.WS2]'s too). */
        const val WS2 = "ws2"
        private const val NONE = "none"
        private const val FULL = "full"
        private const val DELTA = "delta"
    }
}
