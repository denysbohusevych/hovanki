package app.hovanki.server.live.v2

import app.hovanki.shared.live.v2.CloseReason
import app.hovanki.shared.live.v2.Down
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * The meters of the live channel v2 (docs/live-v2/server.md §5): counts and bytes, never a player, a game, a session, an
 * address or what a message carries. The syncs themselves are `hovanki.game.sync{transport=ws2|udp}` of
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
    private val bytesIn = listOf(WS2, UDP).associateWith { bytes("in", it) }
    private val bytesOut = listOf(WS2, UDP).associateWith { bytes("out", it) }
    private val datagramsIn = datagrams("in")
    private val datagramsOut = datagrams("out")
    private val rare = ConcurrentHashMap<String, Counter>()

    /** An open answered: `ok`, `disabled`, `rejected`, `not_found`, `too_many`. */
    fun opened(result: String) =
        count("hovanki.live.open", "Opens of a live session, by their answer", "result", result)

    /** A Down sent over [transport] ([WS2], [UDP]), [bytes] long. */
    fun down(kind: DownKind, down: Down, bytes: Int, transport: String) {
        val state = when {
            down.state_id == 0 -> NONE
            down.base_state_id == 0 -> FULL
            else -> DELTA
        }
        downs.getValue(kind).getValue(state).increment()
        bytesOut[transport]?.increment(bytes.toDouble())
    }

    /** An Up came over [transport], [bytes] long. */
    fun up(bytes: Int, transport: String) {
        bytesIn[transport]?.increment(bytes.toDouble())
    }

    /** A datagram came in (any, before it is looked at), or went out. */
    fun datagramIn() = datagramsIn.increment()

    fun datagramOut() = datagramsOut.increment()

    /** Packet numbers a session's phone skipped (spec §5.3): datagrams lost on the way in, or still coming. */
    fun lost(datagrams: Long) = rare.computeIfAbsent("hovanki.live.lost{in}") {
        Counter.builder("hovanki.live.lost")
            .description("Gaps in the packet numbers of the phones' datagrams: an estimate of the ones lost")
            .tags("dir", "in")
            .register(registry)
    }.increment(datagrams.toDouble())

    /** A datagram of a known session that did not decrypt. */
    fun authFailed() = count("hovanki.live.auth_fail", "Datagrams of a live session that did not decrypt")

    /** A RESET sent (spec §5.5): a datagram of a session the server does not know. */
    fun reset() = count("hovanki.live.reset", "RESETs sent to datagrams of unknown live sessions")

    /** A session moved to [to] ([WS2], [UDP]) from the other transport. */
    fun switched(to: String) = count(
        "hovanki.live.transport_switch",
        "Live sessions that moved between UDP and the WebSocket v2, by where to",
        "to",
        to,
    )

    /** The UDP socket did not open (the port taken, no rights): the opens give no UDP address for a minute. */
    fun udpBindFailed() = count("hovanki.live.udp_bind_failed", "Tries to open the live channel's UDP that failed")

    /** A live session ended for [reason] (spec §13). */
    fun closed(reason: CloseReason) = count(
        "hovanki.live.close",
        "Live sessions ended, by reason",
        "reason",
        reason.name.removePrefix("CLOSE_").lowercase(),
    )

    /** A frame that is no Up: skipped, the link stays. */
    fun badMessage() = count("hovanki.live.bad_message", "Frames of the live channel v2 that are no Up")

    /**
     * A message over the limits: a frame over [app.hovanki.shared.live.LiveLimits.MAX_WS_FRAME_BYTES] (its WebSocket
     * closed), or a Down over [app.hovanki.shared.live.LiveLimits.MAX_FRAGMENTS] datagrams (its session closed).
     */
    fun oversize() = count("hovanki.live.oversize", "Frames over the live channel v2's limit")

    private fun bytes(dir: String, transport: String) = Counter.builder("hovanki.live.bytes")
        .description("Bytes of the live channel v2's messages, by direction and transport")
        .tags("dir", dir, "transport", transport)
        .register(registry)

    private fun datagrams(dir: String) = Counter.builder("hovanki.live.datagrams")
        .description("Datagrams of the live channel v2's UDP, by direction")
        .tags("dir", dir)
        .register(registry)

    private fun count(name: String, about: String, vararg tags: String) =
        rare.computeIfAbsent(name + tags.joinToString(",", "{", "}")) {
            Counter.builder(name).description(about).tags(*tags).register(registry)
        }.increment()

    companion object {
        /** The tag of the WebSocket v2 ([app.hovanki.server.metrics.SyncTransport.WS2]'s too). */
        const val WS2 = "ws2"

        /** The tag of the UDP ([app.hovanki.server.metrics.SyncTransport.UDP]'s too). */
        const val UDP = "udp"
        private const val NONE = "none"
        private const val FULL = "full"
        private const val DELTA = "delta"
    }
}
